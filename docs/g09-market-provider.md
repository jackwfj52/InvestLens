# G09 行情 Provider 与验收

已实现 `market/MarketDataProvider`、固定资产元数据、`SinaMarketDataProvider`、受限 Python 传输及 `tool/MarketHistoryTool`。所有业务结果为来源无关的 Instrument/History/Bar；价格是十进制字符串，币种 USD、报价单位 CURRENCY_PER_SHARE，日期为交易所 sessionDate，retrievedAt 为实际接收时间。Provider 原始结果的 asOf 暂为空，由 G10 的交易日历与闭合检查建立；不能拿 retrievedAt 充当行情时间。

三个技术候选固定为 AAPL / NASDAQ、GLD / NYSE Arca / GOLD 代理、SLV / NYSE Arca / SILVER 代理。元数据来自登记表，不假称新浪历史响应携带了这些字段。代码 ID 不向前端解析推断，未知 ID 在启动进程前拒绝；窗口限 2026 年、最多 120 个日历日、截至纽约当前日期。无跨源补齐、无 Crypto 切换。

公开接口：

- `GET /api/market/instruments`：返回三项候选、元数据来源、PENDING 资格、真实大陆网络未验证、使用状态 UNKNOWN。
- `GET /api/market/history?instrumentId=...&start=YYYY-MM-DD&end=YYYY-MM-DD`：统一工具入口，先验证窗口与资格。目前返回 422 LICENSE_BLOCKED，不向客户端泄露价格或启动外部请求。

AKShare 文档把默认序列描述为未复权，但未证明实际窗口因子/公司行动完整，因此正式 Instrument 标 UNKNOWN。正式生产白名单仍为空，G06 的手动真实组合登记也未擅自放开。适配器已经能取数，程序不会据此把本机网络结果或未知数据使用条件标成合格。后续资格核实后须修改受审查的登记记录；没有一个前端参数或环境布尔值能跳过此门槛。

2026-10-23 验收：Provider 12 项离线 Java 测试，Python 适配器 4 项与共享探测器 17 项通过。覆盖白名单、窗口、USD/份额、错误映射、空/null/HTML/未来时间/越界日期响应、缺解码依赖、禁用配置和许可阻断。显式 `SinaOnlineProbe` 通过真实 Java→Python→新浪路径逐一取得 AAPL、GLD、SLV，各 84 根日线，2026-06-25—2026-10-22；只保存 [在线请求摘要](probes/2026-10-23-g09-sina-provider.json)，未保留价格。3 项真实 HTTP 集成检查通过，G10 补入质量查询后为 4 项；组末 186 项后端全量回归通过。

资格限制仍是 G02 已记录的实际大陆原生网络、数据展示/模型输入/存储条件、完整复权和公司行动。这里完成的是技术实现与三资产在线请求，不能据此宣布真实行情 M2 验收完成。

核验来源（2026-10-23）：[Nasdaq AAPL](https://www.nasdaq.com/market-activity/stocks/aapl)、[GLD 发行方](https://www.ssga.com/us/en/individual/etfs/spdr-gold-shares-gld)、[SLV 发行方](https://www.ishares.com/us/products/239855/ishares-silver-trust-fund)、[AKShare 新浪日线说明](https://akshare.akfamily.xyz/data/stock/stock.html)。运行方式见 [适配器说明](../scripts/market/README.md)。
