# InvestLens

**AI Investment Decision Intelligence｜AI 投资决策研究 Agent**

先检验证据与风险，再形成自己的投资判断。

## 项目文档

完整设计文档见 `docs/InvestLens-项目设计文档.md`，共 24 章，覆盖产品定位、Agent 工作流、Financial Engine、Evidence 机制、系统与数据设计、Docker 部署、云端演进、36h 开发计划及参赛展示。

当前已具备 Decision / Portfolio 的版本化保存、MySQL / Redis 开发环境、Vue 输入工作台、行情 Provider 与质量门槛。真实行情源资格、研究计算与完整部署仍按开发顺序推进；文档中的后续业务接口和性能目标不表示已完成验收。

开发顺序与已有代码复用建议见 [开发顺序与 WealthFlow 复用计划](docs/开发顺序与WealthFlow复用计划.md)，包含源码复用清单、阶段依赖与验收点。

## 已确定方案

- 核心对象：Decision / Hypothesis / Scenario。
- 后端 Spring Boot，前端 Vue，MySQL + Redis。
- 用户电脑采用 Docker Compose 部署；MVP 使用模块化单体。
- 金融数值由真实数据和确定性引擎生成，模型负责组织研究与解释。
- 默认研究股票、黄金和白银；首版暂以美元计价的美股及贵金属 ETF 为演示口径，现货贵金属另行适配。
- 股票/ETF 行情技术候选为新浪入口 + AKShare 固定解码器，无行情 Key；正式资格与大陆原生网络待核实。Twelve Data 为可选账号路径，FRED 为条件宏观候选，Binance 为条件 Crypto 后备。
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

访问 `http://127.0.0.1:5173/workbench`。工作台包含真实连接检查、合成组合编辑、决策保存与确认、按 ID 重开及版本冲突提示；开发代理默认连接本机 8080 后端。`npm run build` 执行类型检查与构建，`npm test` 验证客户端、异步状态和精确百分比。配置与大陆网络准备见 [G07 前端说明](docs/g07-frontend.md)，表单验收见 [G08 输入工作台](docs/g08-input-workbench.md)。

## 行情与质量（G09—G10）

Java 后端通过受限 Python 适配器取得 AAPL、GLD、SLV 的新浪日线，记录来源、USD/份额、交易日和接收时间。固定资产和 2026 年纽约核心交易日历；质量门槛处理闭合、去重、价格、缺失与公司行动。仅质量合格结果提供可计算序列，不伪造价格或研究成功。

`GET /api/market/instruments` 查询候选资格；`/api/market/quality-policy` 查询日历；`/api/market/quality?instrumentId=ETF:SINA:NYSE_ARCA:GLD:USD&start=2026-06-25&end=2026-10-22` 查询禁算原因。当前使用条件、复权/公司行动及真实大陆网络资格尚未确认，生产白名单为空，普通行情研究入口明确阻断。实现与在线摘要见 [G09](docs/g09-market-provider.md)、[G10](docs/g10-market-quality.md)，适配器运行方式见 [说明](scripts/market/README.md)。

## 证据与计算输入（G11）

证据按服务端许可保存，独立计算快照固定组合版本及规范行情输入，提供稳定哈希与数据库重读。METADATA_ONLY 不保存价格或价格哈希，NO_STORE 只留下缺口；输入无法完整保留时明确不可重放。实现、读取接口和验收见 [G11 证据与快照](docs/g11-evidence-snapshots.md)。

## 金融公式（G12）

纯 Java 计算简单/累计收益、样本标准差年化波动率和峰值最大回撤。中间运算采用 DECIMAL128，缺失、零分母与样本不足返回明确原因；公式和固定样本验收见 [G12 金融公式](docs/g12-financial-formulas.md)。指标接口与业务样本门槛在 G13 接入。

## 指标计算（G13）

通过固定计算快照生成并持久查询收益、年化波动率和最大回撤，保留公式、参数、样本与证据引用。波动率默认需要 30 个收益样本，样本不足明确返回不可计算。接口、配置和验收见 [G13 指标计算](docs/g13-metric-bundles.md)。

## 调仓与直接冲击（G14）

`POST /api/scenarios` 从固定的已确认决策和组合快照计算现金调仓、逐资产损益、集中度及用户阈值结果，`GET /api/scenarios/{id}` 重读原结果。20/10/70→30/10/60 的合成示例损失为 6.5%→8.5%，不调用模型；请求与验收见 [G14 情景计算](docs/g14-scenarios.md)。

## 风险工作台（G15）

工作台已接入指标卡、来源/时间/快照摘要、调仓对比、冲击编辑、防抖与旧响应隔离，并支持按 ID 重读原结果。明确区分缺失、样本不足、错误和 SYNTHETIC；真实行情资格未通过时历史计算禁用，M2 真实行情验收仍待完成。使用和浏览器验收见 [G15 风险工作台](docs/g15-risk-workbench.md)。

## 持久工作流模型（G16）

V8 已建立 Run/Step/Event、状态约束与唯一键，通过事务锁、版本条件更新和事件原子保存保护一致性，并关联固定计算快照与指标。实现与真实数据库验收见 [G16 状态模型](docs/g16-workflow-state.md)。

## 幂等研究任务（G17）

创建接口固定已确认的决策/组合版本，同键同请求复用原任务、异请求返回 409；Run、首步骤、事件与幂等映射同事务保存。工作台保存同一次意图的键并可读取真实任务状态。接口及验收见 [G17](docs/g17-run-creation.md)。
