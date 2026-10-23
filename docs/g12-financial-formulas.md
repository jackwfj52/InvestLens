# G12 确定性金融公式

`quant/FinancialFormulas` 是无 Spring、数据库、HTTP 或模型依赖的纯 Java 价格算法。输入为按时间排列、同口径的 `List<BigDecimal>`；由调用方先执行 G10 的日历、闭合 K、缺失和复权检查，纯公式不从数组推断市场或交易日。股票/ETF 的业务样本门槛和 MetricBundle 在 G13 接入。

| 方法 / 公式版本 | 输出（RATIO） | 最低底层样本 |
|---|---|---|
| simpleReturn / simple-return-v1 | `(P_to - P_from) / P_from` | 两个正价格 |
| simpleReturns / simple-return-v1 | 相邻价格逐段简单收益 | 两个正价格 |
| cumulativeReturn / simple-return-v1 | `(P_last - P_first) / P_first`，等价复合收益 | 两个正价格 |
| annualizedVolatility / sample-volatility-v1 | 收益样本标准差 `sqrt(sum((r-mean)^2)/(n-1)) * sqrt(A)` | 三个正价格、两个收益样本 |
| maxDrawdown / peak-drawdown-v1 | `max((runningPeak - P) / runningPeak)` | 两个正价格 |

年化因子默认 252，也接受显式正整数；不隐式切换市场。G13 的股票/ETF 业务入口仍必须至少 30 个有效收益样本（31 个价格），本组的三个价格测试仅验证数学。

除法、均值与平方根采用 DECIMAL128；乘加保留精度。计算波动率时直接使用未按输出精度舍入的收益。最终输出最多 10 位小数、HALF_UP、无指数/尾随零、零统一为 `"0"`，不提前按展示百分比舍入。

不可计算时 `Value.value=null` 且 reason 非空；逐段收益失败时 `Returns.values=[]` 且 reason 非空，不返回残缺的部分序列。原因包括 MISSING_PRICE、INVALID_PRICE、INSUFFICIENT_SAMPLES、INVALID_ANNUALIZATION_FACTOR。null、内点缺失、零价、负价均不可跳过或补零；累计收益也检查内点，不只检查首尾。真正恒定序列的波动率与回撤才返回可计算的零。

参考 `D:/Projects/WealthFlow/src/main/java/org/jack/wealthflow/service/SnapshotQueryService.java` 的 BigDecimal 与 running peak 遍历机制，按市场价格重新实现。没有迁入资产金额、资金进出、百分数输出、SQLite 或旧舍入规则，源项目未修改。

## 验收

```powershell
Set-Location backend
.\mvnw.cmd -B -ntp '-Dtest=FinancialFormulasTests' test
```

16 项纯单元测试通过：

- `100→110→99`：期间收益 `0.1/-0.1`、累计 `-0.01`、最大回撤 `0.1`；样本标准差 `0.1414213562`、默认年化波动率 `2.2449944321`。
- 收益 `0/0.1/-0.1` 用 n−1 得样本标准差 `0.1`；A=4 时 `0.2`、A=252 时 `1.5874507866`，与总体标准差区分。
- 非零均值、复合收益、多次峰值、后续恢复、持续下跌、上涨及恒价。
- 零分母、负数、首/中/末缺失、空序列、样本不足和非法年化因子均返回明确原因。
- 正负 HALF_UP、规范零、极端有效价格、重复计算一致、输入不被修改；逐段展示收益舍入为零时，原始精度仍可产生非零年化波动率。

本组不提供公开计算接口、持久指标或真实历史风险结论。引擎输入版本与 G11 的 `financial-engine-v1` 对应，后续公式变化须更新公式/引擎版本并保留旧快照。

2026-10-23 组末联合回归：后端 216 项全部通过（既有 186、G11 集成 14、G12 公式 16），真实 MySQL/Flyway 已迁移至 V5 并在重复启动时校验通过；G01 Schema、贯穿样例、24 项拒绝场景及哈希不变量通过。G11 固定 payload/input 哈希通过 Python 独立序列化重算。未修改前端，未调用真实行情或模型；正式数据资格限制继续保留。
