# G01 验收记录

执行日期：2026-10-23（Asia/Taipei）。范围：首版业务契约、JSON Schema 和合成贯穿样例；未启动业务服务。

## 验收结果

| 检查点 | 结果与证据 |
|---|---|
| 输入与最小领域对象 | 23 项 `$defs` 覆盖 DecisionDraft、DecisionVersion、Hypothesis、Instrument、PortfolioSnapshot、ResearchSnapshot/Manifest、Plan、Claim、Verdict、MetricBundle、Scenario、Report、Run/Step、API 等；Schema 自检通过 |
| 单位与资金闭环 | 初始 20/10/70；现金减少 10 个百分点、黄金 ETF 目标 30%；得到 30/10/60；targetWeight 与 deltaPercentagePoints 两种表达等价 |
| 情景与规则 | 冲击 -20%/-25%/0；收益 -0.065→-0.085；归一化损益 -6.5→-8.5；变化 -2 个百分点；目标仓位上限规则 PASS，情景损失 8% 阈值在调仓后 FAIL |
| 版本与不可变快照 | 样例 Decision、组合、Plan、ResearchSnapshot、Bundle、ScenarioResult、Report 的固定引用、输入 hash 和版本均一致；组合/研究/指标/元数据/计划 hash 重算一致 |
| 输入状态与执行状态 | NEEDS_INPUT 在 Run 前处理；状态转换表明确终态、取消和恢复边界；未确认请求被样例校验器拒绝 |
| 数据模式与未知项 | 全样例标明 SYNTHETIC；占位 ETF 代码不绑定真实源；宏观假设 UNVERIFIED；Judge 缺失产生 PARTIAL / DETERMINISTIC_FALLBACK / INSUFFICIENT_EVIDENCE |
| 最低报告规则 | 包含固定输入、合法 USER_INPUT 引用、前后权重、直接冲击、阈值、假设评价、缺口和版本；缺最低指标/错误引用/单位错配/伪造数字被拒绝 |
| 拒绝场景 | 24 项全部通过，具体名称由 validate.py 输出，覆盖精度、非法组合、资金不足、跨 Run/快照、Schema、引用、单位、报告降级和计划依赖环 |
| 哈希稳定性 | 对象 key 和持仓集合换序、组合 ID 变化不改变 contentHash；asOf 变化改变 hash |

## 执行环境与命令

本机常规 `python`/`py` 没有可用 Python 安装；使用 Codex 提供的 Python 3.12.14 与临时目录安装的 jsonschema 4.26.0。校验依赖只安装到系统临时目录，未修改项目依赖。

实际校验命令（PowerShell）：

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONPATH = 'C:\Users\Jack\AppData\Local\Temp\investlens-g01-python'
& 'C:\Users\Jack\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' docs/contracts/validate.py
git diff --check
```

在已安装 Python/jsonschema 的环境可直接运行 `python docs/contracts/validate.py`。结果：Schema 自检、完整样例、24 项拒绝场景及 hash 不变量全部通过。`git diff --check` 无空白错误；Markdown 相对链接均解析到现存文件。

样例固定 hash（算法 investlens-canonical-json-v1）：

- Portfolio contentHash：`sha256:40e2b229e37f3fcb400a0e3d26db1f0d9826af57518d5b769388b5ac5aa81a54`
- Research snapshotHash：`sha256:2d8ec23c2fbad5892eae5f868fa2b26b736a9abb6734857e63032babf6e1bf57`

## 后续验收与交接

本组是文档契约变更，不运行无关 Maven/数据库集成测试。validate.py 是样例协议校验与 hash 的参考实现，不能替代后续 Java 业务校验器、数据库事务/状态竞态测试或真实源验收。最低报告拒绝场景在本组检查结构与样例绑定，真正发布拦截由 G30/G31 实现。

下一组 G02：增加参数化且脱敏的行情/模型探测，确认股票、两种 ETF 的代码、币种、日历、复权、历史覆盖、限流和展示/模型/存储许可。未执行在线探测，不宣布 M1/M2 或真实研究闭环完成。

首个记录时间为 16:44；总耗时及提交 ID 以根目录 [开发顺序](../../开发顺序.md) 末尾 G01 记录为准。工作区原有 README、复用计划与开发顺序的改动保留，G01 的 Git 提交仅包含本契约目录。
