# G04 验收记录

日期：2026-10-23，Asia/Taipei。范围：开发依赖 Compose、后端配置、Flyway 基线与健康检查。不包含 G37 四容器部署、领域表或 Redis 业务降级。

## 环境与版本

- Windows，Docker Desktop 4.96.3，Linux / amd64 Engine 29.9.2，Compose v5.6.1。
- JDK 17.0.12、Maven Wrapper 3.10.0、Spring Boot 4.0.9，沿用 BOM 管理的 Flyway 11.14.1。
- `mysql:8.4.13`，实际 `SELECT VERSION()` 返回 8.4.13。
- `redis:7.2.16-alpine`，启用密码、无 AOF / RDB、128 MB、allkeys-lru。
- 本次解析的镜像 digest（供重现核对，版本升级需重新验收）：
  - `mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69`
  - `redis@sha256:f8548b29a1f1cb358b5fcfec1e0126beab57ad15776c1e87623f865579f16c5a`

## 已执行

1. `scripts/dev.ps1 init` 创建真实 `.env`；三份不同的 64 位十六进制随机密码，未输出值。再次 init 拒绝覆盖，文件 SHA-256 保持不变。
2. `git check-ignore` 确认 `.env` 和三种 `application-local.*` 被忽略。示例保留空密码；真实配置、数据库、日志、构建产物均未暂存。
3. `docker compose --env-file .env -f compose.dev.yaml config --quiet` 通过；`scripts/dev.ps1 up` 拉取两个固定版本镜像，等待 healthcheck 成功。端口仅绑定 `127.0.0.1:13306` / `127.0.0.1:16379`。
4. `mvnw.cmd -o -B -ntp test-compile` 通过，随后完整 `mvnw.cmd -o -B -ntp test`：**44 项通过，0 失败、0 错误、0 跳过**。包含既有 G03 的 41 项和 G04 的 3 项真实集成检查，无 H2 / 数据库 mock。
5. 应用用户可连接 MySQL，且不是 root；数据库字符集 utf8mb4、排序规则 utf8mb4_0900_ai_ci，JDBC 会话时区 `+00:00`。首次应用启动创建 `flyway_schema_history`，成功执行 V1，校验通过且无 pending migration。应用 schema 中只有迁移历史表。
6. Redis 随机临时 key 的写入、读取、30 秒 TTL 与清理通过；容器内带环境密码的 `redis-cli ping` 返回 PONG，未认证请求返回 NOAUTH。
7. `scripts/dev.ps1 run` 启动真实后端于 `127.0.0.1:8080`，dev profile 从根 `.env` 读取配置，Flyway 识别 schema 已为 V1，不重复应用。启动本身不调用模型或行情服务。
8. 真实 HTTP 健康端点返回下表结果，无 components / details，不包装业务 envelope。env / configprops / flyway / shutdown 均为 HTTP 404。
9. `scripts/dev.ps1 down` 后再次 `up`，容器及网络重新创建，命名卷保留；历史记录仍为 `installed_rank=1, version=1, checksum=1860727987, installed_on=2026-10-23 10:43:43 UTC, success=1`。重建后经 `scripts/dev.ps1 test` 再次执行全套检查，44 项通过，迁移校验成功。

| 实际操作 | `/actuator/health` | `/health/liveness`¹ | `/health/readiness`¹ |
|---|---|---|---|
| 两依赖正常 | 200 / UP | 200 / UP | 200 / UP |
| 停 Redis | 503 / DOWN | 200 / UP | 503 / DOWN |
| 恢复 Redis | 200 / UP | 200 / UP | 200 / UP |
| 停 MySQL | 503 / DOWN | 200 / UP | 503 / DOWN |
| 恢复 MySQL | 200 / UP | 200 / UP | 200 / UP |

¹ 表中后两列也位于 `/actuator` 下。故障操作仅作用于本次创建的 `investlens-dev` 容器，未改动原有本机 MySQL / Redis。

## 已知提示与边界

- 初次离线执行 `spring-boot:run` 因本地未缓存启动插件的四个依赖而失败；在线补齐后脚本启动成功。首次安装依赖不是完全离线操作，准备方式见 [本地开发说明](local-development.md)。
- Maven 元数据探测访问 Flyway 声明的 GitHub Packages 仓库返回 401，但构建所需依赖已从 Maven Central 解析，实际编译 / 测试 / 启动均成功，不需要 GitHub token。
- Flyway 11.14.1 的 MySQL 插件输出“最高验证版本 8.1”的兼容性提示。未屏蔽该提示或擅自覆盖 Boot BOM；本次仅证实 MySQL 8.4.13 上 V1 的迁移、校验和重复启动通过。后续每个领域迁移仍须在该真实 MySQL 上验收。
- MyBatis 当前没有 Mapper，启动有相应提示；G05 开始添加领域 Mapper，不为消除提示创建占位 Mapper。
- 本机镜像下载成功不等于中国大陆原生网络可达性已获认证；已提供可信仓库覆盖及离线镜像加载方法，不要求用户使用代理或 VPN。本轮未执行另一台中国大陆新机器的镜像 / Maven 下载验收。
- 尚无 Decision 等业务数据，保留验证针对真实迁移历史；后续业务数据重启验收随领域功能添加。Redis 业务降级为 G36 的范围。
