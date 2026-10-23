# G36：可选 Redis、持久预算与明确数据模式

Redis 只保存获准缓存，MySQL 始终保存任务、报告、检查点和账户调用限额。readiness 依赖 MySQL，不再被可选 Redis 阻断；`GET /api/system/resilience` 返回不含地址或凭据的降级状态。Redis 操作有 250ms 超时及 20 秒熔断，失效后不使用无界内存缓存。

## 缓存与预算

- 新浪缓存必须经来源资格、展示/模型许可、PAYLOAD_ALLOWED 和质量校验，并由服务端显式设置 `INVESTLENS_SINA_CACHE_SECONDS=1..900`；默认 0，未确认保留许可时禁止启用。其他源不会套用统一 TTL。
- 缓存键绑定主体、完整候选元数据/许可、查询窗口及质量版本；只保存有界 JSON，不使用 Java 反序列化。原获取时间保留，读取重新校验质量，变更许可/版本无法命中旧缓存。
- LIVE 发起实时读取，可合并同主体同窗口的进行中调用（最多八个不同 leader）；CACHE 仅使用已有合格缓存，未命中、Redis 失效或内容错误均明确 DATA_UNUSABLE，不自动请求真实源。
- V16 为实际市场/模型传输增加按主体和来源/账户哈希隔离的 MySQL 分钟限额，默认新浪 60 次、模型 30 次；这是本应用保守上限，不是上游官方额度承诺。不同 Key 不共享错误账户配额。模型限额 scope 只落 SHA-256，不保存 Key/URL 正文。
- 每次实际传输前独立事务消耗账户限额；外层失败不退还，重试亦计数，Redis 丢失不能重置。原 Run 的 8 次行情、4+1 模型、重试/租约/deadline 预算继续独立生效。MySQL 不可用时不绕过预算。

## 模式和回放

组合、Scenario、Run、报告及 Evidence Board 展示 LIVE/CACHE/REPLAY/SYNTHETIC 及明确中文说明。模式和原始时间来自持久记录，历史/刷新不改写。LIVE 不自动冒充 CACHE，合成数据不冒充真实或回放。

`POST /api/calculation-snapshots/{id}/replay` 请求 `{portfolioSnapshotId}`，显式将同主体的完整可重放源固定输入绑定到一个 REPLAY 组合；币种、金额口径、持仓、权重及原 asOf 必须完全相同。逐条核对可保存 payload 和 hash，复制证据时保留原时间；不请求 Provider。SYNTHETIC 源不能改标 REPLAY。REPLAY 不能通过普通窗口接口取实时数据。元数据或禁止保存的缺口不宣称完整回放。

现有编辑器仍以合成演示输入为默认；正式行情白名单和许可不因本组自动开放。真实源资格与在线模型验收仍在 G38/G39，不把模式支持等同于真实研究已通过。

## 验收

定向测试覆盖许可/缓存损坏/未命中、原时间、single-flight、Redis 端口不可达时发布/读取报告、并发账户限额及回放隔离；前端覆盖四种模式和未知模式。四容器的实际 Redis 停止验收见后续 G37。
