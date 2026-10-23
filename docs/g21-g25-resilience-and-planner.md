# G21–G25：调用控制、取消、恢复与 Planner

## G21：动态超时与有限重试

`BudgetedCalls.executeWithRetry` 是唯一的重试层。每次尝试先持久化配额与记录，再派发；401/403 不重试，429、网络错误和超时在 Run 全局两次重试额度内处理。Retry-After 支持秒数和 HTTP 日期；退避必须小于剩余 deadline 和步骤租约，过大的等待立即拒绝。模型最多 30 秒、行情最多 8 秒，并在派发时重新计算剩余 Permit 时间。新浪子进程与 socket 均接动态上限，超时销毁进程及子进程。行情许可和质量门槛仍在原工具入口执行。

## G22：显式取消

`POST /api/runs/{id}/cancel` 返回 200 和最新 Run。按主体隔离，未知 ID 返回 404。取消在 Run 行锁事务中把待执行、运行中步骤置为 SKIPPED 并提升执行令牌，再进入 CANCELLED；重复取消复用终态。已经结束的任务保留原终态。新派发、退避后派发、返回结果和检查点写入均检查状态、租约和令牌。迟到调用只允许落自己的调用事实，不发布输出。HTTP 断开不代表取消。

## G23：恢复与复用

启动后的调度及定期扫描复用同一数据库领取路径。恢复前核对成功输出的 Run、Step、执行令牌、输入 hash、输出内容 hash 和后继输入引用。成功步骤保留；仅重领过期的未完成步骤，提升执行令牌，步骤尝试最多三次，deadline、调用计数和修复计数均不重置。损坏检查点明确失败。验收以真实 MySQL 中 Quant 成功后的过期领取模拟执行者重启；完整模型跨进程恢复仍属 G33。

## G24：模型网关

参考 WealthFlow AiModelGateway/OpenAiCompatibleGateway 的后端接口、Java HttpClient 与安全错误模式，适配 Boot 4 Jackson 3。返回 JSON 文本、实际模型、finish reason 和 usage；拒绝截断、空输出、异常 usage、错误格式及过大响应。不跟随重定向，不返回供应商错误正文或密钥。V12 在 tool_calls 保存成功回复的模型和 token 元数据。

网关默认关闭，需显式配置 `INVESTLENS_LLM_ENABLED=true`、用户自己的 `LLM_BASE_URL`、`LLM_MODEL` 和后端 `LLM_API_KEY`（兼容已有 DEEPSEEK_API_KEY）。模板 URL、模型为空。默认只发兼容协议字段；供应商需要关闭思考时可设置 `LLM_DISABLE_THINKING=true`。本轮没有启用真实研究输入的在线发送。一次显式启用的 `LlmOnlineProbe` 仅发送固定的非金融 JSON 连通消息，返回 stop、totalTokens=64，通过；不包含用户决策、持仓或文件内容。探测没有自动重试。

## G25：严格计划

版本化 Prompt 与独立 Plan JSON Schema 位于后端 resources/prompts。PlanParser 拒绝重复键、尾随 JSON、代码围栏、未知字段、工具、任意 URL/参数、未授权资产、超过三个资产、同资产多窗口、错误日期、超过六个步骤、重复步骤、未知依赖和环。Run、决策版本及假设集合必须匹配固定输入。最多一次全局 Schema 修复，使用与其他角色共享的持久预算；第二次无效即失败。模型启用时 PLAN 可保存通过验证的计划检查点，后续 Research 工具执行仍属 G26。

用户确认 URL 由用户自行配置；模板不提供默认供应商地址，网关默认关闭。启用后 Planner 将固定引用、假设、调仓动作、期限、风险约束及非现金资产白名单发送到用户所配置的兼容网关。没有在线发送真实决策数据。

## 验证记录（2026-10-23）

- 最终后端全量 299 项通过，零失败；此前 31 项定向回归通过，最终另补 Retry-After 秒数、HTTP 日期及整数溢出用例。
- 本机 HTTP stub 验证结构化请求、usage、401/403、429 Retry-After、动态超时、截断、空输出、格式错误及默认关闭。
- 真实 MySQL 验证重复取消、取消/检查点竞争、迟到返回、API 404、全局重试、Quant 输出复用、预算保留、旧令牌隔离、损坏输出、Planner 一次修复与 usage 持久化。
- 行情 Python 适配器 4 项通过；G01 Schema、24 项拒绝用例与真实 Run API Schema 检查通过；Flyway V1–V12 校验通过。
- 没有生成完整研究报告，没有宣称正式行情资格、M2 或 G33 完成。G21–G25 已按组提交，对应提交见开发顺序文末记录。
