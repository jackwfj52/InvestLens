# G04：本地 MySQL、Redis 与 Flyway

本阶段只用 `compose.dev.yaml` 启动两个依赖，后端在宿主机运行。完整四容器部署属于 G37。MySQL 保存事实数据；Redis 只放可重建的缓存，不承担任务、报告或预算的持久真相。

## 首次准备

需要可运行 Linux 容器的 Docker Engine / Docker Desktop、Docker Compose v2.20+、JDK 17。Maven Wrapper 首次运行需要下载 Maven 和依赖；IDEA 的项目 SDK、Maven Runner 同样使用 JDK 17。

在项目根目录执行：

```powershell
# 生成 .env，含三份独立的 256 bit 随机十六进制密码；已有文件不会被覆盖。
.\scripts\dev.ps1 init
# 检查配置不打印解析后的密码，等待两个依赖通过健康检查。
.\scripts\dev.ps1 up
.\scripts\dev.ps1 status
```

`.env.example` 只有空凭据占位；实际 `.env`、`application-local.*` 已被 Git 忽略。已有 `.env` 时按示例补齐缺失变量并自行设置密码，脚本不会合并或覆盖。共享文件使用简单 `KEY=value`，不加引号、`export`、转义、`${...}` 或行尾注释；密码建议沿用随机十六进制格式，以兼容 Compose dotenv 和 Java properties 的读取规则。不要把解析后的 `docker compose config` 输出贴到日志；只使用 `config --quiet`。

| 依赖 | 固定镜像 | 宿主机地址 | 保存方式 |
|---|---|---|---|
| MySQL | `mysql:8.4.13` | `127.0.0.1:13306` | `investlens-dev_mysql_data` 命名卷 |
| Redis | `redis:7.2.16-alpine` | `127.0.0.1:16379` | 无 AOF / RDB，128 MB、LRU 淘汰 |
| 后端 | 宿主机 JDK 17 | `127.0.0.1:8080` | MySQL |

端口可在 `.env` 的 `MYSQL_PORT` / `REDIS_PORT` / `SERVER_PORT` 调整。容器内仍使用 3306 / 6379；`MYSQL_HOST` / `REDIS_HOST` 为宿主机后端连接地址。应用使用独立的 `MYSQL_USER`，不使用 root。MySQL 的字符集为 utf8mb4、排序规则为 utf8mb4_0900_ai_ci，数据库及 JDBC 会话时区均为 UTC。本地回环连接使用非 TLS JDBC，只适用于当前本机开发环境；远程连接须另配 TLS。

MySQL 的应用用户和密码由镜像在**空数据卷第一次初始化**时创建。修改 `.env` 的密码不会修改已有数据库账户，需通过受控 SQL 修改账户并同步配置。不要通过删除卷来尝试解决密码不匹配。

## 中国大陆网络下准备镜像

运行应用不要求翻墙。首次镜像和 Maven 下载是否可直连需在用户网络验证；本机下载成功不能代替中国大陆原生网络验收。固定版本标签来自 [Docker 官方 MySQL 镜像清单](https://github.com/docker-library/official-images/blob/master/library/mysql) 和 [Redis 镜像清单](https://github.com/docker-library/official-images/blob/master/library/redis)。MySQL 版本对应 [8.4 系列发布说明](https://dev.mysql.com/doc/relnotes/mysql/8.4/en/)；Redis 7.2 系列适用 BSD-3，见 [官方许可说明](https://redis.io/legal/licenses/)。镜像中附带软件有各自许可。

若直连 Docker Hub 不稳定，可以由用户配置已获准的 Docker registry mirror，或将 `.env` 中的 `MYSQL_IMAGE` / `REDIS_IMAGE` 指向可信内网仓库的同版本镜像。不要静默切换到未知公共镜像代理。也可在获准环境准备离线包：

```powershell
docker pull mysql:8.4.13
docker pull redis:7.2.16-alpine
docker save -o investlens-dev-images.tar mysql:8.4.13 redis:7.2.16-alpine
# 将归档及校验值传到目标机器，再执行：
docker load -i investlens-dev-images.tar
```

离线包放到仓库外；核对 SHA-256 和镜像 digest 后再用。无外网机器上的首次 Maven 编译还需准备对应版本的 Maven / `.m2` 依赖缓存。开发 Compose 不承担在宿主机安装 JDK、Docker 或配置系统代理的工作。

## 启动后端和测试

```powershell
# 如 JAVA_HOME 未配置，先将它指向本机的 JDK 17。
.\scripts\dev.ps1 run
# 另一个终端：全套测试包含真实 MySQL / Redis，必须先 up。
.\scripts\dev.ps1 test
```

脚本在 `backend/` 中启动 Maven。IDEA 中运行 `BackendApplication`，设 Active profiles 为 `dev`、Working directory 为 `$PROJECT_DIR$/backend`（若单独打开 backend，则选当前模块目录）。`application-dev.properties` 导入 `../.env`；系统环境变量优先于文件。只运行不启用 dev 的后端时，直接提供 `MYSQL_PASSWORD`、`REDIS_PASSWORD` 等环境变量。不需要行情 Key 或模型 Key 就能完成 G04 的启动检查。

等价命令：

```powershell
Set-Location backend
.\mvnw.cmd -B -ntp '-Dspring-boot.run.profiles=dev' spring-boot:run
# 测试类自身启用 dev，仍需以 backend 为工作目录。
.\mvnw.cmd -B -ntp test
```

Flyway 默认启用并在应用启动时先迁移再提供服务；V1 仅执行 `SELECT 1` 建立迁移历史，不提前建领域空表。G05 从 `V2__...sql` 开始。禁止编辑已应用的迁移、自动 baseline 非空数据库或 Flyway clean。校验失败、账户错误或 MySQL 不可达时必须修复根因，不能关闭 Flyway 绕过。需要独立空 schema；不导入 WealthFlow SQLite SQL 或 DbSchemaMigration。

## 检查与停止

```powershell
docker compose --env-file .env -f compose.dev.yaml config --quiet
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/liveness
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness

# 容器内使用环境变量提供密码，不在宿主机命令行展开。
docker compose -f compose.dev.yaml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" -D "$MYSQL_DATABASE" -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank"'
docker compose -f compose.dev.yaml exec -T redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'
git check-ignore .env backend/src/main/resources/application-local.properties

# 先停止宿主机的后端（Ctrl+C），再停依赖；命名卷保留。
.\scripts\dev.ps1 down
```

Actuator 只开放 health / info，健康响应保留原生格式，不套业务 API envelope，也不展示连接详情。liveness 只检查进程；readiness 检查进程、MySQL、Redis，依赖故障返回 503。当前阶段 Redis 停止会使 readiness DOWN；业务缓存故障降级仍待 G36（主提交 31）验收。env / configprops / flyway / shutdown 不对 HTTP 暴露。

普通 `down` / `up` 保留 MySQL 卷。带 `-v` 的删除会清除数据库，日常命令不包含它。集成测试只检查连接、迁移历史、临时 Redis key、健康端点，重复执行不会删除业务表或重置数据库。版本、结果和重启验收见 [G04 验收记录](g04-infrastructure-acceptance.md)。
