# G05：Decision 保存与不可变修订

日期：2026-10-23，Asia/Taipei。依赖 [本地开发环境](local-development.md)；请求与响应沿用 [输入契约](contracts/input-and-snapshots.md) 和 [API 契约](contracts/workflow-and-api.md)。

## 已实现接口

| 请求 | 结果 |
|---|---|
| `POST /api/decisions` | 接收 DecisionDraft，返回 201 + DecisionVersion；Location 指向新 ID 的固定 version=1 |
| `GET /api/decisions/{id}` | 返回当前已提交版本，200；不存在或不属于本机主体时 404 |
| `GET /api/decisions/{id}?version=N` | 返回指定历史版本，200 / 404；非法版本参数为 400 |
| `PATCH /api/decisions/{id}` | 接收 `{schemaVersion, expectedVersion, draft, confirm}`，成功返回 200 + 新版本；期望版本不匹配为 409 / VERSION_CONFLICT |

响应保留 G03 的 envelope、X-Trace-Id 和固定错误消息。POST 每次新建独立 Decision；draftId 只作为输入关联信息保存，不是幂等键。`PATCH` 的 draft 是一份完整替换草稿，不是 JSON Merge Patch；未填写的业务字段不会从旧版本偷偷补入。

当前服务以服务端 `CurrentOwner` 提供的 `local` 为本机单用户主体。所有读取和修订 SQL 都按主体过滤；客户端 ownerId 字段被拒绝，身份请求头不参与主体选择。这不代表已实现多用户认证。

## 输入和确认规则

- DecisionDraft 必填 schemaVersion=1.0.0、draftId、非空白 inputText、inputStatus、missingFields、proposedDecision。最后一项允许显式 null，其他已提供的可选业务字段不接受 null；尚未填写时应省略该业务字段。
- 后端从实际输入重新计算 baseCurrency、portfolioSnapshotId、action、horizon、constraints、hypotheses 的缺失情况，不保存客户端任意缺失说明。NEEDS_INPUT 必须实际缺字段且客户端 missingFields 非空；READY / CONFIRMED 必须字段齐全且 missingFields 为空。不一致返回 400 及安全字段路径。
- proposedDecision 可只包含部分**完整业务对象**；例如尚未填写期限时省略 horizon，而不是传 `{value:6}`。已提供的 action、horizon、constraints、hypotheses 仍须结构合法。
- POST 的 inputStatus=CONFIRMED 是显式确认意图，时间由服务端生成。PATCH 只以 confirm 为确认开关：true 生成新 CONFIRMED 版本，缺字段时为 409 / NEEDS_INPUT；false 生成 READY 或 NEEDS_INPUT，清空 confirmedAt，即使 draft 仍声明 CONFIRMED 也不会沿用旧确认。
- `DecisionService.requireConfirmed(id, version)` 对 NEEDS_INPUT / READY 分别拒绝为 409 / NEEDS_INPUT（含缺失字段）和 INPUT_NOT_CONFIRMED；历史已确认版本仍可读取。未来建 Run 必须在冻结事务中调用它并再核对组合引用。
- 原始文本、用户假设顺序和数值字符串原样保存；假设 ID 不重复，supportStatus 固定 UNVERIFIED，禁止在输入中伪造已支持结论。
- P0 基准币种限 USD。调仓方式严格二选一；targetWeight 为 `(0,1]`，deltaPercentagePoints 为 `(0,100]`，资金与目标 ID 必须不同。比例和百分点都按规范十进制字符串传输，最多 10 位小数，拒绝尾零、指数和 JSON 浮点数，不做舍入修复。
- 严格 JSON 同时拒绝数字转字符串、字符串转整数 / 布尔、浮点数截断为整数、数字枚举，以及既有的未知字段和重复 key。

保存不调用模型、行情或研究流程。G05 的 READY / CONFIRMED 仅表示输入完整且用户确认；尚未证明 portfolioSnapshotId 真实存在或归属正确，也未证明资金标的是现金、目标在获准白名单或现金足够。组合保存、元数据和创建 Run 的联动检查分别随 G06 / G09 / G17 实现。不能由不透明资产 ID 的文字前缀猜测资产类型。

## 数据和事务

V2 创建 `decisions` 与 `decision_versions`。前者保存 owner、当前版本和创建 / 更新时间；后者以 `(decision_id, version)` 唯一保存完整版本 JSON、draftId 和 UTC 毫秒时间，外键指向 Decision。ID 列使用 ASCII 二进制排序，区分大小写；金融值在 JSON 中保持字符串。已应用的 V1 未修改。

修订使用 `UPDATE ... WHERE owner_id=? AND decision_id=? AND current_version=expectedVersion` 原子递增，并在同一 Spring 事务中插入不可变版本。竞争者在胜方提交后更新零行，返回 409；版本写入失败会回滚头指针，不能留下“当前版本已递增但内容不存在”的状态。当前读取用单条 JOIN 取得已提交头指针对应内容，历史读取按明确版本。生产 Mapper 无更新 / 删除版本内容的方法。

数据库连接、事务建立和瞬时访问故障映射为 503 / PERSISTENCE_UNAVAILABLE，不公开异常原因或连接凭据；意外数据完整性错误保持脱敏 500，不误报成用户版本冲突。列表 / 游标、Run 引用和报告历史接口属于后续任务。

## 最小试用

先 `scripts/dev.ps1 up`、`scripts/dev.ps1 run`，然后在根目录另一终端执行：

```powershell
$example = Get-Content -Raw -Encoding UTF8 docs/contracts/examples/gold-silver-cash.json | ConvertFrom-Json
$draft = $example.draft
$saved = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/decisions `
  -ContentType 'application/json; charset=utf-8' -Body ($draft | ConvertTo-Json -Depth 20)
$decisionId = $saved.data.decisionId
Invoke-RestMethod "http://127.0.0.1:8080/api/decisions/$decisionId`?version=1"

# 补齐两项缺失输入，明确确认生成第 2 版；第 1 版仍可读取。
$draft.proposedDecision | Add-Member -NotePropertyName horizon -NotePropertyValue $example.decision.horizon
$draft.proposedDecision | Add-Member -NotePropertyName constraints -NotePropertyValue $example.decision.constraints
$draft.inputText = $example.decision.inputText
$draft.inputStatus = 'READY'
$draft.missingFields = @()
$revision = @{schemaVersion='1.0.0'; expectedVersion=1; draft=$draft; confirm=$true}
Invoke-RestMethod -Method Patch -Uri "http://127.0.0.1:8080/api/decisions/$decisionId" `
  -ContentType 'application/json; charset=utf-8' -Body ($revision | ConvertTo-Json -Depth 20)
```

此样例的标的和组合 ID 为 SYNTHETIC 占位，仅演示 G05 的保存与修订，不能执行真实研究。

## 验收

JDK 17.0.12 / Boot 4.0.9 / MyBatis 4.0.1 / Jackson 3.1.7，使用 G04 的真实 MySQL 8.4.13 与 Redis 7.2.16，不依赖外网、模型或行情 Key。

```powershell
Set-Location backend
.\mvnw.cmd -o -B -ntp test
```

结果：**95 项通过，0 失败、0 错误、0 跳过**。其中 Decision 真实集成 48 项、基础设施和 API 回归 47 项。完整回归于 19:02 完成；19:05—19:06 另行执行以下真实接口与重启检查；最终移除校验器中的重复列表拷贝后，于 19:08 再跑 48 项 Decision 集成检查，全部通过。

- V1 校验和 `1860727987` 保持不变；V2 `decision versions` 成功，校验和 `-726693985`，应用重复启动无 pending migration。
- G01 的 NEEDS_INPUT 贯穿草稿经真实 POST 保存，补齐后 PATCH 确认为第 2 版；重启后端及 MySQL 后，两份版本和当前指针逐字段一致。重启前后共 6 个成功响应通过冻结 DecisionVersion JSON Schema（jsonschema 4.26.0）的独立校验。

- Decision HTTP 集成：保存、当前 / 指定版本读取、确认与重新确认、服务端缺失项、未确认研究保护、两种调仓方式、主体隔离和 ID 大小写。
- 两个 HTTP 请求同时修订 expectedVersion=1：恰好一个 200、一个 409 / VERSION_CONFLICT，当前为第 2 版，数据库恰好两份版本，原第 1 版逐字段不变。
- 仅在测试配置注册的 MyBatis 拦截器对指定 draft 注入版本 INSERT 失败：真实 HTTP 创建回滚新 head，修订回滚版本递增，旧内容可读，修复后同 expectedVersion 可重试成功。未用 H2 / 模拟数据库，不创建临时生产接口或数据库触发器。
- 测试以 UUID 标记，仅清理自己创建的两表记录，不清库、不重置迁移。非法请求不落库，失败响应不回显拒绝值或注入错误原因。
- 合成验收数据已按本次 UUID 清理；验收后两领域表均为 0 行，迁移历史保留，两个开发容器仍健康，临时后端已停止。真实 `.env`、临时校验脚本和报告留在忽略路径，未提交。
