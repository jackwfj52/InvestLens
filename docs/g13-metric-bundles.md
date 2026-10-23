# G13 指标计算与持久查询

本组把 G11 的固定输入与 G12 的纯公式接到真实 HTTP 和 MySQL。所有价格由已登记 Provider 经质量检查取得，客户端不能上传价格、质量、许可、年化因子或样本门槛。正式行情资格限制继续生效；SYNTHETIC 仅用于显式测试，生产目录没有新增虚构标的。

## 使用接口

1. `POST /api/calculation-snapshots`，请求 `{schemaVersion:"1.0.0", portfolioSnapshotId, marketInputs:[{instrumentId,start,end}]}`。最多三资产、每资产一个窗口，模式取原组合。返回 201 和 Location；`marketInputs:[]` 可固定纯组合输入。行情不可用时保留 G11 的明确缺口与 replayable=false。
2. `POST /api/metric-bundles`，请求 `{schemaVersion:"1.0.0", calculationSnapshotId}`。只重读原保存输入，返回 201、Location 和持久指标结果；不能重放或没有市场输入时返回 422 DATA_UNUSABLE。
3. `GET /api/metric-bundles/{id}` 读取原结果；未知或其他主体记录为 404。
4. `GET /api/metric-policy` 返回 quant-policy-v1、年化因子 252、最少收益样本数及 correlationProvided=false。

公开接口沿用 API envelope、traceId、严格 JSON 和字段校验；拒绝未知字段、数字代替十进制字符串、任意 URL/ownerId 和客户端门槛覆盖。新路由已回写 [API 契约](contracts/workflow-and-api.md)。

## 计算与绑定

独立 Bundle 包含 schemaVersion、bundleId、calculationSnapshotId、sourceInputHash、inputHash、engineVersion、dataMode、createdAt、assets、metrics、metricHashes。runId/researchSnapshotId/snapshotHash 暂为空，G27 再生成发布所需的研究绑定，不冒充 G01 已发布 MetricBundle。

每资产有 PERIOD_RETURN、ANNUALIZED_VOLATILITY、MAX_DRAWDOWN 三项指标。assets 用 instrumentId 和 metricRefs 定位；Metric 严格沿用 G01 字段与单位，携带 formulaVersion、参数、真实样本窗/样本数、质量、不可计算原因和原 Evidence 引用。metricHashes 是每个完整 Metric 的规范 JSON SHA-256。

sourceInputHash 是 G11 原快照的哈希。inputHash 按 G01 封装覆盖原组合内容、规范行情、engineVersion，以及 parameters 中的 dataMode、valuationBasis、costsIncluded=false、annualizationFactor=252、minimumSamples。门槛变更会改变 inputHash；随机 ID 与创建时间不影响它。公式版本分别为 simple-return-v1、sample-volatility-v1、peak-drawdown-v1，引擎 financial-engine-v1。

波动率默认至少 **30 个有效收益样本，即 31 个正价格**。配置 `INVESTLENS_QUANT_MINIMUM_RETURN_SAMPLES` 或 `investlens.quant.minimum-return-samples` 可调整，最小为 2；非法配置直接阻止启动。门槛由服务端固定并写入每份结果，不接受请求覆盖。收益与回撤至少两个价格，其 sampleCount 计价格数；波动率计收益数。

不足门槛的波动率返回 NOT_CALCULABLE、value=null、reason=INSUFFICIENT_SAMPLES、真实样本数，其他可算指标正常保存；HTTP 201 表示结果记录已保存，不表示所有指标都可计算。格式无法容纳的极端结果返回 NOT_CALCULABLE / NUMERIC_RANGE / INVALID，不产生指数、Infinity 或非法精度。相关性、组合历史回测和年化收益尚未提供。

V6 建立 metric_bundles 并外键绑定 calculation_snapshots；重新计算新增记录，不覆盖旧结果。历史查询只读取数据库，即使 Provider 或配置随后变化，原数字、参数与引用仍保留。

## 验收

JDK 17、真实 MySQL 8.4.13/Flyway，运行 `scripts/dev.ps1 test`；单组可在 backend 下运行 `mvnw.cmd -B -ntp -Dtest=MetricIntegrationTests test`。8 项集成用例覆盖三价格不可算波动率、30/31 价格边界、真实 HTTP 创建/读取/Location、同快照重算、配置变化改变哈希、旧结果保留、禁存/空输入拒绝、主体隔离、客户端覆盖拦截和极端数值。

联合回归和独立 Schema/哈希核对结果见 [G14 验收](g14-scenarios.md)。本组未调用模型，不改变源资格，也不以合成历史指标宣称 M2 已完成。
