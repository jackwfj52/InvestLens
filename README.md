# InvestLens

**AI Investment Decision Intelligence｜AI 投资决策研究 Agent**

先检验证据与风险，再形成自己的投资判断。

## 项目文档

完整设计文档见 `docs/InvestLens-项目设计文档.md`，共 24 章，覆盖产品定位、Agent 工作流、Financial Engine、Evidence 机制、系统与数据设计、Docker 部署、云端演进、36h 开发计划及参赛展示。

当前已具备版本化输入、确定性金融引擎、持久 Run 工作流、已校验报告、证据/历史工作台、Redis 降级与完整四容器部署。G38—G40 已补充合成端到端、空卷部署、故障矩阵和演示交付材料；真实行情源资格、在线模型闭环及新物理环境仍待验收，不把合成演示或构建通过等同于全部验收。

开发顺序与已有代码复用建议见 [开发顺序与 WealthFlow 复用计划](docs/开发顺序与WealthFlow复用计划.md)，包含源码复用清单、阶段依赖与验收点。

逐次实施请按 [开发顺序：Git 提交清单](开发顺序.md) 执行，按每组约 90 分钟分为 40 组，包含 34 条主提交与 6 条中间提交的顺序、实现范围、验收要求及提交说明。

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

文档版本：v1.2 · 2026-10-24。

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

后端默认 `127.0.0.1:8080`，健康检查为 `/actuator/health/readiness`。运行 `.\scripts\dev.ps1 test` 执行包含真实 MySQL / Redis 的全部后端测试；`.\scripts\dev.ps1 down` 停止依赖并保留 MySQL 数据卷。固定镜像、IDEA 配置、中国大陆网络准备及迁移规则见 [本地开发说明](docs/local-development.md)，实测结果见 [G04 验收记录](docs/g04-infrastructure-acceptance.md)。完整应用部署使用下方 G37 入口，开发库与部署库独立。

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

## 数据库任务领取（G18）

MySQL 扫描与条件领取提供租约、执行令牌和并发隔离，排队计入 deadline；最小处理器只读取固定输入并声明模型缺口。验收见 [G18](docs/g18-task-claiming.md)。

## 后台调度与检查点（G19）

默认双线程后台执行固定输入核验和确定性集中度计算；输出、Step、事件及下一步骤原子提交。模型处理器缺失会明确失败并保留检查点，无正式报告。配置及异步/中断验收见 [G19](docs/g19-background-checkpoints.md)。

## 任务预算与计量（G20）

固定 120 秒总预算，行情最多 8 次、模型 4 次普通尝试加 1 次修复、全局重试最多 2 次；三资产各一个窗口。持久记录每次尝试并向处理器传递剩余时间，预算不足拒绝新调用，超时提升令牌并明确失败。规则和联合验收见 [G20](docs/g20-call-budgets.md)。

## 恢复、取消与 Planner（G21—G25）

已接入动态传输超时、Retry-After 有界重试、`POST /api/runs/{id}/cancel`、检查点完整性校验与过期步骤恢复，以及带 usage 的兼容模型网关和严格 Planner。模型默认关闭，URL/模型/API Key 由用户配置；启用方式、边界和验收见 [G21—G25](docs/g21-g25-resilience-and-planner.md)。后续研究链路见下一节。

## 研究与报告候选（G26—G30）

已接入 [白名单工具](docs/g26-research-tools.md)、[研究快照冻结](docs/g27-research-freeze.md)、[Bull/Bear 审查](docs/g28-role-reviews.md)、[Judge 与降级候选](docs/g29-report-candidates.md) 及 [结构/引用校验](docs/g30-report-structure.md)。创建 Run 可选指定已保存的 `scenarioId`，固定显式冲击及原计算输入；省略时不会猜测冲击。角色共享冻结 snapshotHash、预算与有限修复。模型默认关闭，用户自行配置 URL 并显式启用后才调用。

G26—G30 验收时仅保存候选；当前已由 G31/G32 数值校验及原子发布接替临时阻断。角色流程使用本机 stub，未宣称正式行情资格或真实数据 M3 完成。

## 报告、历史与可靠进度（G31—G35）

已接入 [数字闸门与后端渲染](docs/g31-report-numbers.md)、[原子发布与分页历史](docs/g32-published-reports.md)、[报告/Evidence Board 工作台](docs/g33-report-workbench.md)、[持久事件 SSE](docs/g34-durable-events.md) 和 [断线/刷新补读](docs/g35-progress-recovery.md)。模型保持默认关闭、URL 由用户配置；确认输入并计算显式情景后可创建确定性 PARTIAL 报告，未知宏观假设仍未验证。

历史和刷新只读取原 Run/报告；SSE 不可用自动轮询并退避重连，不会隐式取消或创建研究。金融展示值来源于冻结后端指标，违规模型自由文本被阻断。报告、结论、引用、步骤与终态事件同事务保存，后端重启后仍可读取。

验收：后端 335 项、前端 35 项、类型/生产构建、契约拒绝及实际报告 Schema 全通过；实际浏览器验证合成报告、引用跳转、后端重启、刷新恢复和正常 SSE 自动完成。真实行情资格与在线模型闭环仍需后续验收。

## 缓存降级与完整部署（G36—G37）

[G36](docs/g36-cache-and-modes.md) 接入可选 Redis、许可/版本绑定缓存、single-flight、持久账户限额和明确数据模式。CACHE 不隐式回源，REPLAY 仅使用许可完整的固定快照；默认禁用行情缓存，正式源资格没有自动开放。

需要 Docker Linux 容器和 Compose；源码构建首次需访问镜像仓库、Maven/npm/PyPI 等，不要求宿主安装 Java/Node/Python。在项目根目录执行（已有 `.env` 时跳过 init）：

```powershell
.\scripts\dev.ps1 init
.\scripts\deploy.ps1 config
.\scripts\deploy.ps1 up
.\scripts\deploy.ps1 status
```

访问 `http://127.0.0.1:8080/workbench`；端口冲突可配置 APP_PORT。仅前端绑定宿主回环端口，MySQL 命名卷保留原输入/报告，`down` 不删除数据。模型保持默认关闭，URL/模型/Key 由用户配置，构建上下文排除凭据与数据库。详见 [G37 部署与验收](docs/g37-deployment.md)。

G36/G37 当轮后端 **343 项**、前端 **36 项**、类型/生产构建和契约检查通过；镜像实构建、四服务健康、Nginx/SSE、实际停 Redis 后继续发布、MySQL/后端重启保留报告均通过。最终收尾结果见下一节。

## 端到端验收与交付（G38—G40）

[G38](docs/g38-research-acceptance.md) 补充合成历史样本、四次本机模型调用、非空引用观点、发布/历史重读、情景重算及取消后拒绝晚到发布。[G39 验收矩阵](docs/acceptance.md) 记录 A01—A14、实际故障及全新空卷部署；后端 **345 项**、前端 **36 项**、脚本 **8 项**、类型/生产构建与契约检查通过。

`python -B scripts/check-clean-deployment.py` 使用已有镜像创建全新独立项目/空卷，验证首次迁移及合成故障恢复，结束只停止临时容器、保留卷，不操作正常部署。它不是新物理机器或无缓存源码构建。

[三分钟演示](docs/demo.md) 固定 SYNTHETIC 输入、版本/镜像、当前原报告及只读回读步骤，已在浏览器演练；[交付清单与限制](docs/delivery-checklist.md) 区分已交付与未验收。模型仍默认关闭，URL 留给用户配置。真实行情、完整在线审查、新目标机器及连续录屏仍待完成，M2/M3/M4 不宣称全部通过。
