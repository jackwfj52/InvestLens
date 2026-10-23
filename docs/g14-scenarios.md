# G14 调仓、直接价格冲击与约束评估

新增 `POST /api/scenarios` 和 `GET /api/scenarios/{id}`。计算使用用户已确认的固定 DecisionVersion、原 PortfolioSnapshot 和 G11 CalculationSnapshot；读取动作、资金来源及阈值时不使用最新版本，不接受请求覆盖。没有行情、HTTP 或模型调用。

## 请求与结果

请求示例（ID 替换为实际保存值）：

```json
{
  "schemaVersion": "1.0.0",
  "decisionId": "decision_...",
  "decisionVersion": 1,
  "calculationSnapshotId": "calculation_snapshot_...",
  "portfolioSnapshotId": "portfolio_snapshot_...",
  "shocks": [
    {"instrumentId": "ETF:SYNTHETIC:DEMO_GOLD:USD", "shock": "-0.2"},
    {"instrumentId": "ETF:SYNTHETIC:DEMO_SILVER:USD", "shock": "-0.25"},
    {"instrumentId": "CASH:USD", "shock": "0"}
  ]
}
```

创建返回 201、Location 和独立 ScenarioResult。包含固定 decisionRef/portfolioRef/calculationSnapshotId、inputHash、engineVersion、dataMode、原动作/阈值、前后权重、排序后的显式冲击、逐资产金额损益、集中度、7 项带引用和哈希的 Metric、规则及假设。未知/其他主体记录返回 404，快照/币种/模式绑定不匹配为 409，未确认输入为 409。Run 与研究快照绑定字段仍为空，后续 G16—G27 再绑定；不伪造 ResearchSnapshot。

纯情景只依赖已保存的用户组合，不依赖历史价格。可使用 `marketInputs:[]` 创建的组合计算快照；历史行情不可重放不妨碍用户组合的直接冲击算术，也不因此产生可用历史指标或真实行情资格。

## 算术与校验

- TARGET_WEIGHT 计算 `delta = target - original`；DELTA_PERCENTAGE_POINTS 计算 `delta = points / 100`。目标必须是本组合非现金资产，资金来源必须是本组合中不同的、登记为 CASH 的资产。只支持正增配、同币种、无杠杆及现金内部调仓。
- 从现金精确扣减 delta，其他资产权重不变；拒绝现金不足、负权重、总和不等于 1、未知资产、目标不增加或不在 0—1 范围。百分点换算若超出权重 10 位精度则拒绝，不静默舍入。
- 每个持仓（包括现金和零权重持仓）必须显式提供一个冲击，范围 -1 到 1。拒绝遗漏、重复、未知资产、数字类型、非规范/超精度小数；现金冲击由用户提供，不默认补零。
- `return = sum(weight * shock)`，`pnl = totalValue * return`；逐资产金额同样由原总值计算。先精确汇总，再按 10 位小数 HALF_UP 输出。NORMALIZED 总值 100 的金额单位为 BASE_CURRENCY_UNIT，ABSOLUTE 为 CURRENCY，并保留 USD。
- 集中度 weight-concentration-v1 明确 includesCash=true：输出最大持仓权重与 HHI（sum(weight²)）的前后值，均为 RATIO，与同一 inputHash 关联。

七项 Metric 为 WEIGHT_CHANGE、TARGET_WEIGHT_AFTER、SCENARIO_RETURN_BEFORE/AFTER、SCENARIO_PNL_BEFORE/AFTER、SCENARIO_RETURN_CHANGE。权重变化与收益变化使用 PERCENTAGE_POINT；其余比例为 RATIO。情景指标 sampleWindow=null、sampleCount=0、年化/样本参数为空；不冒充历史统计。

MAX_TARGET_WEIGHT 比较调仓后目标权重，MAX_SCENARIO_LOSS 比较 `max(0,-returnAfter)`，均使用原 DecisionVersion 的 RATIO 阈值；等于阈值为 PASS，超过为 FAIL。规则失败仍是有效算术结果，不能解释为流程失败或宏观论点成立。

## 输入与保存

哈希使用 G01 规范封装，覆盖原组合 contentHash、原始动作（区分目标权重和百分点方式）、按 instrumentId 排序的全部冲击，以及 parameters 中的 dataMode、valuationBasis、costsIncluded=false、用户阈值；marketInputs=[]，因为此公式不使用历史价格。重排冲击不改变哈希，改冲击/动作/组合/阈值会改变哈希；用户输入时间和随机结果 ID 不参与数值输入哈希。

组合、动作、阈值和冲击保存为真实 USER_INPUT Evidence 及其获准 payload；所有指标引用这条证据，不伪造市场数据。证据 payloadHash 与每项 metricHashes 都可独立重算。V7 建立 scenario_results，外键关联计算快照、组合快照、决策版本和输入证据；证据、payload、完整结果同事务保存，插入失败全部回滚。重算新增 scenarioId，保留旧快照和结果。

## 验收

25 项真实 MySQL/HTTP 集成通过。20/10/70→30/10/60、黄金 -20%/白银 -25%/现金 0% 时，收益为 -0.065/-0.085，归一化金额 -6.5/-8.5，变化 -2 个百分点；总值 10000 时金额 -650/-850。覆盖目标权重/百分点两种输入、等阈值、超限、正收益、现金冲击、±100% 冲击、现金恰好用尽、非法权重/币种/资金/资产、遗漏/重复冲击、旧版本冻结、主体隔离、HTTP 201/400/404/409、输入顺序哈希、旧结果不变和写入失败回滚。测试行情 Provider 零调用，代码没有模型依赖。

2026-10-23 联合回归共 249 项后端测试通过（原有 216、G13 集成 8、G14 集成 25），V6/V7 在真实 MySQL 应用并通过重复启动校验；补齐最终响应单位/引用后，G14 的 25 项再次通过。G01 Schema/贯穿样例/24 项拒绝场景及哈希不变量通过。

运行集成测试后，`python scripts/check-calculation-contracts.py` 使用已忽略的 target 合成样例导出，独立验证 10 项输出 Metric 的 G01 Schema、内容哈希与引用，并重算 G14 的完整 inputHash、权重守恒和 -6.5%/-8.5% 算术；需要已有 jsonschema 依赖，环境参见 [契约验收说明](contracts/acceptance.md)。本次检查通过，未使用真实价格、没有调用模型，正式行情资格仍待确认，尚不宣称 G15/M2 完成。
