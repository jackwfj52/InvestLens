# G34：已提交事件的 JSON 与 SSE

`GET /api/runs/{id}/events?afterSequence=N` 默认返回 API envelope 内 `{items,nextSequence}`，每页最多 100 条。Accept 为 text/event-stream 时发送命名 progress 事件，id=runId:sequence，data 严格遵循 RunEvent 契约。按数据库序号读取已提交事实，不虚构阶段或工具。

流采用独立短只读事务，每半秒补读，空闲心跳不占业务序号；终态事件补齐后关闭。32 个连接上限、两个发送线程、30 秒连接寿命，完成/超时/断线释放资源。无代理缓冲，不在后台工作执行事务中直接发送。关闭浏览器仅结束等待，不取消任务。

前端正常连接展示真实阶段和状态，终态后读取 Run 获取已发布 reportId。G35 接持久游标、自动补读和轮询降级。本组集成验收 JSON/SSE 内容与顺序、终态结束、非法游标和断线后继续冻结研究。
