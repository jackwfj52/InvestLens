# G02 行情与模型能力探测

部署基准是**中国大陆互联网直连，不要求用户翻墙**。所有脚本 HTTP 请求使用 `ProxyHandler({})` 禁用环境变量与系统显式代理，禁用自动跳转，每个入口只尝试一次。脚本无法证明出口地理位置或排除系统透明隧道，因此报告固定保留 `mainlandAccessVerified: false`，不能把开发机结果当作大陆运营商验收。

结果与后续阻塞见 [能力矩阵](../../docs/provider-capabilities.md)。这些脚本只用于探测，不是生产 Provider、报告引擎或交易客户端。默认行情候选是新浪；百度、同花顺为对比；币安仅为用户允许的条件后备。没有自动跨源拼接或资产切换。

## 环境

- Python 3.10+；除新浪解码外只使用标准库。
- Windows 如缺少 IANA 时区库，安装 `tzdata`；脚本报 `TIMEZONE_DATABASE_MISSING` 时先解决依赖。
- 新浪解码需要 Node.js 18+ 和固定版本 AKShare。AKShare 只用于读取本地 `zh_js_decode` 算法文本；不导入整个库，不调用其自动联网接口，不执行网络响应中的 JavaScript。通过版本与 SHA-256 校验后，由本地 Node 执行固定算法，网络响应仅作为字符串数据输入。

```powershell
# 在选定的 Python 开发环境安装固定包；不需要安装其余 AKShare 依赖。
python -m pip install --no-deps -r scripts/probes/requirements-sina.txt
python -B scripts/probes/probe.py --output probe-sina.json market --provider sina
```

解码算法来源：AKShare 1.19.1 的 `akshare/stock/cons.py::zh_js_decode`，SHA-256：`a42a1b100f70c858666ba74d9855a34e5b2a584c45e987e08eeaedf8d70b7167`。版本/校验不匹配直接拒绝；升级时重新审查、探测并更新固定值。AKShare 的 [MIT 软件许可](https://github.com/akfamily/akshare/blob/main/LICENSE) 随原安装包保留；软件许可不代替上游数据使用条件。

## 执行

全局参数 `--env-file`、`--output` 放在子命令前。默认 AAPL / GLD / SLV 可通过 `--stock`、`--gold`、`--silver` 改变；行情窗口为默认最后一个已过去的纽约日历日期向前 120 天。`--end-date YYYY-MM-DD` 可固定窗口，拒绝当前/未来美国会话日期。只计算样本数量、日期和质量摘要，不输出价格或保存原始行情。

```powershell
# 原定美元股票/贵金属 ETF 的国内数据候选
python -B scripts/probes/probe.py --output probe-sina.json market --provider sina --node node

# 百度实际页面与历史数据接口分别探测
python -B scripts/probes/probe.py --output probe-baidu-page.json site --provider baidu
python -B scripts/probes/probe.py --output probe-baidu-history.json market --provider baidu

# 同花顺页面状态；不自动跟随跳转、不执行请求校验脚本
python -B scripts/probes/probe.py --output probe-ths.json site --provider ths

# Twelve Data 仅为可选诊断，需要本机环境 TWELVE_DATA_API_KEY
python -B scripts/probes/probe.py --env-file .env --output probe-twelve.json market --provider twelvedata

# 使用已有模型环境变量，只有固定的无金融数据 JSON 测试消息
python -B scripts/probes/probe.py --output probe-model.json llm --key-env DEEPSEEK_API_KEY --disable-thinking

# 可参数化其他兼容供应商；URL/模型需与所用 Key 的服务及地域匹配
python -B scripts/probes/probe.py llm --key-env DASHSCOPE_API_KEY --base-url https://dashscope.aliyuncs.com/compatible-mode/v1 --model qwen-plus

# 条件后备：仅两种公开现货历史行情，无 Key，无交易动作
python -B scripts/probes/probe.py --output probe-binance.json crypto
```

`.env` 必须是忽略的本地文件；参考根目录 [.env.example](../../.env.example)。支持 `NAME=value`、匹配的单/双引号和独立注释行；不支持 shell 表达式、变量展开、export 或行内注释。已有环境变量优先于文件，CLI 非敏感参数优先于环境。Key 只能放环境/本地文件，不放命令行参数；可用 `--key-env` 指定变量名。

`market --provider yahoo` 仅保留早期对比诊断，报告为 diagnosticOnly；不作为中国大陆部署默认，也不能因为本机成功就启用。`site` 返回 PAGE_AVAILABLE 或 ENCODED_HISTORY_AVAILABLE 仅说明页面/格式可读，不表示已获得合法可算的历史行情。

## 结果语义与边界

- 退出码 0：该次技术请求及相应格式检查通过；2：配置缺失、技术请求失败或探测不完整。终端环境可能将非零进程码概括为 1，应以 JSON failureCode 为准。
- `technicalStatus = PASSED` 只表示该入口的检查通过；正式接入还要完成网络、元数据、交易日历、复权与使用条件核对，因此 `productionAllowlist` 保持为空。
- 行情检查为已过去日期、正数且有限的 OHLC、区间关系、会话去重和至少 31 个价格样本。不能据样本数推断日历完整/统计有效；缺失不填零；部分源不返回币种/交易所，保留 null，不能伪造元数据。
- 模型探测设置 json_object，并本地严格解析精确的小对象；这不证明供应商强制执行 JSON Schema，也不代表 Planner/Claim/Report 已验收。记录 finish_reason、实际模型标识、usage，截断/空输出/错结构都失败。
- 超时是单次 socket 超时（市场 8 秒、模型 30 秒），不是 G20/G21 的完整 Run deadline。没有自动重试，不主动打满额度测试 429；仅被动记录 Retry-After/额度头，离线检查验证错误分类。
- 响应上限 2 MiB（解码输出另限 16 MiB）；只保留枚举化错误、数字计量、日期和有限元数据。禁止记录 Key、请求完整 URL、Authorization、原始错误消息、模型文本、原始行情或浏览器会话。已知 Key/Token 在字符串值中再次脱敏。
- Java 业务后端保持现状。G09 可实现一个受限 Python 取数适配器，通过标准 JSON 交给 Java；本组没有提前实现该适配器、进程池、行情保存或数据库迁移。

## 离线检查

```powershell
python -B -m unittest discover -s scripts/probes -p 'test_*.py' -v
git diff --check
```

这些检查不请求外网，覆盖单次尝试、代理禁用、跳转、401/403/429、超时、输入/响应限制、脱敏、非法行情、模型 JSON、Sina 数据与代码隔离及 Crypto 闭合日线。实际在线记录位于 [docs/probes](../../docs/probes)。
