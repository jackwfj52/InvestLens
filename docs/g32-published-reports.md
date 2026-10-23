# G32：原子报告与只读历史

V15 新增 reports、verdicts、evidence_links。VALIDATE 的执行令牌与租约通过后，步骤输出、校验成功、报告正文、后端展示值、结论、引用、Run 终态和终态事件在同一事务提交。发布器再次执行结构、归属和数字检查；失败或取消不能产生可见报告。相同验证检查点重复提交只读复用，不生成重复终态事件。

- `GET /api/runs/{id}/report`：运行中 202/RUN_IN_PROGRESS；已发布 200；失败或取消且无报告 409。
- `GET /api/runs/{id}/report/figures`：已发布指标的后端格式化值，附原值、单位、公式和证据 ID。
- `GET /api/runs/{id}/evidence`：当前 Run 的可保存证据元数据和缺口，不返回原始 payload。
- `GET /api/decisions?limit=20&cursor=...`：本机主体决策摘要，按创建时间和 ID 降序键集分页。
- `GET /api/decisions/{id}/runs?limit=20&cursor=...`：同主体决策的全部运行版本历史，明确重跑不会覆盖原报告。

limit 范围 1–100；无效游标返回 400。所有查询只读，不调用 Provider/模型、不新建 Run。报告正文和后端展示值持久保存，重启后不重新组装或按新配置改写。公开状态迁移入口仍不能绕过发布器。

验收使用真实 MySQL 验证事务提交后整体回滚、原检查点重试、重复保存、HTTP 报告/历史、不同 Run 引用隔离及取消后的迟到结果。在线模型及正式行情资格未完成，不能据此宣称真实数据 M3 达成。
