# G40：三分钟合成演示与回读

演示版本：`g40-synthetic-demo-20261024`，不创建 Git tag。**这是默认关闭模型的确定性合成演示，不是真实行情研究或在线多角色演示。** 正式门槛见 [验收矩阵](acceptance.md)。

## 冻结输入及版本

| 项目 | 固定值 |
| --- | --- |
| 输入文件 | `backend/src/test/resources/portfolio-create.json`、`decision-ready.json`；演示脚本只改名称、说明、随机对象 ID，并确认输入 |
| 资产 | USD 归一化总值 100；黄金 ETF 代理 20%、白银 ETF 代理 10%、现金 70%，非现货金属 |
| 动作 | 用现金将黄金权重调整到 30%，不是增加 30 个百分点；调仓后 30/10/60 |
| 期限/约束 | 六个月；目标权重上限 30%，情景损失上限 8% |
| 显式用户冲击 | 黄金 -20%，白银 -25%，现金 0%；不是预测 |
| 原输入时间 | `2026-10-23T00:00:00.000Z`；取回/运行时间单独保留，不刷新为假实时数据 |
| 预期算术 | 调仓前 -6.50%，调仓后 -8.50%，变化 -2.00 个百分点；权重阈值通过、损失阈值超限 |
| 报告 | SYNTHETIC / PARTIAL / DETERMINISTIC_FALLBACK，四角色 DISABLED，INSUFFICIENT_EVIDENCE，宏观 UNVERIFIED |
| 预算 | 行情尝试 0、模型尝试 0；不声称四次在线审查 |
| 契约/引擎/质量 | `1.0.0` / `financial-engine-v1` / `market-quality-v1` |
| 提示词 | `planner-v1` / `bull-v1` / `bear-v1` / `judge-v1`；本演示禁用模型，并未执行提示词 |
| 实际回执源提交 | `32dee6b2b5d04129f6cfb0b32b1addfaf614534c`，生成回执时工作区干净；应用生产代码仍为 G37，后续提交仅测试/脚本/文档 |

应用镜像与 G37 相同：backend `sha256:b4095c0ea9b79ef3df530ea46922489d5c69393a852bd8d781b9274701154341`；frontend `sha256:9aebb910219e1e4a11043517029073bae1189ea9090c18d754a06ed26911ae5f`。其他基础 digest 见 [部署说明](g37-deployment.md)。`:local` 标签可变，应以回执内实际 ID 核对，不把本机标签当不可变发行包。

## 演示前准备

按 README 启动四容器，在 `.env` 保持模型和实际行情关闭；不要显示 `.env`、展开 Compose 配置或容器环境。普通部署只要求 Docker，下面可选种子/校验需要宿主 Python 3.10+。

```powershell
python -B scripts/check-deployment.py --label G40 --output backend/target/g40-demo-report.json
python -B scripts/check-delivery.py
```

种子脚本会创建新的合成组合/决策/情景/Run，等待后台发布并验证 SSE；它不是只读回放，也不会删除旧记录。输出报告及 `g40-demo-report-receipt.json` 均被 Git 忽略，含实际对象 ID、输入文件字节哈希、报告字节哈希、快照哈希、提交/工作区状态和镜像 ID；后续版本还包含组合 ID。换机器或源文件换行符变化后应生成该环境自己的回执，不能沿用旧文件哈希假称一致。

## 三分钟讲稿

| 时间 | 页面动作 | 讲述重点 |
| --- | --- | --- |
| 00:00—00:25 | 展示连接和 SYNTHETIC 标识，打开已保存输入 | “我们先检验证据和风险。当前是明确的合成流程演示，不代表真实价格，也不是交易建议。” |
| 00:25—00:55 | 核对 20/10/70、现金增配到 30%、期限与约束 | “目标权重和增加百分点不同。用户假设必须独立验证，输入确认不等于假设成立。” |
| 00:55—01:25 | 读取原情景，展示调仓及后端指标 | “黄金和白银冲击由用户显式给定；损失从 6.50% 变为 8.50%，超过用户的 8% 阈值。金融数值不是模型编写。” |
| 01:25—02:00 | 从研究历史打开原 Run，展示状态和报告 | “后台状态、检查点和事件存在 MySQL。当前模型关闭，因此报告明确是部分降级结果，不冒充 Bull/Bear/Judge 已完成。” |
| 02:00—02:25 | 点击损失阈值的指标来源，展开公式，再跳 Evidence | “公式、输入哈希、Run 引用、来源时间和保存/模型许可可追溯。USER_INPUT 不是市场证据，缺失宏观仍未验证。” |
| 02:25—02:50 | 填入合成示例并计算，黄金改 -10%，等自动重算 | “新情景变为 -4.50% / -5.50%，无需模型。原冻结报告仍为 -8.50%，不会被新假设覆盖；不要点重新运行。” |
| 02:50—03:00 | 刷新，展示相同 Run/报告 ID 和原发布时间 | “刷新只回读，不新增研究。真实源资格、四次在线审查和新目标机器验收仍待补齐。” |

演示前预留好历史/原输入 ID；不要在三分钟内首次下载镜像或等待服务启动。禁用模型时不展示伪造的多空观点；要讲完整四角色技术流程，单独运行 [G38 本机 stub 测试](g38-research-acceptance.md)，并明确它不是在线结果。

## 本次已保存的演示记录

仅适用于当前电脑的 `investlens_mysql_data` 卷，不是跨机器通用 ID：

```text
portfolioId: portfolio_b1afbbf9-ee12-4695-8cb4-59dbf846cf24
portfolioSnapshotId: portfolio_snapshot_a28daa50-fd23-4433-b6b8-fc42d6aa8e9f
decisionId: decision_32178edc-c636-4e36-ae15-7c39ed280a62
scenarioId: scenario_b0b1fcb1-f189-434f-bb9f-d1c1c99c6131
runId: run_78c30d81-babd-48cf-bc22-7d1dfde19a3d
reportId: report_b01ee4cf-3978-4201-9b0c-7ff39cfd07aa
snapshotHash: sha256:9d4b58a8694a19b0446532702a73c7f715bc1a0c8bc8edccda3926e201c5ef75
publishedAt: 2026-10-23T17:40:02.106Z
reportSha256: 95d96b7940bbbc245e0274dca97652525ec2518f152d3d502a879e169366eca8
```

只读重开：打开 `http://127.0.0.1:8080/workbench`，研究历史点 G40 条目，再选其 SYNTHETIC/PARTIAL 运行；原输入分别用“按 ID 重新打开组合/决策”读取，原情景在“重新读取已保存结果”输入 scenarioId。历史不会替换当前编辑输入。若需要从回执找组合 ID，旧回执可 GET `/api/calculation-snapshots/{calculationSnapshotId}/inputs` 查看固定 portfolio；不要重新保存来冒充原版本。

同卷 `down` / `up` 后仍能回读；没有原卷或完整许可输入时不能恢复同一报告。复制报告 JSON 只能静态查看，不等于导入数据库、更不等于真实数据重放。SYNTHETIC 不转换为 REPLAY/LIVE；合法 REPLAY 的前提是非合成源、完整保存许可、同口径组合与有效哈希，仍保留原源时间。

## 实际演练记录

2026-10-24 本机浏览器演练通过：重开原组合/已确认决策和情景；读取固定报告；点击损失指标来源、展开 direct-shock-v1 并跳 USER_INPUT；将黄金冲击改为 -10% 后自动得到 -4.50%/-5.50%，原报告和预算不变；刷新后恢复相同 Run、报告、原发布时间及 -8.50%。重新执行交付审计核对回执与数据库原报告一致。

截图 `backend/target/g40-report.png`、`g40-evidence.png` 与报告/回执对应同一 Run。**本次没有生成连续录屏视频或声称录屏交付**；如另行录制，使用本讲稿并同时保留回执，画面核对 dataMode、runId、reportId、snapshotHash、发布时间与镜像 ID，不混入不同版本或在线连接探测。
