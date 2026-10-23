# G10 行情标准化与质量门槛

`MarketQualityService` 对 Provider 领域结果做确定性检查，输出 `market-quality-v1`、质量、允许计算标志、收益口径、asOf、收到/接受/未闭合/重复数量、缺失交易日和逐项原因。只有无阻断项时才返回 `calculationBars`；失败时为空，`MarketHistoryTool` 返回 422 DATA_UNUSABLE 及结构化原因。标的元数据和日期窗口必须与请求完全匹配，不能混入另一资产或口径。

处理顺序为窗口与元数据检查、核心交易日识别、按观察时间过滤未闭合 K、正价格和 OHLC 校验、精确小数标准化、日期排序与去重、真实缺失检查、复权/公司行动检查。相同日期数值相同的 K 去重并留提示；冲突 K 整日剔除，禁止选第一条或最后一条。缺失不填零，无效数据不插值。

日历 `US_EQUITIES_CORE / us-core-2026-v1` 只覆盖 2026 年 NASDAQ / NYSE Arca 核心时段。官方清单的 10 个休市日和两次 13:00 提前收市已固定；常规 16:00 收市，America/New_York 自动处理夏令时。收市后额外等待 15 分钟是本程序的保守终结门槛，不宣称上游刷新 SLA。其他年份/日历/时区不能回退成“周一至周五”。临时停市仍须维护日历并升版本。

expected session 按当前检查时间确定；K 是否闭合同时受 retrievedAt 与检查时间约束。旧缓存中的盘中 K 不会随着墙上时间流逝被升级为收盘 K。休市/周末不计缺失；已结束且缺失的交易日明确列出。asOf 来自最后一个有效已闭合 K 的实际核心收市时刻，retrievedAt 保留原值。

复权未验证或 UNKNOWN、公司行动覆盖未完整核实，均禁止收益计算。History 的 corporateActionsVerified 表示其整个 start/end 窗口的完整检查，corporateActions 枚举该窗口已知事件；未复权序列跨拆股即使事件自称 handled 也被阻断，只有已验证复权且事件已处理才能通过。价格收益始终 PRICE_ONLY；分红记录为 DIVIDENDS_EXCLUDED，不冒充总收益。TOTAL_RETURN 只接受已验证 TOTAL_RETURN_ADJUSTED 且相关行动处理完整的输入。至少两根合格价格才形成最小收益输入；波动率等更高样本门槛交由后续 Quant 实现。

查询入口（启动本地后端后）：

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/api/market/quality-policy'
Invoke-RestMethod 'http://127.0.0.1:8080/api/market/quality?instrumentId=ETF:SINA:NYSE_ARCA:GLD:USD&start=2026-06-25&end=2026-10-22'
```

质量查询对未获准候选返回 SOURCE_NOT_QUALIFIED / MAINLAND_ACCESS_UNVERIFIED / LICENSE_BLOCKED / ADJUSTMENT_UNKNOWN，不发外部请求、不推断价格缺失、也不返回价格。符合资格时才调用 Provider 并返回实际质量报告。策略端点公开日历版本、来源与终结等待时间。

2026-10-23 验收：26 项质量测试、12 项 Provider 测试、4 项真实 HTTP 行情集成通过；固定合成价格覆盖排序/相同与冲突重复 K、未闭合与旧观察、真缺失、法定休市、周末、零/负/非有限/超精度价格、OHLC、拆股、分红、未知复权、公司行动覆盖、DST、提前收市与不支持年份。合格合成样本明确为 PRICE_ONLY，阻断样本的计算序列为空。

显式在线质量检查通过 Java→Python→新浪重取三资产：每项有效日线 84 根、缺交易日为空、asOf 均为 `2026-10-22T20:00:00.000Z`，但由于使用条件 UNKNOWN、复权 UNKNOWN、公司行动未验证，均 calculationAllowed=false。仅保留 [无价格质量摘要](probes/2026-10-23-g10-sina-quality.json)。重跑用 [G09 在线命令](../scripts/market/README.md) 加 `-Dmarket.quality=true`，常规测试不联网。

日历依据：[NYSE 交易时间与休市](https://www.nyse.com/trade/hours-calendars)、[Nasdaq 2026 日历与交易时段](https://www.nasdaq.com/market-activity/stock-market-holiday-schedule)，核对日期 2026-10-23。此组没有添加行情存储、报告或已完成 Run，G11 再建立许可约束下的证据与计算快照。

组末回归：后端 186 项全部通过、前端 22 项全部通过，前端类型检查与生产构建通过。数据库仍为 V1—V3，无空占位迁移；无真实行情价格持久化。
