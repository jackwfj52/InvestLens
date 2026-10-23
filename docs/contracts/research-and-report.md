# 研究对象、确定性指标与最低报告

## Plan、Claim 与 Verdict

`Plan`：`planId`、`runId`、`decisionRef`、`hypothesisIds[]`、`dataNeeds[]`、`steps[]`、`promptVersion`。dataNeeds 包含 instrumentId、purpose、window{start,end}、interval=1d；每非现金资产最多一个窗口、总计最多 3 个资产。steps 最多 6 项，每项为 `stepKey`、固定 `tool` 和 `dependsOn[]`；仅允许 `Market.getHistory / Portfolio.getSnapshot / Quant.calculate / Quant.runScenario`。Tool 参数由已确认输入和能力清单生成，不接受任意 URL、SQL、代码、ownerId 或模型任意覆盖的预算。步骤 key 唯一，依赖必须存在且无环；Research 去重取数，宏观工具未启用时记录缺口，不编造调用成功。

`Claim`：`claimId`、`runId`、`snapshotHash`、`role = BULL / BEAR / JUDGE`、`hypothesisId`、`text`、`relation = SUPPORTS / OPPOSES / CONTEXT`、`evidenceRefs[]`、`metricRefs[]`、`conditions[]`、`invalidationConditions[]`、`unknowns[]`。确定性替代不冒充角色输出，用 role=DETERMINISTIC 并在 report.generationMode 中声明。

Claim 的 evidenceRefs 使用当前 Run 全局 ID，界面才映射 E01；metricRefs 使用真实 metricId。外部事实必须有合法证据；计算结论必须引用可计算指标；纯解释可不引用但必须在 unknowns 或 conditions 标明假设。所有引用存在、属于同 Run、在冻结 Manifest 中、口径一致且许可可用；未知 E99、其他 Run 的 ID、不同 snapshotHash 都应拒绝。用户输入数字追到 USER_INPUT，金融数值由后端用引用渲染；模型 text/conditions 等自由文本不得添加未经批准的金融数字，Schema 通过也不能跳过该业务检查。

`Verdict`：`verdict`（SUPPORTED / MIXED / NOT_SUPPORTED / INSUFFICIENT_EVIDENCE）、`thesisAssessment`、`supportingClaimIds[]`、`opposingClaimIds[]`、`unknowns[]`、`invalidationConditions[]`、`nextChecks[]`。报告封装提供 runId/snapshotHash，避免复制不一致；Schema 之外验证所有 claimId 存在、关系与角色含义一致。禁止输出未经校准概率或把模型 confidence 当统计可信度。

## MetricBundle 与 Metric

`MetricBundle` 固定 `bundleId`、`runId`、`researchSnapshotId`、`snapshotHash`、`inputHash`、`engineVersion`、`metrics[]`。冻结前内部计算产物只有 inputHash，冻结后形成发布 Bundle；禁止先编造研究 hash 再反向填指标。

每项 `Metric` 包含 `metricId`、`name`、`status = CALCULABLE / NOT_CALCULABLE`、`value`（十进制字符串或 null）、`unit`、`currency`（无金额为空）、`inputHash`、`formulaVersion`、`parameters`、`sampleWindow`、`sampleCount`、`quality`、`reason`、`evidenceRefs[]`。status 与 value/reason 绑定：CALCULABLE 有值且 reason=null、quality=VALID；NOT_CALCULABLE 值为空且 reason 非空。quality 使用单项证据质量枚举，不能混入 Run 状态。

parameters 固定显式键：annualizationFactor、minimumSamples、valuationBasis、costsIncluded（不适用时 null）。市场指标窗口为 sessionDate 起止和 interval=1d；情景/调仓窗口为 null，sampleCount=0 表示非统计量，不冒充历史样本。

| name / formulaVersion | 单位 | 公式与限制 |
|---|---|---|
| PERIOD_RETURN / simple-return-v1 | RATIO | P末/P首−1；至少 2 个有效同口径正价格 |
| ANNUALIZED_VOLATILITY / sample-volatility-v1 | RATIO | 收益样本标准差（n−1）×sqrt(A)；首版 A=252，至少 30 个有效收益样本（至少 31 个价格） |
| MAX_DRAWDOWN / peak-drawdown-v1 | RATIO | max(1−当前值/此前峰值)，非负损失幅度；至少 2 个有效正价格 |
| WEIGHT_CHANGE / weight-change-v1 | PERCENTAGE_POINT | (目标权重−原权重)×100 |
| TARGET_WEIGHT_AFTER / weight-after-v1 | RATIO | 调仓后目标资产权重；供仓位上限规则引用 |
| SCENARIO_RETURN_BEFORE、SCENARIO_RETURN_AFTER / direct-shock-v1 | RATIO | sum(weight×shock)，依次使用原/新权重 |
| SCENARIO_PNL_BEFORE、SCENARIO_PNL_AFTER / direct-shock-v1 | CURRENCY 或 BASE_CURRENCY_UNIT | 总值×对应组合冲击收益 |
| SCENARIO_RETURN_CHANGE / direct-shock-v1 | PERCENTAGE_POINT | (调仓后收益−调仓前收益)×100，样例为 -2 个百分点 |

P0 不强制相关性、年化收益、组合历史回测；未来相关性门槛为 60 个共同收益样本，不能借示例宣称这些能力已交付。合法休市不算缺失，未闭合日 K 不参与收益，缺失不填零，不跨复权/币种/日历静默拼接。样本不足返回 NOT_CALCULABLE 与真实样本数。

`ScenarioResult`：绑定 scenarioId/runId/researchSnapshotId/snapshotHash/inputHash，包含 beforeWeights、afterWeights、metricRefs、`ruleResults[]`。规则为 ruleId、status=PASS/FAIL/NOT_EVALUATED、threshold、unit、metricRefs、reason。最大目标权重用 RATIO 比较，最大情景损失用 `max(0, -R_p)` 比较，等于阈值为 PASS；权重变化的 PERCENTAGE_POINT 不能拿来比较 RATIO 阈值。规则不通过可以是有效研究结果，不等于执行失败。

## 最低 Report Schema 与终态

`Report` 必填：schemaVersion、reportId、runId、researchSnapshotId、snapshotHash、decisionRef、portfolioRef、status（仅 COMPLETED/PARTIAL）、dataMode、dataQuality、generationMode（MODEL_REVIEW / DETERMINISTIC_FALLBACK）、scope、hypothesisAssessments[]、claims[]、verdict、metricBundle、scenarioResult、evidenceRefs[]、roleResults、gaps[]、limitations[]、versions、validatedAt、publishedAt。

`scope` 明确 `requiredAnalyses[]`、`optionalAnalyses[]`；首版 required 为 REALLOCATION / DIRECT_PRICE_SHOCK，HISTORICAL_RISK 在有获准真实行情的常规研究中也是 required；宏观因果检验为 optional。不能在工具失败后缩减 required 来伪装完成。合成样例 scope 不包含历史指标，必须显示 SYNTHETIC；不代表真实行情里程碑。

`roleResults` 固定 planner/bull/bear/judge，每个值为 AVAILABLE / MISSING / DISABLED；DISABLED 也不是完成。`Gap` 为 `{code, ref, message}`，ref 可空。`versions` 包含 contractVersion、engineVersion、qualityPolicyVersion、promptVersions（planner/bull/bear/judge）、modelConfigVersion，服务端配置版本指向实际模型 ID/参数的脱敏配置，不保存 Key。input/ref/version/hash 在报告、Run、Bundle、ScenarioResult 和 Manifest 之间必须一致。

最低可发布结果必须全部满足：

1. 已确认 DecisionVersion 与不可变 PortfolioSnapshot，Run/研究快照/指标输入一致，持久化至少一项合法 USER_INPUT 证据；所有报告引用来自该快照。
2. 调仓前后权重、资金扣减、直接价格冲击输入、可计算的前后冲击收益/损益及用户阈值规则结果完整。单有一段模型结论或几个不可计算指标不够。
3. 每个用户假设都有评价；缺宏观来源可 UNVERIFIED，明确缺口和后续检查。Bull/Bear/Judge 是否可用如实记录，至少存在明确的 verdict（允许 INSUFFICIENT_EVIDENCE）。
4. 来源/输入时间、模式、口径、单位、公式和版本可追溯；没有授权的原始 payload 不向用户/模型泄露；所有自由文本数值和引用已通过后端校验。
5. 部分结果、限制及角色缺失可见；报告、引用关系、Run 终态与最终事件同一事务发布。validatedAt 只表示后端校验完成，不是模型输出时间。

| 情况 | 终态与发布结果 |
|---|---|
| 声明的 required 研究和四个角色均完成、关键质量与引用/数值校验通过；optional 宏观缺失 | COMPLETED，仍可 verdict=INSUFFICIENT_EVIDENCE、宏观 UNVERIFIED，dataQuality 可 PARTIAL |
| required 数据/角色部分缺失，但上述最低报告全部满足 | PARTIAL，缺失角色/数据列入 gaps 与 limitations |
| Judge 不可用，确定性最低结果完整 | PARTIAL + DETERMINISTIC_FALLBACK + INSUFFICIENT_EVIDENCE；不声称 Judge 已审查 |
| 任一角色失败且无合法调仓/冲击结果，或无法建立有效固定输入 | FAILED，无正式 Report，保存诊断和检查点 |
| 报告引用/数字校验失败 | 先去除违规内容并重新校验；满足最低契约才 PARTIAL，否则 FAILED；不得携带非法候选发布 |
| 用户显式取消 | CANCELLED，不再发布迟到报告，保留已有调用记录与检查点 |

dataQuality 只汇总数据完整性：缺宏观来源可能 PARTIAL；角色超时不会自动降低 dataQuality，但会影响 Run 完整性。SYNTHETIC 数据也可在协议检查上 VALID，必须始终显著标模式；LIVE/CACHE 不与 REPLAY/SYNTHETIC 混合运行。LIVE 允许混用获准实时取数和缓存证据，缓存原时间和每条证据 CACHE 模式保留；全缓存 Run 记 CACHE。

## 贯穿样例的预期值

样例使用归一化总值 100，初始黄金 ETF `0.2`、白银 ETF `0.1`、现金 `0.7`；目标模式 `targetWeight=0.3` 与变化模式 `deltaPercentagePoints=10` 均得到 `0.3/0.1/0.6`。冲击分别 `-0.2/-0.25/0`。

原冲击收益 `-0.065`（损益 `-6.5`），新收益 `-0.085`（损益 `-8.5`），收益变化 `-2` 个百分点、损失增加 2 个归一化计价单位。示例用户最大目标权重 `0.3`、最大情景损失 `0.08`：目标权重规则 PASS，原情景 PASS，新情景 FAIL。不能据此判断宏观理由得到支持。

样例的宏观假设 UNVERIFIED，Judge MISSING，Report 为 PARTIAL/DETERMINISTIC_FALLBACK，verdict=INSUFFICIENT_EVIDENCE；这是有意覆盖降级路径。源数据、行情时间和研究结论均未伪装成真实运行。
