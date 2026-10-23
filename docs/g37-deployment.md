# G37：本机四容器部署

## 前提与首次启动

需要 Docker Engine/Desktop（Linux 容器）、Compose v2.20+、可访问镜像仓库、Maven Central、npm、Debian 软件仓库及 PyPI。源码构建不要求宿主机安装 Java/Node/Python；首次下载与构建需要网络和时间，不是零配置离线启动。

在项目根目录运行：

```powershell
.\scripts\dev.ps1 init
.\scripts\deploy.ps1 config
.\scripts\deploy.ps1 up
.\scripts\deploy.ps1 status
```

如果已存在 `.env`，跳过 init，不覆盖旧密码。入口为 `http://127.0.0.1:8080/workbench`；端口冲突时在 `.env` 设置 APP_PORT。Linux/macOS 可用 `docker compose --env-file .env -f compose.yaml up -d --build --wait --wait-timeout 240`，首次 `.env` 需按 `.env.example` 配置三个独立的强密码。

## 运行边界

- frontend/backend/mysql/redis 四服务均有健康检查；仅 frontend 的 8080 映射到主机回环地址，数据库、Redis 和后端没有主机端口。
- Nginx 提供 SPA、同源 API 与唯一 readiness 探针；SSE 关闭缓冲/缓存、保留 Last-Event-ID，代理读超时大于流寿命和心跳周期。
- 前后端以非 root 用户运行、只读根文件系统、临时目录限额、去除 capabilities、禁止提权。
- 后端包含 Java 17、Node 24、Python、时区及 AKShare 1.19.1 的固定本地解码资源；仅安装软件包不授予行情保存、展示或模型使用许可。
- 模型和实际行情传输默认禁用。启用模型前由用户配置自己的 LLM_BASE_URL、LLM_MODEL、LLM_API_KEY（或 DEEPSEEK_API_KEY）和 INVESTLENS_LLM_ENABLED=true。不会在构建时读取或复制 `.env`、Key、Git、数据库、日志和宿主机构建产物。
- 模型 URL 必须从容器可达，远端只接受 HTTPS；容器的 127.0.0.1 不是宿主机。当前不默认放行任意宿主 HTTP 别名，要接宿主模型需提供受 TLS 保护、容器可访问的地址。
- Redis 是可选缓存，丢失不影响 MySQL 中的任务/报告和账户限额；缓存模式缺失时明确拒绝，不隐式回源。全局 `/actuator/health` 可以因 Redis 报 DOWN，但应用 readiness 只依赖 MySQL。

## 开发与部署隔离

`compose.dev.yaml`（项目 investlens-dev）仅启动开发 MySQL/Redis，供宿主机 Java/Vite 使用；`compose.yaml`（项目 investlens）启动全部应用。两者的数据卷独立，不会自动迁移开发数据。不要把两个入口混为同一数据库，也不要让不同版本的后端共用一份迁移数据库。

```powershell
.\scripts\deploy.ps1 down
.\scripts\deploy.ps1 up
```

down 不删除命名卷，重启后保留输入、Run、证据、报告和调用预算。不要使用 `down -v`，不要在已有数据卷上直接修改数据库用户名/密码而期待自动重建账户。日志有大小限制；禁止公开打印 `docker compose config` 的展开内容或容器环境。

## 更新与验收

修改源码后执行 build/up；迁移校验失败应恢复正确版本，不清空数据库或修改已执行迁移。基础镜像是有明确版本线的官方镜像，交付前需记录实际 digest；真实离线机器还需预先导入全部镜像。G38/G39 已执行合成端到端及空卷部署，真实行情、在线模型和新物理机器仍未验收，见 [逐项矩阵](acceptance.md)，不能用合成冒烟代替。

可选冒烟脚本需要宿主 Python 3.10+：`python -B scripts/check-deployment.py --faults --restart`。它只允许本机 URL，先确认容器模型关闭，再创建明确的合成记录；测试真实 Nginx/SSE、幂等创建、停止 Redis 后继续发布，以及 MySQL/后端重启后读取同一报告。记录保留在独立部署库，不自动删除用户数据；失败输出不打印响应正文或凭据。普通启动不需要宿主 Python。

G39 增加 `--project` / `--env-file` / `--label` / `--output`，首先核对本机 URL 与选定 Compose 项目的前端端口，避免故障测试操作错实例。JSON 回执包含实际对象 ID、源提交/工作区状态、输入/报告哈希与镜像 ID。`python -B scripts/check-clean-deployment.py` 自动生成全新项目与空命名卷做合成故障验证，结束时只 down 临时容器，不删除数据卷、不修改正常部署。

## 本机实测记录

2026-10-24（Asia/Taipei），Docker Engine 29.9.2，Linux 容器。本机源码构建成功、四服务 healthy；宿主机仅 frontend 的 `127.0.0.1:8080` 映射开放。容器内 Java 17.0.20.1、Node 24.22.1、Python 固定 AKShare 1.19.1 可用，后端 uid 非 0，镜像不含项目 `.env` 或 `.git`。

`scripts/check-deployment.py --faults --restart` 全部通过：SPA、同源 API、幂等任务、后端 -8.50% 合成指标、真实 SSE 顺序/Last-Event-ID/终态关闭、Redis 停止后 readiness/原报告/新报告、MySQL 与后端重启后的报告及事件不变。浏览器核对合成模式、原时间、历史重开及 Redis 不可用提示。最后恢复 Redis，保留四容器可访问。未执行新物理环境、真实行情或在线模型验收。

实际基础镜像 manifest digest：

```text
maven:3.9.11-eclipse-temurin-17 sha256:e4a7ace3dc0d645ed97f8d9ad0b0d3f0b14fa8d150138f27f116d7105a639b82
node:24-bookworm-slim sha256:55fb5f5a527ea2c5c4a95f963e48ef90aa38c7b346054fdc8ee9957a2b55d205
nginx:1.28-alpine sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236
```

本轮应用镜像 ID：backend `sha256:b4095c0ea9b79ef3df530ea46922489d5c69393a852bd8d781b9274701154341`；frontend `sha256:9aebb910219e1e4a11043517029073bae1189ea9090c18d754a06ed26911ae5f`。运行日志、合成报告和截图位于忽略的 `backend/target/g37-*`，不提交运行数据或密钥。
