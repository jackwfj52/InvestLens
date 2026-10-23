# G07：Vue 工作台与通用 API 客户端

日期：2026-10-23，Asia/Taipei。范围：前端工程、工作台路由 / 布局、真实服务连接状态、JSON API 客户端及开发代理。决策 / 组合输入表单属于 G08，研究和报告仍按后续任务实现；页面不展示虚构持仓、行情或研究结果。

## 启动与构建

推荐 Node.js 24 LTS；工程接受 Node 22.18+（22 系列）或 24+。使用 npm 和已提交的 package-lock.json，不混用其他包管理器生成锁文件。

根目录先启动 G04 的依赖和后端：

```powershell
.\scripts\dev.ps1 up
.\scripts\dev.ps1 run
```

另一个终端：

```powershell
Set-Location frontend
npm ci
npm run dev
```

打开 `http://127.0.0.1:5173/workbench`。Vite 仅监听回环地址，5173 被占用时明确失败，不静默换端口。根地址跳转工作台，未知页面显示 404 和返回链接，工作台支持直接刷新。

如果后端端口不同，将 `frontend/.env.example` 复制为已忽略的 `frontend/.env.local`，调整 `DEV_BACKEND_TARGET` 后重启 Vite。默认代理 `/api` 和 `/actuator/health/readiness` 到 `http://127.0.0.1:8080`，路径不重写；不开放其他 Actuator 代理。

```powershell
npm run typecheck     # Vue SFC、业务 TS、测试、代理脚本和 Vite 配置
npm run build         # 包含 typecheck，输出 dist/
npm test              # 20 项本地测试，无后端依赖，包含本机 HTTP fixture
npm run test:proxy    # 需要实际 Vite 和后端，检查真实代理
```

`npm run preview` 在 `127.0.0.1:4173` 预览构建文件，**不包含后端代理**；完整生产反向代理属于 G37。开发和后端命令在各自终端用 Ctrl+C 停止。

## 依赖与中国大陆网络

锁定 Vue 3.5.44、Vue Router 4.6.4、Vite 8.3.5、@vitejs/plugin-vue 6.0.10、TypeScript 6.0.3、vue-tsc 3.3.13、@types/node 24.19.2。版本与兼容范围核对 npm 官方注册表；路由维持 v4，暂不引入 Pinia 或其他状态库。

页面只使用本地打包的脚本、CSS 和系统字体，不依赖外部字体、图片 CDN、分析服务或模型接口。所有应用请求为同源相对路径，不要求用户翻墙。前端不读取根 `.env`，不包含行情 / 模型凭据；`DEV_BACKEND_TARGET` 只供开发服务器使用，不以 `VITE_` 变量传给浏览器。

首次 `npm ci` 需要取得依赖；本机从官方 npm 注册表安装成功不等于大陆原生网络已完成验收。目标环境可使用获准的内网仓库，或在同操作系统 / 架构的获准环境准备 npm 缓存后执行 `npm ci --offline`。锁文件保留 integrity，不静默改用未知镜像或删除校验信息。`dist/` 构建后无 npm 下载依赖；正式部署配置和新环境验收留给 G37 / G39。

工具行为依据：[Vite 入门](https://vite.dev/guide/)、[开发代理](https://vite.dev/config/server-options.html#server-proxy)、[Vue TypeScript 指引](https://vuejs.org/guide/typescript/overview.html)、[Vue Router](https://router.vuejs.org/guide/)。

## 客户端契约与复用

读取 WealthFlow 的 `frontend/src/services/apiClient.ts` 后保留其 fetch 工厂、JSON 包装解析和类型化错误思路，按 [InvestLens API 契约](contracts/workflow-and-api.md) 重写；原项目未修改，未迁入 React、Ant Design、旧 `/api/v1` 或 `code=0` 协议。

`src/services/apiClient.ts` 的 `apiClient` 默认同源 `/api`。`get / post / patch / delete / request` 返回 `ApiResult<T>`，其中 data 是业务内容，status、code、traceId、timestamp、location、retryAfter 保持可读：

```typescript
const result = await apiClient.post<Run>('/decisions/one/runs', body, {
  signal: controller.signal,
  headers: { 'X-Custom': 'value' },
  idempotencyKey: stableUserActionKey,
})
// result.status === 202 表示任务已受理；并不表示研究完成。
```

示例只展示未来 Run 调用方式，当前尚无 Run 端点。

- `new Headers(options.headers)` 支持对象、Headers 和二元组；只补充缺失的 Accept / Content-Type，不覆盖调用方自定义头。
- HTTP 201 保留 Location；202 的 OK 和 RUN_IN_PROGRESS 都为正常结果。RUN_IN_PROGRESS 出现在其他 HTTP 状态时按协议错误处理，不把普通成功与仍在执行混淆。
- 400 / 409 / 429 / 503 等抛 `ApiError`，保存字符串业务码、真实 HTTP 状态、安全字段错误、追踪和 Retry-After，不公开任意错误 data。即使 HTTP 200，业务错误码也不能当作成功。
- 校验 envelope 七字段和 schemaVersion=1.0.0；泛型 T 是调用方的 TypeScript 约定，不替代各业务对象的运行时校验。HTML 网关响应、坏 JSON、其他 Schema 和非 envelope（含 204 空响应）返回固定 INVALID_RESPONSE，不回显原始内容。
- 网络失败采用固定消息。fetch 和响应体读取阶段的 AbortError 保持原异常；AbortSignal 原样传入，不改成网络错误，也不发 `/cancel` 请求。
- 幂等键由调用方为一次用户动作生成并在显式重试时复用，符合 1—128 位 ASCII 规则。已有 Header 与选项冲突时本地拒绝；客户端不自动生成新键、不自动重试写请求或 429。
- `checkBackend` 单独处理 Actuator 的 `{status}`，不强套业务 envelope。`useAsyncRequest` 提供 loading / success / error，重试先清错误，忽略过时请求结果；离开页面或 cancel 只停止浏览器等待。

中止浏览器请求可能发生在服务端已提交之后，不能据此判定保存失败或研究已取消。未来 Run 取消必须由明确的后端取消动作完成；SSE 客户端也留给相应任务。

## 实测验收

环境：Windows、Node 24.16.0、npm 11.13.0；后端仍为 JDK 17 / Boot 4.0.9，真实 MySQL / Redis 依赖。没有修改后端代码或数据库迁移。

- `npm install --no-fund` 成功，生成固定锁文件；安装审计 0 vulnerabilities，未把这项结果作为完整安全审计。
- 随后执行 `npm ci --offline --no-audit --no-fund`，仅用已准备缓存从锁文件重新安装成功；重新构建和客户端测试再次通过。该结果只适用于本次 Windows / x64 缓存，不代表任意机器首次安装可离线完成。
- `npm run build` 通过 Vue / TypeScript 检查和 Vite 生产构建；35 个模块，JS 95.42 kB（gzip 37.31 kB）、CSS 5.48 kB（gzip 1.81 kB）。构建产物已忽略。
- `npm test`：**20 项通过、0 失败、0 跳过**。覆盖三种 HeadersInit、不可编码请求体的本地拒绝、201/202、409 字段错误、200 业务失败、错误响应脱敏、AbortSignal、显式幂等键复用、无自动重试及异步状态竞争。
- 本机真实 HTTP fixture 收到创建请求后，浏览器 abort 结束等待，但服务端定时工作继续，且只收到原创建路径，没有取消请求。此项不是尚未实现的 Run 生命周期验收。
- `npm run test:proxy` 通过真实 Vite → Spring Boot：工作台直接刷新 200、readiness UP、业务 404 envelope、非法草稿 400 字段错误及 traceId。仅查询不存在资源 / 发送校验不通过的输入，不创建领域数据。
- 浏览器实际检查桌面布局：后端停止时显示连接失败和“重新连接”；启动后点击重试变为“服务已就绪”；工作台刷新、未知路由 404 和返回工作台均通过。加载阶段由状态测试覆盖；未宣称已验证手机浏览器或完整端到端业务。
- 截图保存在本机忽略路径 `backend/target/g07-workbench.png`，可随验收查看。真实配置、node_modules、dist 与临时输出不提交；根 README / 复用计划的原有改动继续保留。
- 验收用前后端进程在收尾时停止，MySQL / Redis 依赖保留；需要再次查看时按上方命令启动。

下一组 G08 接入实际组合 / 决策表单，保留 SYNTHETIC 标识与确认 / 版本语义，不在 Run 接口完成前伪造研究成功。
