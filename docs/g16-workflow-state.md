# G16 持久 Run、Step 与 Event 状态模型

新增内部 `workflow/` 模型、状态机、MyBatis Mapper 与事务边界 `WorkflowStore`，V8 建立 research_runs、run_steps、run_events 以及计算快照/指标关联表。此组没有公开 Run 创建/查询接口、幂等键、队列扫描或后台研究；这些随 G17—G23 实现。

## 状态与持久字段

Run 使用 CREATED、QUEUED、RUNNING、COMPLETED、PARTIAL、FAILED、CANCELLED；stage 独立为 PLAN、RESEARCH、QUANT、DEBATE、JUDGE、VALIDATE 或未开始的 null。整体 dataQuality 使用 VALID/PARTIAL/UNUSABLE 或未评估的 null，与执行状态及 dataMode 分开。BULL/BEAR 都映射到 DEBATE，步骤仍保留各自键。

状态拓扑按 [冻结契约](contracts/workflow-and-api.md) 实现：CREATED→QUEUED；QUEUED→RUNNING/CANCELLED/FAILED；RUNNING→四种终态；终态无后继。Step 为 PENDING→RUNNING/SKIPPED、RUNNING→SUCCEEDED/FAILED，以及显式恢复的 RUNNING/FAILED→PENDING；SUCCEEDED/SKIPPED 不再改写。

Run 保存固定决策/组合版本、模式、阶段/质量、未来研究/报告引用、UTC 时间、deadline、尝试计数、revision 和 eventSequence。内部 createModel 只接受当前主体已确认决策并核对原组合，返回 CREATED；没有对外接受任务或启动研究。120 秒 deadline 与零计数仅建立未来预算持久字段，G20/G21 再接完整计量。

Step 有唯一 `(run_id, step_key)`、attempt、inputHash、outputRef、leaseUntil、executionVersion、errorCode 及时间。PENDING 第一次开始时 attempt/token 从 0 到 1；恢复使 token 递增而不清空历史 attempt，再次开始会增加 attempt。RUNNING 必须带未来租约，SUCCEEDED 必须带输出引用，成功/失败写入要匹配当前 token 且租约未过期；只有过期的 RUNNING 能显式退回 PENDING。未实现自动领取或自动恢复。

## 写入保护与事件

所有受支持写入先按 owner 锁定 Run 行，再做状态检查与版本条件更新；步骤也以旧 status/token 作条件。终态 Run 与成功/跳过 Step 被条件 SQL 拒绝更新。并发同 revision 只有一个状态转换成功；状态变化和对应事件同事务，事件插入失败时连同状态、序号和版本一起回滚。

表级 CHECK 约束拒绝非法枚举、负计数、缺少运行时间/租约、终态缺 finishedAt、成功步骤缺输出引用。COMPLETED/PARTIAL 还需要研究引用、snapshotHash 和 reportId；当前事务入口明确拒绝这两个发布终态，等待后续已验证报告的原子发布器，不能用空报告伪造成功。

RunEvent 使用 `runId:sequence`，唯一 `(run_id, sequence)`，从 1 增长；类型为 RUN_QUEUED、STEP_STARTED、STEP_SUCCEEDED、STEP_FAILED、RUN_TERMINATED。消息是固定枚举文本，不保存上游原始响应。只有真实步骤开始才记录 STEP_STARTED，Run 变 RUNNING 本身不冒充步骤开始。事件 Mapper 只提供新增与读取，不提供修改；直接数据库管理员写入不属于状态机接口保护范围。

G16 最终迁移不依赖触发器或 SUPER 权限。首次本地迁移试验在触发器创建处因默认账号权限失败；确认本次五张新表全部为空后，只回退这些空结构和两项新增索引，再用 Flyway repair 清除失败记录。成功的 V1—V7 校验和及既有数据未改动；修订后的 V8 完整执行，并经重复启动验证。未调整数据库全局信任设置或应用账号权限。

## 快照与指标关联

`run_calculation_inputs` 将 Run 与计算输入绑定，组合快照由双向复合外键保证匹配；`run_metric_bundles` 要求引用已关联的同一计算快照以及真实持久 Bundle。事务入口检查当前主体、组合版本与模式。关联保留 G11/G13 的原 JSON 与哈希，允许明确复用独立输入；G27 再物化 Run 专属 Evidence/Manifest 与发布 Bundle，当前不把这些内部关联冒充已冻结的研究引用。

## 验收

JDK 17、真实 MySQL 8.4.13/Flyway：3 项纯状态机测试遍历所有 Run/Step 状态对并核对阶段映射；9 项集成覆盖固定输入、DB 字段约束、非法/终态转换、报告缺失阻断、步骤键及事件序号唯一、租约/token/attempt、真实输出引用、过期令牌、事件失败原子回滚、并发竞争、跨组合关联拒绝与原对象不变。V8 首次迁移及重复启动校验通过。

组末后端 261 项全部通过，前端 27 项及类型/生产构建通过。测试仅创建显式合成模型和确定性输出引用，不生成研究完成状态；没有后台任务或模型请求。下一组 G17 实现事务建 Run、固定输入与幂等 API。
