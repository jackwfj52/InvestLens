# G17：固定输入与幂等创建

`POST /api/decisions/{id}/runs` 要求 `Idempotency-Key` 和契约规定的五项 CreateRunRequest 字段，成功返回 202、Location 和 Run；`GET /api/runs/{id}` 返回本机主体的真实持久状态。时间为 UTC 毫秒 Z，预算包含 deadline、固定上限与已使用次数（G20 已对齐 G01 Schema）。未确认决策、跨快照、版本或模式不符拒绝创建。

V9 的 `(owner_id,idempotency_key)` 唯一键与行锁串行化相同键请求。规范 requestHash 包含主体、决策版本、组合快照/版本与模式；同键同请求复用原任务（含终态），异请求 409。映射不自动过期。固定输入、Run、PLAN Step、RUN_QUEUED 事件和映射在同一事务提交，事件失败时全部回滚。

前端为一次意图保存键与完整固定请求；刷新、网络重试复用键，修改输入或明确重新运行生成新键。状态读取不产生任务。此组不提供事件流、取消或模型执行。

验收：真实 MySQL 并发四请求只生成一组 Run/Step/Event；冲突、终态复用、不同键重跑、错误快照回滚、事件故障回滚、未确认阻断、后续组合修订不影响原引用、HTTP 202/Location/UTC/404/缺键均覆盖。前端两项意图持久化测试及类型检查通过。
