# G29：Judge 与报告候选

Judge 只读取已保存的 Bull/Bear 结果及同一冻结快照。Verdict 的 claimId 必须存在且与 SUPPORTS/OPPOSES 一致；缺宏观因果证据时保留 INSUFFICIENT_EVIDENCE 和逐假设 UNVERIFIED，不用组合规则推断宏观论点成立。

Judge 关闭、超时、格式错误或额度不足时，最低调仓及显式冲击结果完整才生成 PARTIAL/DETERMINISTIC_FALLBACK 候选，保存缺失角色、限制及后续检查；没有显式冲击或必要确定性指标则 FAILED 无候选。Planner 可用状态来自实际成功调用事实，不能把确定性计划标为模型已审查。LIVE/REPLAY 等常规 scope 保留 HISTORICAL_RISK required，缺工具数据不能删减 scope。

候选保存在 JUDGE 的原子步骤输出，未写正式 reports、Run reportId 或 COMPLETED/PARTIAL 终态，也不填写 validatedAt/publishedAt。完整角色与 required 数据齐备才允许候选状态 COMPLETED；宏观 optional 缺口仍明确显示。G30 校验结构与归属，G31/G32 再做数值校验和原子发布。
