# G30：报告候选结构与引用归属

ReportStructureValidator 使用打包的实际 G01 Report Schema 校验候选；候选仅省略尚未发生的 validatedAt/publishedAt，禁止模型自行填写这两个生命周期字段。校验未知字段、类型、范围、条件约束，再核对主体/Run、决策和组合版本、研究 snapshotHash、Manifest、计划、配置和 Prompt 版本。

证据必须在当前 Run 的 research_evidence 关联和冻结 Manifest 中，元数据/保存策略与实际持久记录一致，已存 payload hash 可复算。论点只能引用可保存、模型可用的有效证据和可计算指标；指标必须来自冻结 Bundle 且内容 hash/公式/输入 hash 一致。E99、其他 Run 的证据、其他快照、篡改指标、伪造角色可用状态、Judge 未引用的论点均被拒绝；正常引用可定位到 Evidence 及其固定输入。用户假设评价必须完整，宏观证据不足保持 UNVERIFIED。

STRUCTURE_VALID 明确 numericValidationComplete=false、published=false。VALIDATE 步骤保存结构验收检查点后，当前版本以 FAILED/REPORT_UNAVAILABLE 明确结束 Run；没有最终报告或完成状态。G31/G32 完成前不能发布，结构校验通过不代表自由文本金融数字已安全或报告已发布。非法候选以 MODEL_OUTPUT_INVALID 拦截并保留之前的研究和角色检查点。

联合验收补齐：冻结输入保存 G01 InputContent 完整封装，涵盖组合、动作、显式冲击、获准行情及计算参数，Bundle inputHash 可独立重算；资产元数据包含已取得行情的完整 Instrument。可选 scenarioId 区分省略和显式 null，保持原五字段客户端兼容；情景绑定、计算快照和 MetricBundle 都建立持久 Run 关联。Planner 得知固定情景存在时必须选择 Quant.runScenario，且不能刷新固定行情。

最终验收（2026-10-23）：后端全量 321 项通过、零失败；前端 29 项、类型检查及生产构建通过；G01 24 项拒绝用例、实际冻结对象/候选结构、G13/G14 数值和 hash 检查通过。专门验证重复市场步骤只取数一次、Quant 依赖完整、四次正常模型调用使用同一 snapshotHash、单侧超时、全局一次修复、Judge 降级、跨 Run/E99/篡改指标拦截、取消后迟到写入隔离。模型均为本机 stub，未发送真实组合做在线研究；正式行情资格与 M3 仍未宣告完成。
