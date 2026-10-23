# G11 证据与计算输入快照

`EvidenceService.freeze(portfolioSnapshotId, queries, dataMode)` 是内部确定性入口，固定已有组合快照、版本和内容哈希，通过已登记标的与 G10 质量门槛取得市场输入。调用方不能传价格、质量、许可、主体或 URL。每资产最多一个窗口、最多三资产，必须属于组合、同币种、模式匹配。未取得正式资格的候选只记录质量缺口，不请求外部行情。

本组使用独立 `calculation_snapshot_*`，没有 Run、研究 Manifest 或 MetricBundle；它们分别在 G16—G18、G27、G13 接入。前置证据的 `runId` / `displayId` 为空，不作为 G01 已绑定 Run 的发布 Evidence。公开入口只提供读取；后续计算业务通过内部入口冻结输入。

## 保存策略

策略取自服务端 `MarketInstrumentCatalog`，未知值保守按 NO_STORE 处理。

| 策略 | 落库内容 | 可完整重放 |
|---|---|---|
| PAYLOAD_ALLOWED 且质量通过 | 来源公共 origin、Instrument 元数据、时间、质量、规范日 K、payloadHash | 是 |
| PAYLOAD_ALLOWED 但质量不通过 | 质量和逐项原因、缺失交易日，payloadRef/payloadHash 为空 | 否 |
| METADATA_ONLY | 来源公共 origin、时间、质量和许可；无价格、价格摘要或价格哈希 | 否 |
| NO_STORE / 未知 | 只在计算快照保留不带来源内容的 NO_STORE 缺口，不创建 Evidence、不抓取 | 否 |

只保存 G10 通过的标准化输入，不保存上游原始响应。URL 只保留 http(s) origin，删除用户信息、路径、查询和 fragment，不能借来源字段保存凭据。`modelInputAllowed` 仅在许可与质量都通过时为 true，本组不调用模型。

不能完整重放时 `replayable=false`、`inputHash=null`，不为没有保留的价格生成哈希。METADATA_ONLY 也不把价格派生摘要当成许可豁免。NO_STORE 的缺口不含资产、源 URL、时间或内容哈希。用户自己已保存的组合引用仍可保留。

## 哈希与不可变输入

计算输入采用 G01 `investlens-canonical-json-v1`，内容为 `{schemaVersion, engineVersion, portfolioContentHash, action:null, shocks:[], marketInputs, parameters}`。市场输入按 instrumentId 排序，包含 Instrument、质量策略版本、价格口径、日历/时区、区间、asOf 和已排序、去重、规范小数化的日 K；parameters 固定 dataMode、valuationBasis、costsIncluded=false。

排除随机记录 ID、抓取时间、创建时间和重复 K 计数。相同组合内容与规范市场输入产生相同哈希；修改价格、区间、模式、元数据或组合内容会改变哈希。独立市场 payloadHash 覆盖完整 MarketContent。`engineVersion=financial-engine-v1`；本组 action/shocks 尚未提供，后续情景入口必须将实际输入加入同一封装。

V4 建立 `evidence` / `evidence_payloads` / `calculation_snapshots`，证据、获准 payload 与快照同事务保存；V5 在已应用 V4 上增加复合外键，数据库禁止把价格 payload 挂到 METADATA_ONLY 证据，保留 V4 原校验和。组合快照有真实外键。服务不提供更新快照或证据的方法。

重读只查询数据库中的原组合版本和原 payload，不重新请求行情、不按当前墙上时间改变原质量；同时验证 payload 与完整 inputHash。组合修订和 Provider 后续返回变化不会改变已保存输入。查询按服务端 owner 隔离。

## 读取接口

均使用已有 API envelope / traceId：

- `GET /api/evidence/{evidenceId}`：独立证据元数据。
- `GET /api/calculation-snapshots/{snapshotId}`：组合引用、inputHash、replayable、marketInputs、gaps。
- `GET /api/calculation-snapshots/{snapshotId}/inputs`：原快照、原组合及规范日 K；不可重放返回 422 DATA_UNUSABLE，内容哈希不一致返回 409 SNAPSHOT_MISMATCH。

未知或其他主体记录返回 404，不提供公开上传价格、覆盖许可或任意 URL 的入口。

## 验收

使用现有 JDK 17、真实 MySQL 8.4.13 / Flyway 与 Redis 开发依赖：

```powershell
.\scripts\dev.ps1 test
# 或在 backend/ 中只验收本组
.\mvnw.cmd -B -ntp '-Dtest=EvidenceIntegrationTests' test
```

全部价格为显式 SYNTHETIC 固定样本，生产目录与资格未改动。覆盖重复输入/乱序/相同 K/规范小数和抓取时间不变哈希、价格/组合变化、原组合版本冻结、独立服务重读、三个保存策略与未知策略、数据库禁存约束、缺失与过期、模型输入禁用、真实 HTTP 200/404/422、跨主体隔离、篡改校验与事务回滚。验收清理本次 UUID 数据，保留迁移和已有数据。

正式行情资格、大陆原生网络、使用条件与公司行动仍待确认；本组完成基础能力，不宣称 M2 或真实研究已完成。
