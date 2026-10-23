# G02 数据源与模型能力矩阵

更新时间：2026-10-23（Asia/Taipei）。基线：[G01 契约](contracts/README.md)。执行入口：[探测脚本](../scripts/probes/README.md)。

**本组探测代码和在线记录已交付，正式行情源资格尚待确认。**原定股票/黄金白银 ETF 范围保留；首选技术候选为 **AKShare 固定解码算法 + 新浪国内历史数据入口**，不需要行情 Key。用户允许在这条路径不可用时考虑币安 Crypto 后备，尚未触发资产范围切换。

用户要求实际使用不翻墙。当前报告通过禁用环境/系统显式代理验证“本机无显式代理可请求”；没有真实大陆运营商环境、出口地区或透明隧道排除证据，不能写成“中国大陆可用已验收”。`networkContext.mainlandAccessVerified` 均为 false，需在实际用户网络执行相同命令补验。

## 技术对比

| 来源 | Key / 账号 | 本机关闭显式代理的实测 | 历史覆盖与口径 | 当前决定 |
|---|---|---|---|---|
| 新浪财经 + AKShare 1.19.1 解码 | 不需要行情 Key | AAPL、GLD、SLV 页面与历史资源均 200，历史解码通过 | 三者均取得 2026-06-24—2026-10-22 的 85 根日 K，正值/OHLC/去重检查通过 | 原范围的首选技术候选；元数据、复权、使用条件和真实大陆网络待核对 |
| 百度财经 / 百度股市通 | 页面无需 Key；数据入口实际要求未确认 | 三个页面均 200；页面使用的历史接口三个标的均 403；按正常 Accept/Origin/Referer 复核仍 403 | 没有成功取得可验证日 K；403 原因未确认，不推断一定需要哪一种签名/账号 | 不作为当前 Provider；保留请求失败记录，无请求校验绕行 |
| 同花顺公开页面 | 页面账号要求未确认 | 三个 HTTPS 页面均返回 302，未跟随；重定向 host 为同花顺或相对地址 | 页面访问不证明历史接口；尚未验证直接日 K | 不据 302 推断没有该资产，也不等同于已确认反爬；本探测未形成可计算历史输入 |
| 同花顺 iFinD 官方接口 | 官方 access_token / 账户数据权限 | 已查官方接入文档；当前未配置 Token，未请求账户行情 | 官方提供历史 OHLC 接口；当前账户对美股及两种 ETF 的覆盖、额度、复权与许可未验证 | 可选账号路径，不符合当前免行情凭据偏好 |
| Twelve Data | 需要 API Key | 当前未配置；三个标的均 0 次外部请求，PROVIDER_NOT_CONFIGURED | 账户实际覆盖与权限未确认 | 可选诊断，不作为默认依赖 |
| Yahoo | 早期技术诊断未用 Key | 早期通过系统默认代理规则请求成功；**不满足本轮大陆网络验收依据** | 早期三个标的各 85 根日 K，含 adjclose 字段，未核实复权含义 | 仅诊断；撤回默认免 Key 方案判断，不参与正式白名单 |
| 币安公开现货数据入口 | 无 Key；未登录/未交易 | data-api.binance.vision 的 BTCUSDT / ETHUSDT 均 200 | 两者 2026-08-20—2026-10-22 各 64 根闭合 UTC 日 K | 用户允许的条件后备，仍需大陆网络/使用条件及 Crypto 契约调整 |
| DeepSeek 模型 | 使用本机既有环境变量 | 禁用显式代理后 200，约 781ms，finish_reason=stop | json_object + 本地精确结构校验通过；usage=55 输入/12 输出/67 总 Token | 模型最小连接通过；不等于研究角色验收或服务端严格 Schema 保证 |

页面和请求记录仅证明所列时间、机器和入口的结果，不推断全部用户、全部日期或其他资产的覆盖。来源跨站页面可见性也不能代替程序接口验收。

## 候选标的与元数据

G02 固定探测候选为 AAPL、GLD、SLV，均沿用 P0 的 USD 计价和美国交易日研究范围。此表是**待资格确认的候选清单**，正式 productionAllowlist 为空，不允许将合成 DEMO 代码注册到真实源。

| 角色 / 标的 | 元数据预期（尚须与 Provider 固定绑定） | 核验依据 |
|---|---|---|
| 股票 AAPL | NASDAQ、USD/股、America/New_York、美国股票交易日 | [新浪 AAPL 页面](https://stock.finance.sina.com.cn/usstock/quotes/AAPL.html)确认代码与 NASDAQ；币种/日历仍需在资产注册时单独验证 |
| 黄金代理 GLD | 黄金交易型信托/ETF 分类、NYSE Arca、USD/股、美国股票交易日 | [发行方 GLD 页面](https://www.ssga.com/us/en/individual/etfs/spdr-gold-shares-gld)；不能标成现货金价 |
| 白银代理 SLV | 白银交易型信托/ETF 分类、NYSE Arca、USD/股、美国股票交易日 | [发行方 SLV 页面](https://www.ishares.com/us/products/239855/ishares-silver-trust-fund)；不能标成现货银价 |

Sina 历史 payload 本身没有完整交易所、币种、类型、时区字段，报告明确为 null。不能因为取到了列数据，就默认所有元数据已从响应验证。观察窗口不含当前美国日历日期；周末与休市、提前收市、拆股、分红等完整质量规则在 G10 实现。

AKShare 文档将新浪的默认美股日线描述为未复权，并提供前复权选择和因子；探测只解码默认序列，没有取因子、前向填充或执行因子响应。尚未核实窗口内公司行动及分红含义，`adjustmentVerified = false`；不能称总收益，也不能直接用于遇拆股的窗口。[AKShare 新浪日线说明](https://akshare.akfamily.xyz/data/stock/stock.html)、[维护方实现](https://github.com/akfamily/akshare/blob/main/akshare/stock/stock_us_sina.py)。

## 额度、错误与使用条件

| 项目 | 已有证据 | 未确认 / 后续动作 |
|---|---|---|
| 新浪额度与 429 | 本轮小量请求均成功；无公开额度头、无真实 429 | 未主动压测；初始串行少量调用，后续取数按共享预算限制，不将“免费”当作无限额度 |
| 百度 403 | 正常无凭据请求被拒绝，未重试放大、未伪造会话或反向构造请求校验 | 若未来获得官方允许的接入方式，再更换适配器；当前不继续猜签名 |
| iFinD 凭据和接口 | [官方使用流程](https://quantapi.51ifind.com/gwstatic/static/ds_web/quantapi-web/help-center/deploy.html)说明 refresh_token/access_token；[官方手册](https://quantapi.51ifind.com/gwstatic/static/ds_web/quantapi-web/help-center/manual.html)列出历史行情 API | 当前没有 Token，不宣称有账户覆盖、存储或模型输入权限 |
| Twelve Data credits | [官方 credits 说明](https://support.twelvedata.com/en/articles/5615854-credits)及[额度头说明](https://support.twelvedata.com/en/articles/5713553-control-over-api-usage) | 具体账户额度、ETF 覆盖和条款适用性待账户验证 |
| 软件许可 vs 行情使用 | [AKShare MIT 许可](https://github.com/akfamily/akshare/blob/main/LICENSE)适用于软件；[新浪页面说明](https://stock.finance.sina.com.cn/usstock/quotes/GLD.html)表明行情来自合作方、面向信息获取 | 据此不能推断获准公开展示、永久保存/回放或向外部模型传送全部行情；三项均保留 UNKNOWN |
| 同花顺页面内容使用 | [同花顺法律声明](https://www.10jqka.com.cn/ia/pass_buck.php)有内容使用与授权约定 | 网站可看与 API 数据许可分别核实；不据免费网页自行承诺外部分发 |
| 模型 JSON 与数值边界 | [DeepSeek JSON 文档](https://api-docs.deepseek.com/guides/json_mode/)；本次精确 JSON 小对象成功 | 没有发送市场数据、用户组合或敏感信息；真实研究与 Schema、错误分类仍在 G24 及后续任务验收 |
| 币安数据边界 | [官方公开行情入口](https://github.com/binance/binance-spot-api-docs/blob/master/faqs/market_data_only.md)不要求 Key；本轮只读现货 K 线 | 本机通过不证明大陆可用；若启用，新增 CRYPTO 类型、USDT 计价与 365/UTC 日历契约，不能把 USDT 当 USD 或把稳定币储备当美元现金 |

本组统一使用市场 8 秒、模型 30 秒单次 socket 超时，无自动重试；401/403/429、截断、空模型输出、非法 JSON/OHLC 等由离线检查覆盖。没有线上 429、长时间故障、完整 Run deadline 或生产吞吐测试证据，这些分别留给 G20/G21/G39。

## 已保存记录与下一步

有效的当前基准记录：

- [新浪页面与资源](probes/2026-10-23-sina-direct.json)、[新浪解码日线](probes/2026-10-23-sina-history-direct.json)。
- [百度页面](probes/2026-10-23-baidu-direct.json)、[百度历史接口](probes/2026-10-23-baidu-history-direct.json)、[同花顺页面](probes/2026-10-23-ths-direct.json)。
- [DeepSeek 直连请求](probes/2026-10-23-deepseek-direct.json)、[币安条件后备](probes/2026-10-23-binance-direct.json)、[Twelve Data 缺配置](probes/2026-10-23-twelvedata.json)。

早期 [Yahoo](probes/2026-10-23-yahoo.json) 和 [DeepSeek](probes/2026-10-23-deepseek.json) 的请求使用系统默认代理规则，记录补充了该上下文，仅留对比。所有文件只保存脱敏概要，没有 Key、原始行情、模型响应文本或会话数据。

后续先完成三个资格项：实际大陆原生网络重跑；上游对本地展示、模型输入和保存/回放的使用条件；固定资产元数据、交易日历及复权口径。G02 暂记“进行中（探测实现已提交，正式行情资格待确认）”；可以继续 G03 的通用响应与异常基础，不能据本组宣布 M2 或生产 Provider 已完成。
