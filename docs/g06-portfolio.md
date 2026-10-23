# G06：手动组合与不可变快照

日期：2026-10-23，Asia/Taipei。依赖 [G04 本地环境](local-development.md) 和 [G01 输入 / 哈希契约](contracts/input-and-snapshots.md)。

## 接口与数据

| 请求 | 行为 |
|---|---|
| `POST /api/portfolios` | 接收 schemaVersion、name、baseCurrency、valuationBasis、totalValue、asOf、dataMode、positions；201 + PortfolioSnapshot，Location 指向第 1 版 |
| `GET /api/portfolios/{id}` | 当前已提交的快照，200 / 404 |
| `GET /api/portfolios/{id}?version=N` | 指定历史快照，200 / 404；非法版本参数为 400 |
| `PATCH /api/portfolios/{id}` | `{schemaVersion, expectedVersion, positions, asOf}`；200 + 新快照，版本过期为 409 / VERSION_CONFLICT |

`name` 必填，首版限 200 字符，保存在组合定义中，不进入 PortfolioSnapshot 或 contentHash。PATCH 完整替换持仓与 asOf，保持原币种、估值基准、总值和数据模式；改变这些元数据应另建组合。每次修订都有新的 snapshotId 和递增版本，即使内容相同。内部 `PortfolioService.getSnapshot(snapshotId)` 按本机主体读取原快照，供后续冻结输入使用。

V3 创建 `portfolios` 和 `portfolio_snapshots`：头记录 owner、名称、当前版本及 UTC 创建 / 更新时间；快照表保存独立 ID、版本、内容哈希和完整 JSON。数据库限制 `(portfolio_id, version)` 唯一，并以外键关联组合。应用不更新旧快照；条件 UPDATE 头指针与 INSERT 快照同事务，失败一起回滚。读取只返回已提交版本。

沿用服务端 `CurrentOwner=local`，客户端不指定主体，未知 ownerId 被拒绝，身份请求头不生效。当前 / 历史 / snapshotId 查询和修订均校验主体；不属于该主体时为 404。ID 区分大小写。

## 权重、资金与口径

- 金融数值必须是规范十进制字符串，无指数、前导 / 尾随冗余零或负零，不接收 JSON number。权重范围 `[0,1]`，最多 10 位小数；BigDecimal 精确合计为 1，无浮点容差。
- 拒绝重复标的和币种混合；P0 基准币种限 USD，持仓币种还须匹配服务端登记的标的币种。位置数组 1—4 项，最多 3 个非现金标的，须显式包含 USD 现金作为资金来源。
- 现金项可以为 `"0"`，组合仍可保存；后续实际增配须再检验正现金是否足够。保存不推算用户的调仓数量，不因现金不足自动改动权重。
- NORMALIZED 的 totalValue 必须显式为 `"100"`，是归一化示例总值；ABSOLUTE 必须为正值，最多 18 位整数 / 10 位小数，保留精度、不舍入，也不验证真实账户余额。
- asOf 是用户组合快照时间，不是抓取时间。要求有效 UTC RFC3339、固定毫秒与 Z，拒绝无效日期、年份 0000、偏移形式以及会被解析器静默规范化的闰秒。
- 权重总和 / 重复项为 400 / INVALID_WEIGHTS，币种冲突为 CURRENCY_MISMATCH，缺现金为 INSUFFICIENT_FUNDING，未知标的为 UNSUPPORTED_INSTRUMENT。格式 / 精度错误为 VALIDATION_ERROR；数字到字符串等类型错误为 INVALID_REQUEST，均不写入数据。

## 标的登记与数据模式边界

G02 的正式行情白名单仍为空，本组不自行确认来源资格。`PortfolioInstrumentCatalog` 是手工组合输入身份登记，不是完整行情元数据或 Provider 许可；精确查询登记 ID，不从 ID 字符串前缀猜类型。

| 登记 ID | 类型 | 组合数据模式限制 |
|---|---|---|
| `CASH:USD` | CASH | 所有已定义模式，仅表示用户输入的美元现金 |
| `ETF:SYNTHETIC:DEMO_GOLD:USD` | ETF / 黄金代理示例 | 仅 SYNTHETIC |
| `ETF:SYNTHETIC:DEMO_SILVER:USD` | ETF / 白银代理示例 | 仅 SYNTHETIC |
| `STOCK:SYNTHETIC:DEMO_STOCK:USD` | STOCK 示例 | 仅 SYNTHETIC |

这些 DEMO ID 来自合成契约口径，不能注册到真实行情源；SYNTHETIC 标的放入 LIVE / CACHE / REPLAY 均被拒绝。当前股票 / ETF 手动演示应明确使用 SYNTHETIC；真实非现金资产待 G09 按资格结果登记。不能借修改 dataMode 把同一快照冒充真实数据。

保存不访问行情、模型或外网。模式随快照固定并进入哈希；这不代表已经实现 G36 的回放、缓存降级或完整模式展示。币安 Crypto 未进入本组，也未将 USDT 当 USD。

## 内容哈希

contentHash 为 `sha256:<小写64位hex>`，hashAlgorithm 为 `investlens-canonical-json-v1`。只覆盖 schemaVersion、hashAlgorithm、baseCurrency、valuationBasis、totalValue、asOf、dataMode、positions；排除组合 ID、快照 ID、版本及名称。

持仓先按 instrumentId 排序，其他 JSON 对象按 Unicode 码点排列 key。UTF-8 无 BOM、无空白、无 Unicode 归一化、不转义 `/`；控制字符使用约定短转义或小写十六进制，拒绝未配对代理码点与浮点 JSON number。Java `CanonicalJson` 复现 G01 Python 字节规范。

相同内容即使创建为不同组合、换名称或打乱持仓顺序，哈希仍相同；修改权重、asOf、数据模式或估值参数会改变哈希。哈希不是归属证明，也不代表真实数据资格。

## 试用

先按 G04 启动两个依赖和 dev 后端，在根目录另一终端执行：

```powershell
$request = Get-Content -Raw -Encoding UTF8 backend/src/test/resources/portfolio-create.json | ConvertFrom-Json
$created = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/portfolios `
  -ContentType 'application/json; charset=utf-8' -Body ($request | ConvertTo-Json -Depth 15)
$portfolioId = $created.data.portfolioId
$created.data  # SYNTHETIC、NORMALIZED=100、20/10/70
$request.positions[0].weight = '0.3'
$request.positions[2].weight = '0.6'
$revision = @{schemaVersion='1.0.0'; expectedVersion=1; positions=$request.positions; asOf=$request.asOf}
Invoke-RestMethod -Method Patch -Uri "http://127.0.0.1:8080/api/portfolios/$portfolioId" `
  -ContentType 'application/json; charset=utf-8' -Body ($revision | ConvertTo-Json -Depth 15)
Invoke-RestMethod "http://127.0.0.1:8080/api/portfolios/$portfolioId`?version=1"
```

旧快照仍为 20/10/70；新快照为 30/10/60。Decision 保存原 snapshotId 后，修订组合不会改写其引用；组合归属、模式与确认版本在 G17 创建 Run 时仍须完整联动检查，本组不创建 Run。

## 验收

在 `backend/` 目录使用 JDK 17.0.12、Boot 4.0.9、真实 MySQL 8.4.13 / Redis 7.2.16 执行 `mvnw.cmd -o -B -ntp test`：**144 项通过，0 失败、0 错误、0 跳过**。其中 Portfolio 真实集成 45 项、规范 JSON 4 项、既有回归 95 项；全套于 2026-10-23 19:22（Asia/Taipei）完成。

- 20/10/70 的 Java contentHash 与 G01 Python 固定值完全相同：`sha256:40e2b229e37f3fcb400a0e3d26db1f0d9826af57518d5b769388b5ac5aa81a54`；名称 / ID / 持仓顺序不影响结果，权重 / 时间 / 模式改变会改变哈希。
- 真实 HTTP 创建、修订、当前 / 历史读取、主体隔离、精确小数和 ABSOLUTE 值保留均通过；超额 / 不足 / ±0.0000000001 偏差、负权重、混合币种、重复项、缺现金、未知标的和不合法输入均被拒绝。
- 两个 HTTP 修订竞争同一期望版本，恰好一个 200、一个 409；快照恰好两份，旧内容不变。仅测试配置的 MyBatis 拦截器注入快照 INSERT 故障，证实创建 / 修订的头指针和内容一起回滚，原 expectedVersion 可成功重试。无 H2 或数据库替代实现。
- 创建真实 Decision 引用第 1 版 snapshotId 后，再修订组合，Decision 引用和内部 snapshotId 读取内容均不变；不把这个用例当作尚未实现的 Run 冻结验收。
- 规范 JSON 单元检查 Unicode 码点顺序、组合字符不归一化、控制字符小写转义、数组顺序、大整数和浮点 / 未配对代理码点拒绝。
- 19:23—19:24，实际 dev 后端保存并修订组合，重启后端及 MySQL 后，两份快照、当前指针和哈希逐字段不变。共 6 个真实成功响应通过冻结 PortfolioSnapshot Schema，并由 G01 Python 参照独立重算哈希通过。
- V3 `portfolio snapshots` 成功，校验和 `1221404562`，重复启动无 pending migration；V1 `1860727987`、V2 `-726693985` 保持不变。测试及重启样例仅按各自 UUID 清理，验收后四张领域表均为 0 行，未重置迁移。
- 临时后端已停止，两个开发容器仍健康；`.env`、临时校验脚本和输出均被忽略。未添加第三方依赖或任何运行时外网请求，未变更 G02 的大陆网络 / 数据源资格结论。
