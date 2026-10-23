# InvestLens 首版业务契约

契约版本：`1.0.0` · 冻结日期：2026-10-23 · 对应任务：G01 / 主提交 01。

本目录将[项目设计文档](../InvestLens-项目设计文档.md)第 3、6—8、11—12、15 章及[开发顺序](../../开发顺序.md)的首版约定落实为字段、规则和可校验样例。这里定义后续实现应遵守的接口，不表示业务 API、数据源或模型已经可用。具体股票/ETF 代码、来源许可、复权语义和市场日历在 G02 探测后登记。

| 文件 | 用途 |
|---|---|
| [输入与快照](input-and-snapshots.md) | DecisionDraft / DecisionVersion / Hypothesis / Instrument / PortfolioSnapshot / ResearchSnapshot / Manifest |
| [状态与 API](workflow-and-api.md) | 输入确认、Run / Step 状态、预算、幂等、错误与查询字段 |
| [研究与报告](research-and-report.md) | Plan / Claim / Verdict / MetricBundle / Scenario / 最低报告与发布规则 |
| [JSON Schema](schemas/contract.schema.json) | JSON Schema Draft 2020-12；通过 `$defs` 引用单个对象；根对象为贯穿样例容器 |
| [20/10/70 样例](examples/gold-silver-cash.json) | 合成输入、调仓、固定快照、部分报告及 Judge 不可用的预期结果 |
| [契约校验](validate.py) | 校验 Schema、样例引用、版本、哈希、算术与代表性拒绝路径 |

## 共同约定

- JSON 字段采用 camelCase；ID 为不透明字符串，版本为从 1 开始的整数。所有业务对象带 `schemaVersion`。未知字段拒绝；新增可选字段也须更新 Schema 与契约版本。破坏兼容的字段/单位/枚举变化升级主版本，既有报告保留原版本并按对应版本读取。
- 金额、价格、权重、收益、冲击和百分点以十进制**字符串**传输，后端金额和权重使用 BigDecimal。禁止指数、NaN、Infinity、浮点金额；规范写法无多余前导/尾随零，零写 `"0"`，不写 `"-0"`。金额/价格最多 18 位整数、10 位小数（对应 DECIMAL(28,10)）；比例最多 10 位小数。超精度输入拒绝，不静默截断。
- 权重/收益/冲击的 `RATIO` 单位中 `"0.2"` 表示 20%；变化字段 `deltaPercentagePoints: "10"` 表示 **10 个百分点**。目标 `targetWeight: "0.3"` 表示 30%。二者不能都传或都缺失；增配必须给资金来源。展示 20%→30%，不得解释成相对增加 10%。
- API 返回计算精度值；展示层统一 HALF_UP：金额 2 位、价格最多 6 位、百分比和百分点 2 位。中间 BigDecimal 运算不为展示提前舍入，必须除法时使用 DECIMAL128；统计可用 double，但落库前检查有限性、按 10 位小数 HALF_UP 转换并记录引擎版本。不可计算用 `value: null` 和原因，禁止用零代替。
- 时间戳是 UTC RFC3339、固定毫秒、后缀 `Z`；交易日/窗口日期使用 `YYYY-MM-DD`，另存交易所 IANA 时区、日历 ID 及版本。抓取时间、市场数据时间、用户快照时间分别记录。
- 执行状态、证据质量、数据整体质量、假设支持程度、数据模式分字段；“研究完成”与“论点获支持”分别判断。
- 样例 ID 中的 `demo`、`SYNTHETIC_*` 均为占位标识，不能加入真实白名单。样例只验证协议与算术，不宣称任何历史行情、行情授权或投资结论。

## 验收与运行

Python 3.10+ 与 `jsonschema` 4.x 可运行（不需要启动后端、数据库、模型或网络）：

```powershell
python docs/contracts/validate.py
git diff --check
```

缺少校验依赖时，在选定的开发环境安装 `python -m pip install "jsonschema>=4,<5"`。校验脚本不修改样例，不访问网络。它覆盖 Schema 和本组跨字段规则；并发、事务、数据源许可、真实行情与完整报告校验需由对应后续任务实现和验收。

G01 验收记录见 [acceptance.md](acceptance.md)。下一组 G02 负责参数化行情/模型探测及能力矩阵；不得把本合成样例当作在线探测通过。
