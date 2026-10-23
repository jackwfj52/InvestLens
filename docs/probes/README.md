# G02 探测执行记录

执行日期：2026-10-23，约 17:35—17:55（Asia/Taipei）；JSON 内时间以 UTC 记录。详细决策与待确认项见 [能力矩阵](../provider-capabilities.md)。

环境：Windows、Codex 捆绑 Python 3.12.14、Node.js 24.16.0、AKShare 1.19.1（仅安装至临时目录，未复制任何账户、数据库或行情）。源码与脚本不包含真实 Key。既有 DEEPSEEK_API_KEY 用于一个无金融信息的固定 JSON 消息。

实际执行使用的解释器为：

```powershell
$probePython = 'C:\Users\Jack\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'

# 新浪解码算法安装于本机临时目录；仅为该 Python 进程提供包搜索路径。
$env:PYTHONPATH = 'C:\Users\Jack\AppData\Local\Temp\investlens-g02-sina'
& $probePython -B scripts/probes/probe.py --output docs/probes/2026-10-23-sina-history-direct.json market --provider sina

# 对比入口（失败/不完整时进程非零是预期结果，不表示通过）。
& $probePython -B scripts/probes/probe.py --output docs/probes/2026-10-23-baidu-history-direct.json market --provider baidu
& $probePython -B scripts/probes/probe.py --output docs/probes/2026-10-23-ths-direct.json site --provider ths
& $probePython -B scripts/probes/probe.py --output docs/probes/2026-10-23-binance-direct.json crypto
& $probePython -B scripts/probes/probe.py --output docs/probes/2026-10-23-deepseek-direct.json llm --disable-thinking

# 离线验证，不需要 AKShare / Node 或外网。
& $probePython -B -m unittest discover -s scripts/probes -p 'test_*.py' -v
git diff --check
```

结果：17 项离线检查全部通过；新浪三个历史序列各 85 根日 K，币安两个序列各 64 根闭合日 K，DeepSeek 的精确 JSON 小对象通过。百度历史接口 403、同花顺页面 302、Twelve Data 未配置均如实记录。

早期 `2026-10-23-yahoo.json`、`2026-10-23-deepseek.json` 使用系统默认代理规则，其网络上下文后补为 SYSTEM_DEFAULT，不能用于证明大陆直连。`2026-10-23-twelvedata.json` 没有 Key，未发请求。其余带 `-direct` 的记录使用显式禁用代理的脚本，仍没有出口位置/透明隧道或原生大陆运营商验证证据。记录中的 mainlandAccessVerified=false 不由本机 HTTP 成功自动升级。

未执行：真实大陆原生网络部署、实际 429/限流压测、连续多日稳定性、完整交易日历/公司行动校验、源数据存储/模型输入/外部展示资格确认、模型角色与报告链路、生产 Java↔Python 适配器。G02 的这些边界不能被本次格式/连通探测替代。
