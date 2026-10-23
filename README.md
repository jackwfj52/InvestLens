# InvestLens

**AI Investment Decision Intelligence｜AI 投资决策研究 Agent**

先检验证据与风险，再形成自己的投资判断。

## 项目文档

完整设计文档见 `docs/InvestLens-项目设计文档.md`，共 24 章，覆盖产品定位、Agent 工作流、Financial Engine、Evidence 机制、系统与数据设计、Docker 部署、云端演进、36h 开发计划及参赛展示。

当前已有项目设计文档和 IDEA 生成的 Maven 后端骨架，业务功能尚待实现。文档中的业务接口、性能目标和部署操作是后续开发契约。

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

2026-10-23 已使用 JDK 17 执行以上命令，主代码与测试代码编译通过。应用启动及 `contextLoads` 测试还需后续配置数据库连接、Redis 和数据库迁移；编译检查不代表这些集成验证已经通过。本地凭据、IDE 配置和构建产物不纳入 Git。
