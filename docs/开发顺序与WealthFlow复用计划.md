# InvestLens 开发顺序与 WealthFlow 复用计划

日期：2026-10-23。状态：已有 Maven 后端骨架，Git 已统一至项目根目录；JDK 17 下 `mvnw.cmd -B -ntp test-compile` 已通过。WealthFlow 业务代码尚未迁移，业务与数据库集成测试尚未运行。

本计划基于 InvestLens v1.1 设计文档与本机 `D:/Projects/WealthFlow` 源码静态检查；已按用户要求将默认市场调整为股票与黄金、白银。WealthFlow 保持原样，后续按文件提取到 InvestLens，随实现迁移相关测试，不复制本地数据库、真实配置、构建产物和依赖目录。

## 1. 基线与复用原则

- InvestLens 已有 IDEA 生成的 Maven 后端骨架，路径为 `backend/`；业务功能和部署文件仍待实现。
- 按用户最终生成结果锁定 Java 17、Spring Boot 4.0.9、Maven Wrapper、MyBatis Starter 4.0.1（含测试 Starter）；前端继续 Vue 3 + TypeScript，数据层 MySQL、Redis，部署 Docker Compose。数据库迁移选 Flyway。
- WealthFlow 当前声明 Java 17、Spring Boot 3.5.6、MyBatis、SQLite；前端为 React + Ant Design + ECharts。
- 后端通用代码可以提取，但须适配 WealthFlow 的 Boot 3 与本项目 Boot 4 的依赖和 API 差异，不直接沿用旧 MyBatis 3.0.x Starter；持久层按 MySQL 和新领域模型适配。React 页面不能直接作为 Vue 组件使用，仅复用独立 TypeScript 逻辑、图表配置和交互设计。
- WealthFlow 声明的依赖版本只是参考，不能因复用直接认定其兼容性、安全性或构建已通过；创建骨架时锁定目标版本并验证。
- 每次提取同步迁移必要依赖、错误码及有价值的测试，不整包复制旧业务模块。

## 2. 已检查的复用候选

下表路径相对 `D:/Projects/WealthFlow`。

| 来源 | 处理 | InvestLens 中的用途与适配 |
|---|---|---|
| `src/main/java/org/jack/wealthflow/service/AiModelGateway.java` 与 `service/impl/OpenAiCompatibleGateway.java` | 优先提取改造 | 作为 LlmClient 的传输基础；当前返回文本、固定 temperature、默认请求超时 60 秒。需增加按 Run 剩余预算设超时、结构化请求能力、usage/finish reason 等返回元数据和可识别的限流错误 |
| `src/test/java/org/jack/wealthflow/service/impl/OpenAiCompatibleGatewayTest.java` | 随网关迁移 | 复用本机 HTTP stub、鉴权失败、超时和脱敏测试方式，补充新接口行为；静态检查不代表测试已运行 |
| `src/main/java/org/jack/wealthflow/dto/ApiResponse.java`、`exception/*` | 优先提取改造 | 统一响应、业务异常和错误处理，替换领域错误码；保留 HTTP 202/409 等语义 |
| `frontend/src/services/apiClient.ts` | 提取改造 | 不依赖 React 的 fetch 封装；统一 API 前缀，补请求头合并、Idempotency-Key、AbortSignal 与需要的响应状态处理 |
| `src/main/java/org/jack/wealthflow/agent/AgentInputs.java` | 按函数提取 | 参数白名单、日期和数值校验；按新 DTO 与业务单位重写具体规则 |
| `src/main/java/org/jack/wealthflow/agent/ModelReplyParser.java` | 参考解析结构 | 提取严格 JSON 解析与失败处理方式，新建 Plan/Claim/Verdict Schema；旧快照操作协议不迁入 |
| `src/main/java/org/jack/wealthflow/service/impl/ToolAgentChatService.java` | 提取局部机制 | 复用白名单分发、工具结果隔离、引用 ID 校验及有限修复思路；不沿用六轮聊天循环、写入权限和快照业务流程 |
| `src/main/java/org/jack/wealthflow/service/SnapshotQueryService.java` | 提取纯算法后验证 | 参考 BigDecimal 权重与峰值回撤遍历；原对象是资产金额快照，受资金进出影响，不作为市场收益算法直接迁入。补零分母、连续性与样本校验 |
| `src/main/java/org/jack/wealthflow/service/impl/SnapshotActionExecutionServiceImpl.java` 等草稿执行服务 | 参考事务与状态检查 | 新建 Run/Step 执行器；旧 PendingAction 不能替代持久工作流、租约和研究快照 |
| `frontend/src/features/trends/index.tsx`、`features/dashboard/index.tsx`、`components/*` | 参考视图与图表配置 | Vue 中重建图表、指标卡、空态和错误态，移除 React hooks、Ant Design React 组件及旧资产分类依赖 |
| `frontend/src/utils/amount.ts`、`rate.ts`、`date.ts`、`color.ts` | 选择性提取 | 复用展示工具。原 amount 工具按两位小数处理，且部分无效输入回落为零；股票价格、份额、贵金属报价单位与缺失状态必须重新约定。金融结果统一后端计算 |
| `src/main/java/org/jack/wealthflow/config/DbSchemaMigration.java` 与 SQLite mapper SQL | 不直接迁入 | 存在 SQLite `PRAGMA` 等语法；新项目用 MySQL + Flyway |
| `src/main/java/org/jack/wealthflow/service/WindowsDpapiSecretProtector.java` | 不迁入容器基线 | 绑定 Windows 用户，Linux 容器不能直接使用；P0 凭据由后端环境配置提供 |
| `knowledge/*`、Embedding 配置与 AI 模拟金额服务 | 延后 | P0 不依赖 RAG；市场价格与指标不能来自模型生成的模拟金额 |

## 3. 先冻结的一条业务路径

默认研究股票与黄金、白银。首版暂选美元计价的美股及黄金/白银 ETF，以同市场、同币种的资产完成闭环；具体代码在数据能力探测后列入白名单。市场和币种保留配置能力，不承诺首版同时覆盖 A 股、港股和美股。

黄金、白银 ETF 明确标为代理资产，不能展示成现货价格。现货后续通过独立 instrument 类型接入，记录 XAU/XAG、每金衡盎司或其他实际报价单位、币种、交易时段和来源；期货与杠杆产品不进入 P0。

演示输入：黄金 ETF 20%、白银 ETF 10%、现金 70%，将现金的 10 个百分点转为黄金 ETF；冲击输入为黄金 ETF -20%、白银 ETF -25%、现金 0%。以上只是检验算术的用户设定示例，不代表典型波动或未来预测。股票增配沿用同一流程。

结果必须包含：真实已闭合日 K 的指标、来源与时间、仓位前后变化、情景损失由 6.5% 变为 8.5%、假设逐条评估，以及可重新打开的报告。

区分三类问题：历史描述由行情与引擎回答；风险约束由用户阈值与规则回答；宏观因果论点缺少证据时保持未验证。不能用仓位约束通过替代对宏观投资理由的支持。

## 4. 按依赖推进的开发顺序

以下是单主要开发者的 36h 目标窗口，不是工时承诺。前端从骨架阶段开始，随各接口逐步接入；每个阶段都保留能运行的成果。

| 阶段 / 目标窗口 | 开发内容 | 复用来源 | 阶段验收 |
|---|---|---|---|
| 0 / 0—2h | 固定股票/贵金属 ETF 样例、DTO、报告 Schema、状态转换；优先探测行情源的标的覆盖、复权、日历、额度与使用权限，再探测模型连接 | WealthFlow 现有 API 包装与模型消息结构 | 一份输入和预期输出；可用数据/模型能力清单；确认版本与快照契约。找不到适用数据源时明确阻塞，不自动切回 Crypto |
| 1 / 2—5h | 后端/前端骨架、MySQL 迁移、Redis 配置、Compose 与健康检查；Vue 建工作台框架 | ApiResponse、异常处理、apiClient；按需提前提取模型网关做连接探测 | 容器可启动，前端能访问后端，Decision 与组合可保存；重启保留记录 |
| 2 / 5—9h | 股票/ETF Provider、标的元数据、已完成交易日 K、复权与公司行动校验、标准化、Evidence 与输入快照；配置数据保留策略 | HTTP 和校验代码的通用部分 | 白名单股票及黄金/白银 ETF 能取真实日 K；来源、币种、交易日、复权口径可见；不将休市当作缺失，不跨拆股静默计算 |
| 3 / 9—12h | 收益、样本波动率、最大回撤；MetricBundle 与公式版本 | SnapshotQueryService 中可提取的纯算法思路 | 小样本验证公式，正式接口执行样本门槛；指标能追到输入快照 |
| 4 / 12—15h | 手动组合、调仓前后、直接价格冲击、规则约束评估；接入工作台 | BigDecimal 运算与图表呈现思路 | 无模型也能算出示例 -6.5%/-8.5%；改冲击即时重算，缺失值不显示成零 |
| 5 / 15—20h | Run/Step、持久队列、幂等、预算、取消、检查点与最小恢复；先用状态轮询 | 旧事务与状态校验思路 | 重复提交复用 Run；终态不被迟到结果覆盖；重启识别未完成步骤 |
| 6 / 20—25h | 接通 LlmClient；Plan → 后端白名单工具执行 → 冻结快照 → Bull/Bear → Judge → 校验 | 模型网关、严格解析、工具分发与引用校验局部代码及相关测试 | 四次正常模型调用有边界；未知引用和无来源数字被拦截；宏观理由可保持未验证 |
| 7 / 25—28h | 完成报告、Evidence Board、历史打开和进度展示；接入 SSE | 图表配置、空态/错误态设计 | 点击引用可定位；刷新读到原报告；SSE 断线可轮询。完整断线补读仍属原 P0 验收项，若延期必须标明 |
| 8 / 28—32h | 集成、故障和新环境部署验收，修复阻断问题 | 提取后的网关测试与新故障 stub | 覆盖 429、超时、空数据、伪造引用、取消竞态、Quant 后重启、Redis 失效与持久报告 |
| 9 / 32—36h | 冻结功能、记录实测、录屏、讲稿、回放准备与休息缓冲 | 实际完成的研究记录 | 三分钟可演示；版本与录屏一致；未完成能力明确列出 |

开发依赖主线：契约 → 骨架 → 行情与证据 → 指标 → 组合情景 → 持久工作流 → 多角色研究 → 完整交互 → 验收与演示。

## 5. 需要在阶段 0 写清的契约

1. Decision 修订使用显式版本记录，Run 固定引用已确认版本；后续编辑不改变旧 Run。Instrument 区分股票、ETF、贵金属现货；包含交易所、报价币种、报价单位、时区与交易日历。
2. 定义 ResearchSnapshot/Manifest，关联决策版本、组合快照、行情输入、指标版本与内容哈希。原始数据的保存受来源策略限制。
3. NEEDS_INPUT 归属和转换必须明确；优先在 Run 创建前完成输入确认。Run 内如果允许等待补充，必须定义预算暂停、版本确认和恢复行为。
4. Step 抢占使用租约与执行版本；写结果验证当前执行令牌及 Run 状态，防止取消和恢复后旧结果覆盖。
5. 分开定义执行状态、数据质量、假设支持程度、数据模式。研究完整完成不等于投资理由得到支持。
6. 定义最低报告契约和 PARTIAL 条件；Judge 不可用时仍可交付标识清楚的确定性风险结果，不伪造完整审查结论。
7. apiClient 合并自定义请求头；创建 Run 支持幂等 Key，同 Key 不同请求返回冲突；客户端取消等待不等于取消后台任务。
8. 后端生成关键数值及规则结论，模型引用 metricRef；模型自由文本不得成为未经校验的金融事实来源。

## 6. 时间不足时的取舍

- 行情未通：收缩股票/ETF 白名单，暂停宏观、现货和 RAG 扩展，优先完成一个适用真实来源；不自动改为 Crypto。无可用源时回放/合成只用于明确标识的流程演示。
- 确定性闭环未通：暂停模型角色与装饰性页面，先保证行情、计算、情景、保存与重新打开。
- 模型阶段超时：合并解析与规划，Research 使用固定白名单计划；保持实际步骤可见。
- 页面进度落后：合并为一个工作台和历史侧栏，不新增独立 Portfolio 页面。
- 完整事件补读或自动恢复未通过：可以交付范围缩小的演示版，但必须记录验收未完成，不能宣称原文档全部 P0 已交付。
- 不削减数据来源与时间、数值校验、引用校验、缺失标识及模式标识；不把模拟行情展示成真实行情。

## 7. 里程碑

- M1（阶段 1）：能启动、保存输入、重启保留。
- M2（阶段 4）：能用真实数据完成风险指标与仓位冲击对比，暂不依赖 LLM。
- M3（阶段 6）：加入可追溯、可降级的多角色研究报告。
- M4（阶段 9）：按实测验收范围交付可演示版本，附完成与未完成清单。

首个实施批次为阶段 0—1：创建干净项目骨架，提取响应/异常/API 客户端基础，确定数据模型和迁移脚本，并让 Compose 启动。随后立即进入真实行情链路。
