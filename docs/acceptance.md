# P0 验收矩阵（G38—G39）

日期：2026-10-24，Asia/Taipei。G38 测试提交：`332704d`；生产应用代码及镜像来自 G37（`7a4e785`），本轮没有修改生产计算/许可策略。测试脚本执行时包含本轮待提交改动，运行回执记录 HEAD 和工作区是否有改动；不能把 HEAD 自动当成镜像构建来源。

**发布判断：技术回归通过，真实行情/在线研究与新物理环境尚未通过，M2/M3/M4 不作全部完成声明。** “通过”仅适用于该行列明的测试范围，不代表所有场景都已在线验证。

## 执行环境与汇总

| 项目 | 本轮结果 |
| --- | --- |
| 后端 | JDK 17，已缓存 Maven 3.10.0，真实开发 MySQL/Redis；345 项，0 失败/错误/跳过 |
| 前端 | Node 24.16.0 / npm 11.13.0；36 项，0 失败；TypeScript 与 Vite 生产构建通过 |
| 验收脚本 | Python 3.10；8 项单元测试通过，覆盖 URL/项目/输出路径边界、故障目标不匹配时拒绝、敏感值检查不回显 |
| 契约 | G01 联动样例、24 项拒绝、哈希不变量、G13/G14/G17 导出及 G38 发布报告 Schema 通过 |
| 四容器 | Docker Engine 29.9.2 / Linux 容器；正常部署与全新空卷部署均通过本机合成闭环 |
| 有界敏感值审计 | 当前本地凭据未出现在已跟踪文件、构建/实际服务静态资源、抽样 API 响应或近期前后端日志中 |

日志、JSON、回执和截图均保留在忽略的 `backend/target/`，不提交数据库、Key、用户输入和运行报告。没有在线模型或真实投资内容外发。

## A01—A14 逐项核对

| 编号 | 状态 | 已执行与边界 |
| --- | --- | --- |
| A01 输入澄清 | 通过（合成） | Decision/Portfolio 集成测试与输入工作台：目标权重/百分点分开、现金来源与期限必填、ETF 代理明确；不把歧义输入直接确认为可运行 |
| A02 真实历史行情及时间 | **未通过** | 质量/元数据测试通过，正式源白名单仍为空；尚无许可、复权/公司行动和大陆原生网络均合格的真实链路 |
| A03 后端金融数值 | 通过（合成/固定样本） | FinancialFormulas、Metric、Scenario、ReportNumbers 测试；公式/输入哈希可追踪、展示值由后端产生，模型文本数字被拒绝；不等于真实行情验收 |
| A04 当前 Run 引用与跳转 | 通过（合成） | ResearchAcceptance、ReportStructure/Publication、前端 reports 测试；引用必须存在于冻结 Run，浏览器跳转按原证据定位；真实引用未执行 |
| A05 缺失宏观不造事实 | 通过 | 冻结缺口与报告假设均保留 UNVERIFIED，直接冲击结果不被当成宏观验证 |
| A06 同快照 Bull/Bear | 通过（本机 stub） | 新增完整非空观点用例及 RoleReview 测试，条件/失效/未知项完整；在线双角色尚未执行 |
| A07 Judge 可拒绝判断 | 通过（本机 stub/降级） | Judge 引用已有观点且允许 INSUFFICIENT_EVIDENCE；禁用/失败时明确 PARTIAL，不假称模型成功 |
| A08 不调用模型重算 | 通过 | Scenario 集成与新跨模块用例：变更显式冲击仅产生独立情景，原报告不变、模型调用仍为四次 |
| A09 同键重试 | 通过 | RunCreation 并发、事务回滚与 HTTP 重试；实际容器相同键返回同一 Run，异请求冲突 |
| A10 刷新/断线/重启 | 通过（列明范围） | 前端 progress、后端事件与租约恢复；实际容器重启 MySQL/后端后报告和事件不变。QUANT 后恢复通过时钟推进/令牌测试，未在真实长耗时在线调用中杀进程演练 |
| A11 429/超时有界 | 通过（本机故障注入） | RetryAfter、CallBudget、WorkflowResilience、LlmClient，包含全局重试/修复、原 deadline、已取消令牌、账户配额；未压测真实供应商 |
| A12 新环境 README | **部分通过** | 本机全新 Compose 网络/空命名卷/随机独立凭据，从 V1 迁移至 V16 后闭环、Redis 故障和重启通过；复用已有镜像。新物理机器、无镜像/依赖缓存的源码构建和大陆原生网络未执行 |
| A13 明确模式与原时间 | 通过 | LIVE/CACHE/REPLAY/SYNTHETIC 标识、缓存拒绝隐式回源、重放保留源时间；禁止合成改标真实。发布演示只标 SYNTHETIC，不称实时行情 |
| A14 不泄露 Key | 通过（有界审计） | 网关/错误处理测试，加当前凭据字面值扫描；审计限当前 `.env`/环境中至少十二字符的命名凭据、当前构建和各最近千行应用日志，不代表全 Git 历史扫描或渗透测试 |

## 故障覆盖及新增跨模块断点

| 场景 | 证据 |
| --- | --- |
| 领取竞争、旧令牌写入、过期任务及损坏检查点 | WorkflowClaim/Checkpoint/Resilience；QUANT 输出不重算，调用预算不重置 |
| 已有模型结果晚到取消 | ResearchAcceptance：VALIDATE 已算完后 HTTP 取消，晚到发布被拒绝，报告/重复终态均未产生 |
| 原子报告事务回滚 | ReportPublication：报告、结论、引用、Run 和终态事件一起回滚，相同结果重试不重复发布 |
| 模型未知/跨 Run 引用、伪造数字和有限修复 | RoleReview、ReportStructure、ReportNumbers、ReportPublication；失败不伪造成功报告 |
| SSE 重复、乱序、缺口、错误/跨 Run/未来游标 | RunEvent、前端 progress；真实 Nginx 验证 Last-Event-ID 优先、顺序、终态关闭 |
| Redis 实际停止 | G37 部署与本轮空卷部署：readiness、原报告、新合成发布继续可用，最后恢复或停止临时项目 |
| MySQL/后端实际重启 | 原报告内容及完整事件重读一致；未清卷、未靠缓存恢复 |
| 故障脚本目标错配 | 新脚本先核对 Compose 前端端口，错配时在 stop/restart 之前拒绝；输出仅允许忽略目录，不覆写源码 |

## 空卷部署实测

执行 `python -B scripts/check-clean-deployment.py`：

- 临时项目 `investlens-acceptance-6b5e47f93f22`，端口 `127.0.0.1:18080`，运行前不存在同名 MySQL 卷。
- 新生成三个独立随机凭据；不读取/复用当前 `.env` 密码，屏蔽 Compose 相关宿主覆盖变量，模型和行情均关闭。
- 使用已有应用镜像，四服务首次健康启动、16 条迁移成功；幂等 Run、报告、后端情景值、历史、JSON/SSE、停止 Redis 后新发布、MySQL/后端重启均通过。
- 最终只执行临时项目 `down`（没有 `-v`），移除临时容器/网络；命名卷 `investlens-acceptance-6b5e47f93f22_mysql_data` 与忽略目录中的凭据和回执保留，可人工按相同项目恢复。
- 原 `investlens` 部署仍在 `127.0.0.1:8080`，`investlens-dev` 开发库也保持不变。
- 回执：`backend/target/investlens-acceptance-6b5e47f93f22/report-receipt.json`；合成 Run `run_488f6287-b263-45db-949d-19d086f2c6fd`，仅存在于该临时库。

这是真实空数据库验证，**不是新物理环境或首次冷构建**。脚本每次使用新项目，故障只作用于临时服务；不自动清理任何数据卷。

## 复现命令

先按 README 启动开发依赖与正常部署。开发测试期间不要额外启动会领取同库任务的后端调度实例。

```powershell
Set-Location backend
.\mvnw.cmd -B -ntp test
Set-Location ../frontend
npm test
npm run build
Set-Location ..
python -B -m unittest discover -s scripts/tests -v
python -B docs/contracts/validate.py
python -B scripts/check-calculation-contracts.py
python -B scripts/check-workflow-contracts.py
python -B scripts/check-clean-deployment.py
python -B scripts/check-deployment.py --label G39 --output backend/target/g39-deployment-report.json
python -B scripts/check-delivery.py --receipt backend/target/g39-deployment-report-receipt.json
```

契约脚本需要 `jsonschema`；普通 Docker 启动不要求宿主 Python/Java/Node。空卷脚本要求 G37 应用镜像已构建，默认临时端口 18080；冲突可用 `--port`。敏感值审计只读，停止/重启属于显式 `--faults`/`--restart` 冒烟选项。

## 必须补齐的正式验收

1. 源内容许可、复权/公司行动和目标网络资格通过后，按 [G38](g38-research-acceptance.md) 执行获准真实数据链路，登记源时间、样本、输入哈希和实际调用次数。
2. 用户显式配置并启用自己的模型 URL，完成真实冻结输入下的四次在线审查；不把连接探测或本机 stub 当在线完成。
3. 在新的仅有 Docker 的目标机器按 README 从源码冷构建、启动、端到端演示；登记系统/网络/镜像 digest/迁移/结果，失败如实记录，不以本机缓存代替。
4. 上述通过后才可以关闭相应里程碑，不在本次修改许可状态或宣称 P0 全部通过。
