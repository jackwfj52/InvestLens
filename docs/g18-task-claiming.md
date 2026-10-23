# G18：数据库领取与租约

扫描以 MySQL Run 行为串行化边界，使用 `FOR UPDATE SKIP LOCKED` 跳过其他实例正在领取的行；条件限定本机主体、活动状态及待执行/租约过期步骤。同一 Run 有有效执行租约时不再安排另一执行者。领取事务原子更新 Run、Step、attempt、executionVersion 和 STEP_STARTED 事件，事务返回后才调用处理器。

租约最多 45 秒，且不越过原 deadline；排队也计入 120 秒。已过 deadline 的任务失败为 BUDGET_EXHAUSTED，不调用处理器。过期步骤先使旧 token 失效，再产生新 attempt/token；所有写入口核对 Run 活动、当前 Step、token 和有效租约。成功/终态不被重新领取。

最小 PLAN 处理器只读取固定输入，输出明确的固定输入计划与模型禁用缺口。G19 再连接受限线程池及持久输出；这里没有伪造模型或正式报告。可注入 UTC Clock 供后续预算边界测试。

验收：真实 MySQL 四个并发扫描只有一个有效领取者；有效租约期间再次扫描为空；处理器读取固定组合；过期租约重领保留 attempt 并使旧 token 无法成功；排队超时不开始步骤。更完整的重启检查点恢复留给 G23。
