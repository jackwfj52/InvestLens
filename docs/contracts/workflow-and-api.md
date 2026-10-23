# 输入状态、执行状态与 API

这是后续 G03—G06、G16—G23、G32—G35 的实现契约，当前尚无这些接口。共同类型见 [Schema](schemas/contract.schema.json)。

## 状态分离与转换

| 维度 | 字段 | 值与含义 |
|---|---|---|
| 输入 | inputStatus | NEEDS_INPUT → READY → CONFIRMED；编辑生成新的未确认版本 |
| Run 执行 | status | CREATED / QUEUED / RUNNING / COMPLETED / PARTIAL / FAILED / CANCELLED |
| Run 阶段 | stage | null（尚未开始）或 PLAN / RESEARCH / QUANT / DEBATE / JUDGE / VALIDATE |
| 步骤 | Step.status | PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED |
| 单项证据质量 | Evidence.quality | VALID / STALE / MISSING / CONFLICT / INVALID / LICENSE_BLOCKED |
| 整体数据质量 | dataQuality | VALID / PARTIAL / UNUSABLE，依据声明的数据需求汇总 |
| 假设支持 | supportStatus | UNVERIFIED / SUPPORTED / MIXED / NOT_SUPPORTED / INSUFFICIENT_EVIDENCE |
| Verdict 支持 | verdict | SUPPORTED / MIXED / NOT_SUPPORTED / INSUFFICIENT_EVIDENCE |
| 数据模式 | dataMode | LIVE / CACHE / REPLAY / SYNTHETIC |

`NEEDS_INPUT` **不是 Run 状态**。未确认或缺字段直接拒绝创建 Run，不扣外部预算、不开始 deadline；不设计 Run 内等待输入/暂停预算。合法确认后创建事务持久化固定输入、CREATED→QUEUED、首步骤与事件，对外直接返回 QUEUED。CREATED 可仅为事务内状态。

| 当前 Run 状态 | 允许的下一状态 |
|---|---|
| CREATED | QUEUED |
| QUEUED | RUNNING / CANCELLED / FAILED（排队 deadline 已过或不可恢复配置错误） |
| RUNNING | COMPLETED / PARTIAL / FAILED / CANCELLED |
| COMPLETED / PARTIAL / FAILED / CANCELLED | 无；重复取消或同一结果的幂等读取不产生状态转换 |

恢复时 Run 保持 RUNNING，重新领取过期 Step 并更新执行令牌；禁止把终态重新放回 QUEUED。重试需要新的 Run 或 Step 的有限新 attempt。Step：PENDING→RUNNING/SKIPPED，RUNNING→SUCCEEDED/FAILED；FAILED→PENDING 只允许在预算内重试并先使旧令牌失效；租约过期 RUNNING→PENDING 同样增加执行版本。SUCCEEDED/SKIPPED 不再重写。取消时 PENDING→SKIPPED，RUNNING→FAILED 并记录 RUN_CANCELLED；Run 终态才是取消事实，不虚构 Step.CANCELLED。

## Run、Step 与事件最小字段

`Run`：`runId`、`decisionId`、`decisionVersion`、`portfolioSnapshotId`、`portfolioVersion`、`status`、`stage`、`dataMode`、`dataQuality`（未评估为空）、`researchSnapshotId`（冻结前为空）、`snapshotHash`（同上）、`reportId`（发布前为空）、`budget`、`errorCode`（无错误为空）、`createdAt`、`startedAt`、`finishedAt`。终态必须有 finishedAt；COMPLETED/PARTIAL 必须与已验证报告原子保存；FAILED/CANCELLED 保留诊断及合法检查点，不冒充正式报告。

`Step`：`stepId`、`runId`、`stepKey`（PLAN/RESEARCH/QUANT/BULL/BEAR/JUDGE/VALIDATE）、`status`、`attempt`（尚未执行为 0）、`inputHash`、`outputRef`、`leaseUntil`、`executionVersion`、`errorCode`。唯一 `(runId, stepKey)`；每次领取令牌递增，结果写入同时核对 Run RUNNING、当前租约与 executionVersion。输出、SUCCEEDED 和事件同事务；外部请求不占长事务。

`RunEvent`：`eventId = runId:sequence`、`runId`、`sequence`（从 1 递增、每 Run 唯一）、`type`（RUN_QUEUED / STEP_STARTED / STEP_SUCCEEDED / STEP_FAILED / RUN_TERMINATED）、`stage`、`status`、`createdAt`、脱敏 `message`。事件消费已提交事实，断线不取消后台任务。缓存、事件推送不可替代 MySQL 事实源。

`budget` 初始上限：deadline = createdAt + 120 秒（排队计入）；单次行情最多 8 秒、单次模型最多 30 秒且不能超过剩余 Run 时间；最多 3 个非现金资产、每资产一个窗口、8 次外部数据尝试；4 次正常模型调用 + 全局最多 1 次 Schema 修复。模型重试同样占最多 5 次模型尝试，总重试最多 2 次，不是每层再加 2 次。attempt、超时、修复、重试和剩余计数持久化，重启不重置；401/403 不重试，429 尊重 Retry-After，预算不足不再调用。具体计量实现留给 G20/G21。

## HTTP 与公共响应

`ApiResponse` 固定 `{schemaVersion, code, message, data, errors, traceId, timestamp}`。成功 `code = OK`、`errors = []`；错误 `data = null`、`code` 为下表业务错误码。`errors[]` 为 `{field, code, message}`，字段错误采用 JSON 路径（如 `positions[0].weight`），不回显 Key、原始上游 body 或敏感配置。HTTP 状态仍表达 201/202/400/409 等语义。

| 方法与路径 | 请求 / 响应 data | HTTP |
|---|---|---|
| POST /api/decisions | DecisionDraft（客户端 draftId 可预生成）；返回新 DecisionVersion，CONFIRMED 时服务端记录 confirmedAt | 201；缺失输入允许保存为 NEEDS_INPUT |
| GET /api/decisions/{id}?version=N | 指定版本 DecisionVersion；无 version 返回当前 | 200 / 404 |
| PATCH /api/decisions/{id} | `{schemaVersion, expectedVersion, draft, confirm}`；confirm=true 须无 missingFields | 200（新版本）/ 409 |
| GET /api/decisions?cursor=…&limit=20 | `{items:[DecisionSummary], nextCursor}`，limit 1—100 | 200 |
| POST /api/portfolios | `{schemaVersion, name, baseCurrency, valuationBasis, totalValue, asOf, dataMode, positions}` | 201，返回 PortfolioSnapshot |
| PATCH /api/portfolios/{id} | `{schemaVersion, expectedVersion, positions, asOf}`，产生新快照 | 200 / 409 |
| GET /api/portfolios/{id}?version=N | 固定版本 PortfolioSnapshot；无 version 返回当前 | 200 / 404 |
| POST /api/decisions/{id}/runs | `CreateRunRequest` + `Idempotency-Key`；返回 Run、Location: /api/runs/{runId} | 202（新建或复用，复用终态也返回 202） |
| GET /api/runs/{id} | Run（含固定输入、budget、真实状态/阶段） | 200 / 404 |
| POST /api/runs/{id}/cancel | 空 body；返回 Run。重复取消返回原 Run，已完成/失败时返回既有终态 | 200 / 404 |
| GET /api/runs/{id}/report | Report；进行中 data 为 Run + `code = RUN_IN_PROGRESS` | 200 / 202；FAILED/CANCELLED 无报告 409 REPORT_UNAVAILABLE |
| GET /api/runs/{id}/evidence | `{items:[Evidence], gaps:[Gap]}`，按存储许可过滤 | 200 |
| POST /api/runs/{id}/scenarios | Scenario（服务端核对 Run 和快照）；返回 ScenarioResult | 200 / 409 |
| GET /api/runs/{id}/events?afterSequence=N | Accept: application/json 时 `{items:[RunEvent], nextSequence}`；text/event-stream 时 SSE | 200 |

`DecisionSummary` 包含 decisionId、version、inputText 摘要、inputStatus、updatedAt、latestRunId（可空）；游标按 `(createdAt, decisionId)` 稳定倒序。查询不创建 Run。

SSE `id` 使用 eventId，JSON 补读使用 sequence；原生 EventSource 刷新后可用 afterSequence。Last-Event-ID 存在则优先使用它并核对 runId；错误游标返回 400。补读严格 `sequence > cursor`，客户端按 runId/sequence 去重。事件流终态后结束；断线与 AbortSignal 只停止等待，显式取消走 cancel API。

## 幂等、版本与错误

`CreateRunRequest` 必填 `schemaVersion`、`decisionVersion`、`portfolioSnapshotId`、`portfolioVersion`、`dataMode`。版本和快照必须与已确认 DecisionVersion 一致，不能偷偷使用最新组合。主体从后端本机主体/认证上下文获取，不接受客户端 ownerId。

Idempotency-Key 为 1—128 个 ASCII 字母、数字、`-_.:`；作用域 `(ownerId, key)`。requestHash 采用输入快照文档的规范 JSON，覆盖 `{schemaVersion, ownerId, decisionId, decisionVersion, portfolioSnapshotId, portfolioVersion, dataMode}`。无请求默认值歧义；相同 key + 相同 hash 返回原 Run；相同 key + 不同 hash 返回 409；不同 key 可以明确重跑同一版本。并发由数据库唯一约束解决，建 Run、幂等记录、Step 和事件同事务，不能先发 202 再落库。

首版幂等映射随 Run 历史保留，不自动过期/删除；后续引入过期策略须保证活动 Run 映射不失效并升级契约。请求取消/刷新/网络重试复用同一 key；用户明确重跑才生成新 key。

| HTTP | code | 条件 |
|---|---|---|
| 400 | INVALID_REQUEST / VALIDATION_ERROR | JSON/未知字段/单位/精度/格式错误 |
| 400 | INVALID_WEIGHTS / CURRENCY_MISMATCH / INSUFFICIENT_FUNDING | 组合或调仓约束错误 |
| 400 | UNSUPPORTED_INSTRUMENT | 不在获准白名单或 P0 不支持 |
| 404 | RESOURCE_NOT_FOUND | 决策版本、快照、Run 不存在或不属于本机主体 |
| 409 | VERSION_CONFLICT / SNAPSHOT_MISMATCH | 修订版本过期或输入/指标/快照不匹配 |
| 409 | NEEDS_INPUT / INPUT_NOT_CONFIRMED | 创建 Run 前缺字段/未确认，返回缺失字段 |
| 409 | IDEMPOTENCY_CONFLICT / INVALID_STATE | Key 对应其他请求或非法状态转换 |
| 409 | REPORT_UNAVAILABLE | FAILED/CANCELLED 无可发布报告 |
| 422 | LICENSE_BLOCKED / DATA_UNUSABLE | 调用前许可受限或无可用计算输入 |
| 429 | BUDGET_EXHAUSTED / RATE_LIMITED | 本地调用额度或账户额度不足；适用时 Retry-After |
| 502 | UPSTREAM_ERROR / MODEL_OUTPUT_INVALID | 同步上游失败或不合法输出 |
| 503 | PROVIDER_NOT_CONFIGURED / PERSISTENCE_UNAVAILABLE | 配置缺失或数据库不可用 |
| 504 | UPSTREAM_TIMEOUT | 同步依赖超时 |
| 500 | INTERNAL_ERROR | 脱敏内部错误 |

已返回 202 的 Run 内失败体现在 `Run.errorCode`、Step、Gap 和终态，轮询 HTTP 仍为 200；不得用后续 HTTP 500 混淆已创建任务。内部报告校验错误如 UNKNOWN_REFERENCE、CROSS_RUN_REFERENCE、UNSOURCED_NUMBER、UNIT_MISMATCH 作为 Gap/Step.errorCode 保存，移除不合格内容后重新验收最低报告，不能直接发布错误候选。
