# InvestLens

**AI Investment Decision Intelligence｜AI 投资决策研究 Agent**

先检验证据与风险，再形成自己的投资判断。

## 项目文档

完整设计文档见 `docs/InvestLens-项目设计文档.md`，共 24 章，覆盖产品定位、Agent 工作流、Financial Engine、Evidence 机制、系统与数据设计、Docker 部署、云端演进、36h 开发计划及参赛展示。

当前已具备 Decision / Portfolio 的版本化保存、MySQL / Redis 开发环境和 Vue 工作台基础。输入表单、真实行情研究及完整部署仍按开发顺序推进；文档中的后续业务接口和性能目标不表示已完成验收。

开发顺序与已有代码复用建议见 [开发顺序与 WealthFlow 复用计划](docs/开发顺序与WealthFlow复用计划.md)，包含源码复用清单、阶段依赖与验收点。

## 已确定方案

- 核心对象：Decision / Hypothesis / Scenario。
- 后端 Spring Boot，前端 Vue，MySQL + Redis。
- 用户电脑采用 Docker Compose 部署；MVP 使用模块化单体。
- 金融数值由真实数据和确定性引擎生成，模型负责组织研究与解释。
- 默认研究股票、黄金和白银；首版暂以美元计价的美股及贵金属 ETF 为演示口径，现货贵金属另行适配。
- 股票/ETF 行情源优先验证 Twelve Data 或其他获准来源；FRED 为条件启用的宏观候选，Binance 留作后续 Crypto 扩展。
- P0 聚焦股票/贵金属 ETF + 现金的研究闭环，包含简版 Portfolio / Scenario。
- 微服务、K8s、弹性伸缩与资源调度作为后续 SaaS 演进设计。

## 推荐阅读

开发：第 1、5、7、10、12、18、19 章。

产品与前端：第 3—8、20 章。

PPT 与答辩：第 14、20—23 章。

文档版本：v1.1 · 2026-10-23。

## 后端开发基线

- 目录：`backend/`，Git 在项目根目录统一管理后端、后续前端及文档。
- Java 17、Spring Boot 4.0.9、Maven Wrapper。
- MyBatis Spring Boot Starter 与测试 Starter 均为 4.0.1。
- 已选依赖：Web MVC、Validation、Actuator、MySQL、Redis、Flyway、Lombok。

在 IDEA 中打开项目根目录后，将 `backend/pom.xml` 添加为 Maven 项目；也可直接打开 `backend/`。项目 SDK 与 Maven Runner 使用 JDK 17。

Windows 编译命令：

```powershell
Set-Location backend
.\mvnw.cmd -B -ntp test-compile
```

2026-10-23 已使用 JDK 17 执行以上命令，主代码与测试代码编译通过。本地凭据、IDE 配置和构建产物不纳入 Git。

## 本地依赖与启动（G04）

需要 Docker（Linux 容器）、Compose v2.20+ 和 JDK 17。在项目根目录执行：

```powershell
.\scripts\dev.ps1 init   # 首次生成已忽略的 .env，不覆盖已有配置
.\scripts\dev.ps1 up     # MySQL / Redis 健康后返回
.\scripts\dev.ps1 run    # 后端使用 dev profile，另开终端执行 test
```

后端默认 `127.0.0.1:8080`，健康检查为 `/actuator/health/readiness`。运行 `.\scripts\dev.ps1 test` 执行包含真实 MySQL / Redis 的全部后端测试；`.\scripts\dev.ps1 down` 停止依赖并保留 MySQL 数据卷。固定镜像、IDEA 配置、中国大陆网络准备及迁移规则见 [本地开发说明](docs/local-development.md)，实测结果见 [G04 验收记录](docs/g04-infrastructure-acceptance.md)。完整四容器部署仍待 G37。

## 前端开发（G07）

使用 Node.js 24 LTS 和 npm，在另一个终端执行：

```powershell
Set-Location frontend
npm ci
npm run dev
```

访问 `http://127.0.0.1:5173/workbench`。工作台包含真实连接检查，开发代理默认连接本机 8080 后端；组合 / 决策表单由 G08 接入。`npm run build` 执行类型检查与构建，`npm test` 验证 API 客户端和异步状态。配置、代理验收及大陆网络准备见 [G07 前端说明](docs/g07-frontend.md)。
