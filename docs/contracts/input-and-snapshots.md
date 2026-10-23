# 输入、版本与快照

共同单位、精度与版本见 [README](README.md)；对象的精确必填项及空值规则见 [Schema](schemas/contract.schema.json)。本文件中“业务校验”是 Schema 之外后端必须执行的约束。

## DecisionDraft 与 DecisionVersion

`DecisionDraft` 保存不完整输入：`draftId`、`inputText`、`inputStatus`、`missingFields[]`、可空的 `proposedDecision`。`inputStatus` 为 `NEEDS_INPUT / READY / CONFIRMED`；`missingFields` 逐项包含字段路径、原因和面向用户的提示。资金、期限、约束由用户确认，模型不得代填。结构不合法的请求返回 400；字段尚未补齐的合法草稿可以保存，且没有 Run。

`DecisionVersion` 字段：`decisionId`、`version`、`inputText`、`inputStatus`、`confirmedAt`、`baseCurrency`、`portfolioSnapshotId`、`action`、`horizon`、`constraints`、`hypotheses[]`、`missingFields[]`、`createdAt`。NEEDS_INPUT 版本允许未填写的业务字段为 null，并记录非空 missingFields；READY/CONFIRMED 时业务字段齐全、hypotheses 非空、missingFields 为空。草稿 proposedDecision 仅包含已填写的业务字段，不包含持久化 ID、版本和时间；保存时由后端分配。

- `action.type = REALLOCATE`，`instrumentId` 是增配目标，`fundingInstrumentId` 是减少的资金资产；P0 仅实现现金资金来源，不支持融资、外部入金或杠杆。变更方式二选一：`TARGET_WEIGHT + targetWeight` 或 `DELTA_PERCENTAGE_POINTS + deltaPercentagePoints`。不同目标方式规范化到同一调仓结果，但作为不同用户请求保留。
- `horizon` 为 `value`（正整数）和 `unit = DAY / MONTH / YEAR`，代表用户研究期限，与行情窗口分别记录。
- `constraints` 至少要求用户明确 `maxTargetWeight`、`maxScenarioLoss`（均为非负 RATIO，范围 0—1）。这是首版表单的两条必填研究约束；不推断个人风险承受能力，不推断配置金额。组合可使用归一化总值。
- `inputStatus = CONFIRMED` 才可创建 Run，`confirmedAt` 必须非空；READY/NEEDS_INPUT 的确认时间为空。READY 表示字段齐全待用户确认，NEEDS_INPUT 表示 `missingFields` 非空。
- 每次修订（包括确认）生成新版本；请求携带 `expectedVersion`，只在当前版本匹配时创建 `version + 1`，冲突返回 409。已经创建的版本不可覆盖；旧版本和被引用组合快照必须可读取。编辑后需重新确认，不沿用原确认时间。
- Run 创建事务核对已确认版本、组合归属、币种和快照 ID，将版本和快照引用固定。后续编辑 Decision/Portfolio 不影响这个 Run。

`Hypothesis` 保存 `hypothesisId`、`statement`、`mechanism`、`testMethod` 和 `supportStatus`。输入版本中的状态固定为 `UNVERIFIED`，研究后的逐条评价放在报告中，禁止回写旧输入。评价状态为 `UNVERIFIED / SUPPORTED / MIXED / NOT_SUPPORTED / INSUFFICIENT_EVIDENCE`，并带当前 Run 内的 `evidenceRefs` / `metricRefs` 和原因。没有获准宏观证据时允许 UNVERIFIED；组合规则通过不能升级宏观支持程度。

## Instrument

资产 ID 必须区别同名代码：由 `instrumentType`、交易所/来源、symbol、quoteCurrency 构成，服务端注册后作为不透明 ID 使用。字段包含 `instrumentId`、`instrumentType`、`symbol`、`exchange`、`provider`、`quoteCurrency`、`priceUnit`、`exchangeTimezone`、`calendarId`、`calendarVersion`、`proxyFor`、`adjustment`、`adjustmentDescription`。

| 类型 | 报价单位与必要语义 | P0 |
|---|---|---|
| STOCK | `CURRENCY_PER_SHARE`，股票，`proxyFor = null` | 仅获准白名单 |
| ETF | `CURRENCY_PER_SHARE`，黄金/白银 ETF 分别 `proxyFor = GOLD / SILVER` | 是，明确代理资产 |
| CASH | `CURRENCY`，provider/exchange/timezone/calendar/adjustment 为空，`proxyFor = null` | USD 现金 |
| PRECIOUS_METAL_SPOT | `CURRENCY_PER_TROY_OUNCE`，独立市场元数据 | P1，仅预留类型，P0 拒绝执行 |

非现金的来源、交易所、时区、日历、复权说明不得为空。`adjustment = UNADJUSTED / SPLIT_ADJUSTED / TOTAL_RETURN_ADJUSTED / UNKNOWN`；复权到底含拆股或分红由获准源确认，UNKNOWN 不允许进入受影响的收益计算。未复权序列只能称价格收益，拆股未处理则禁算；不因名字含黄金就套用现货口径。元数据变化需新版本/新内容哈希，不能改变已冻结研究内容。

## PortfolioSnapshot 与 Scenario

`PortfolioSnapshot`：`snapshotId`、`portfolioId`、`version`、`baseCurrency`、`valuationBasis`、`totalValue`、`asOf`、`dataMode`、`positions[]`、`contentHash`。`positions` 为唯一 `instrumentId`、同币种 `quoteCurrency` 和 `weight`。P0 非负、无杠杆、精确合计 `"1"`，不能以容差吞掉用户录入差错；最多 3 个非现金标的，现金不占标的额度。

`valuationBasis = NORMALIZED` 时 `totalValue = "100"`，金额单位为 `BASE_CURRENCY_UNIT`，必须标明归一化示例总值，不能称真实持仓金额。用户填写绝对金额时 `valuationBasis = ABSOLUTE` 且 `totalValue > 0`，单位为 `CURRENCY`。纯权重不需要模型猜测金额。

调仓先从目标差额计算资金扣减：`deltaWeight = targetWeight - originalWeight = deltaPercentagePoints / 100`；其他非目标资产权重不变；现金新权重 = 原现金权重 − deltaWeight。P0 此动作只允许正增配、目标不能是现金、资金来源必须是不同的 CASH 资产；拒绝现金不足、负数、总权重错误、币种不一致、未知标的和非本组合快照。

`Scenario` 固定 `scenarioId`、`runId`、`portfolioSnapshotId`、`researchSnapshotId`、`snapshotHash`、`shocks[]`、`assumptions[]`、`engineVersion`。每个当前持仓的冲击必须显式提供（包括现金），不得漏项默认零；单项 `shock` 为 RATIO，最低 `-1`，上限为首版产品输入限制 `1`（并非市场波动上限）。组合损益 `sum(totalValue × weight × shock)`，组合收益 `sum(weight × shock)`，调仓前后使用相同输入快照和冲击。现金零冲击只是一项用户假设。

## Evidence、ResearchSnapshot 与 Manifest

`Evidence` 保留全局 `evidenceId`、`displayId`（Run 内短编号）、`runId`、`type`、`provider`、脱敏 `sourceUrl`、可空 `instrumentId`、`asOf`、`retrievedAt`、`quality`、`dataMode`、`payloadHash`、`storagePolicy`、`payloadRef`、`modelInputAllowed`。市场口径由研究快照中的 Instrument 元数据及对应行情输入 Manifest 固定。

`type = SOURCE_FACT / USER_INPUT / COMPUTED / INTERPRETATION`；`quality = VALID / STALE / MISSING / CONFLICT / INVALID / LICENSE_BLOCKED`。仅 VALID 且口径/许可通过的数据可作为确定性计算输入。缺失/禁用证据的 `payloadHash`、`payloadRef` 为空，不能被论点当事实引用。

存储策略由后端 ProviderCapabilities 映射：`PAYLOAD_ALLOWED / METADATA_ONLY / NO_STORE`。后两者 `payloadRef = null`，NO_STORE 只允许留下 Run 调用状态和缺口，Evidence 内容不入库；发布时不引用未保存证据。仅在许可允许时保存摘要、hash 和元数据，hash 也不是授权豁免。禁止模型输入时 `modelInputAllowed = false`，不向角色发送该证据内容。

`ResearchSnapshot`：`snapshotId`、`runId`、`decisionRef {decisionId, version}`、`portfolioRef {snapshotId, version, contentHash}`、`dataMode`、`frozenAt`、`manifest`、`snapshotHash`。完成 Research/Quant 后、Bull/Bear 前冻结一次，后续角色只能消费同一个 `snapshotHash`。创建 Run 时已有固定的输入引用，不需要预先伪造尚不存在的研究快照；Run 的 `researchSnapshotId` 初始为空。

`ResearchManifest` 包含 `instruments[]`（资产 ID 与元数据哈希）、`evidence[]`（ID、payloadHash、存储策略）、`marketInputs[]`（evidenceRef、1d、价格口径、日历与时区、区间、样本数、输入哈希）、`metrics[]`（metricId、inputHash、formulaVersion、内容哈希）、`planHash`、`schemaVersion`、`engineVersion`、`qualityPolicyVersion`、`promptVersions {planner,bull,bear,judge}`、`modelConfigVersion`、`replayable`、`gaps[]`。

指标的 `inputHash` 指向计算输入，而不是尚未生成的研究快照哈希。研究快照再将指标结果哈希纳入 Manifest，避免“指标包含快照 hash、快照又包含指标 hash”的循环。MetricBundle 在冻结时绑定 snapshotHash，但每个 metric 的内容哈希不含 Bundle 的绑定字段。保存策略不允许保留全部输入时 `replayable = false`，历史可读不等于可重新抓取当时数据。

## 哈希规范：`investlens-canonical-json-v1`

G11 的 Run 前置实现使用独立 `CalculationSnapshot`：`schemaVersion / snapshotId / hashAlgorithm / engineVersion / portfolioRef / dataMode / createdAt / inputHash / replayable / marketInputs / gaps`。marketInputs 包含 instrumentId、evidenceRef、quality、inputHash、payloadRef；不可保存或不可计算的输入不产生价格哈希。对应独立证据 runId/displayId 暂为空，不满足发布 Evidence 的 Run 绑定条件。读取路由、保存策略及精确哈希封装见 [G11 实现契约](../g11-evidence-snapshots.md)，G27 再形成完整 ResearchSnapshot/Manifest。

1. 固定 Schema 版本，拒绝未知字段、重复 JSON key 和非有限数；金融小数在进入哈希前转换成 README 约定的规范字符串。对象按 key 的 Unicode 码点升序；不执行 Unicode 归一化；JSON 无额外空白，保留非 ASCII，UTF-8 无 BOM，不转义 `/`。字符串的引号和反斜杠分别转义为 `\"` 和 `\\`；控制字符优先使用 `\b/\t/\n/\f/\r`，其他 U+0000—U+001F 用小写 `\u00xx`；拒绝未配对代理码点。整数只用十进制，无浮点 JSON number。
2. 集合先排序：positions/shocks 按 instrumentId；Manifest instruments 按 instrumentId、evidence 按 evidenceId、marketInputs 按 evidenceRef、metrics 按 metricId；gaps 按 code、ref（空在前）。计划步骤、假设、论点和文本数组保持原顺序。
3. 哈希为小写 `sha256:<64 hex>`，版本固定在 `hashAlgorithm = investlens-canonical-json-v1`。
4. 组合 contentHash 只覆盖 `{schemaVersion, hashAlgorithm, baseCurrency, valuationBasis, totalValue, asOf, dataMode, positions}`；排除 snapshotId、portfolioId、version 和 contentHash。相同内容不同 ID 得到同 hash；更改 asOf/模式会改变 hash。
5. 研究 snapshotHash 覆盖除 snapshotHash、snapshotId、frozenAt 外的整个 ResearchSnapshot（包含 runId、固定输入引用和完整 Manifest）。时间和 ID 作为外部封装，市场时间和内容 hash 仍在 Manifest 中；跨 Run 引用仍要业务校验，hash 不能代替归属检查。
6. metric 内容 hash 覆盖完整 Metric；planHash 覆盖完整 Plan；计算输入 inputHash 覆盖 `{schemaVersion, engineVersion, portfolioContentHash, action, shocks, marketInputs, parameters}`。本样例 marketInputs 为空，parameters 显式声明归一化和无成本假设。变更任何计算输入必须生成不同 hash；重算不覆盖旧对象。

本组 [validate.py](validate.py) 给出规范序列化和样例 hash 的可执行参照；后续 Java 实现必须复现同样的 UTF-8 字节和 hash。
