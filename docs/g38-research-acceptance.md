# G38：研究闭环验收

执行时间：2026-10-24，Asia/Taipei。结论：**合成输入与本机模型 stub 的技术闭环通过；真实行情 + 在线模型正常路径未验收，不标记真实数据 M2/M3 完成。**

## 新增跨模块用例

`backend/src/test/java/org/jack/backend/research/ResearchAcceptanceTests.java` 使用真实 Spring HTTP 服务、开发 MySQL 和真实工作流/计算/发布逻辑；只有 Provider 与模型是本机测试替身，调度器关闭，由测试显式领取七个步骤。没有请求用户配置的模型 URL。

正常路径从合成组合、已确认决策及固定历史样本开始：

1. 保存明确标记 SYNTHETIC 的黄金 ETF/白银 ETF/现金组合与宏观未验证假设。
2. 通过 Provider 替身产生三十一个闭合交易日样本，保留原接收时间，不把它当真实行情。
3. 固定计算快照和显式冲击，HTTP 同幂等键创建两次，返回同一 Run。
4. Planner、Bull、Bear、Judge 各调用一次；三次审查共享同一冻结 snapshotHash，无修复、无重试，完成时间不超过原 deadline。
5. Bull/Bear 均有非空观点、条件、失效条件、未知项及合法 Evidence/Metric 引用；Judge 引用实际观点，保留 INSUFFICIENT_EVIDENCE。
6. 数字闸门及原子发布后，HTTP 读取 MODEL_REVIEW 报告、Evidence Board、后端展示值、历史和游标事件。报告虽为流程 COMPLETED，数据仍为 SYNTHETIC / PARTIAL，宏观仍为 UNVERIFIED。
7. 从同一固定输入修改黄金冲击重新计算情景，不额外调用模型或 Provider，不修改原报告，不产生第二个 Run；历史可以重读原报告。

另一个跨模块用例在 Judge 结束、VALIDATE 已算出结果但尚未提交时通过 HTTP 取消；晚到检查点被令牌栅栏拒绝，报告表仍为空，终态事件只有一个，报告接口返回 409。

## 结果与复现

新增两项测试通过（0 失败/错误/跳过）。首次运行修正了测试中历史指标名称断言（契约为 PERIOD_RETURN），没有改动生产金融公式。后续全部回归及故障验收统一见 [验收矩阵](acceptance.md)。

```powershell
Set-Location backend
.\mvnw.cmd -B -ntp '-Dtest=ResearchAcceptanceTests' test
```

本机验证使用已缓存 Maven 与 JDK 17；开发 MySQL/Redis 由 `scripts/dev.ps1 up` 提供。不要同时运行另一实例后台调度器抢占测试库任务。报告导出到被忽略的 `backend/target/g38-model-stub-report.json`，不是可冒充真实行情的交付数据。

## 不通过/未执行的正式门槛

| 门槛 | 状态 | 原因与解除条件 |
| --- | --- | --- |
| 合格真实历史数据端到端 | 阻塞 | 生产白名单为空；需确认内容保存/展示/模型使用权、复权/公司行动与交易日口径，见 [能力矩阵](provider-capabilities.md) |
| 在线四次模型调用 | 未执行 | 需用户配置并显式启用自己的 URL/模型/凭据，在上列许可通过后单独登记真实执行记录；最小连接探测不是完整研究验收 |
| 真实引用跳转、真实指标、真实历史重读 | 未执行 | 不能由本机替身/合成样本的成功推导真实链路成功 |

不会为追求绿色验收擅自开放行情白名单、补造价格/宏观证据、默认启用模型或把旧资料时间改为当前时间。
