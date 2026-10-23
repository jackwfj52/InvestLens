# G03：统一响应、异常与请求追踪

日期：2026-10-23（Asia/Taipei）。基于 [G01 API 契约](contracts/workflow-and-api.md) 实现；保留 Java 17、Spring Boot 4.0.9、既有依赖与包名前缀。G02 的行情资格待确认不阻塞本组通用基础。

## 实现与复用

保留 WealthFlow `ApiResponse` 的泛型 record 结构，以及 `BusinessException / ErrorCode / GlobalExceptionHandler` 的类型化异常处理思路，按 InvestLens 重写字段和错误码。没有迁入旧资产分类错误码、任意异常 data/消息透传或 Windows DPAPI 配置。

| 入口 | 行为 |
|---|---|
| [ApiResponse](../backend/src/main/java/org/jack/backend/common/api/ApiResponse.java) | 固定 schemaVersion/code/message/data/errors/traceId/timestamp 七字段；时间为 UTC 毫秒；失败 data=null |
| [ApiResponses](../backend/src/main/java/org/jack/backend/common/api/ApiResponses.java) | 显式提供 ok、created、accepted、inProgress，分别返回 200/201/202/202；创建响应保留 Location |
| [ErrorCode](../backend/src/main/java/org/jack/backend/common/exception/ErrorCode.java) | G01 的 24 个业务错误码、HTTP 状态与固定公开消息 |
| [BusinessException](../backend/src/main/java/org/jack/backend/common/exception/BusinessException.java) | 只接受类型化错误码、字段问题及内部 cause；不接收任意公开消息或 data |
| [ApiExceptionHandler](../backend/src/main/java/org/jack/backend/infrastructure/web/ApiExceptionHandler.java) | 参数/JSON/领域/未知异常映射；拒绝值、校验器自定义消息和上游原文不进入响应或自有日志 |
| [RequestTraceFilter](../backend/src/main/java/org/jack/backend/infrastructure/web/RequestTraceFilter.java) | 服务端 UUID，X-Trace-Id 与响应 traceId 一致；MDC 在 finally 恢复；异步/错误重分派复用请求标识 |
| [ApiJsonConfiguration](../backend/src/main/java/org/jack/backend/infrastructure/web/ApiJsonConfiguration.java) | 使用 Boot 4 的 Jackson 3 JsonMapperBuilderCustomizer，拒绝未知 JSON 字段与重复 key |
| [SensitiveFieldResponseAdvice](../backend/src/main/java/org/jack/backend/infrastructure/web/SensitiveFieldResponseAdvice.java) | 只处理 ApiResponse 的 JSON，递归移除凭据字段；保留业务数据、数值字符串、usage 和 HTTP 状态 |

本组未新增生产业务端点。验收用 `/_fixture` 控制器仅位于测试源码，不随生产 JAR 发布；Decision/Run/Portfolio 接口仍由后续任务实现。

## 后续控制器用法

```java
// Controller 正常响应：显式保留 HTTP 含义。
return ApiResponses.created(URI.create("/api/decisions/" + id), dto);
return ApiResponses.accepted(URI.create("/api/runs/" + runId), runDto);
return ApiResponses.inProgress(runDto); // 202 / RUN_IN_PROGRESS

// Service 失败：只使用固定公开消息，字段路径不含拒绝值。
throw new BusinessException(ErrorCode.VERSION_CONFLICT);
throw new BusinessException(ErrorCode.NEEDS_INPUT,
        List.of(ApiError.required("horizon")));
throw new BusinessException(ErrorCode.UPSTREAM_ERROR, upstreamException);
```

ApiError 使用 `required/invalid` 工厂输出可公开的字段路径与固定消息。Bean Validation 的嵌套字段示例为 `positions[0].weight`；无法安全表示的路径为空。服务端返回值校验失败为 500，不能归咎为用户输入错误。

框架层 JSON 不可读、未知/重复字段、参数类型错误返回 400/INVALID_REQUEST；字段约束失败返回 400/VALIDATION_ERROR。领域层未确认输入、版本或幂等冲突返回 409；同步上游失败/超时等按 ErrorCode 返回 502/504。框架 404/405/415 保留原 HTTP 状态；405/415 使用通用 INVALID_REQUEST，并保留 Allow 等必要协议头。ErrorResponse 的内部 detail 和任意其他 header 不透传。

控制器显式返回的 ResponseEntity 状态和 header 不会被统一改成 200。非 ApiResponse 对象、字符串、Actuator 正常结果和未来 SSE 不自动包装。容器 `/error` 兜底仍使用 Boot 自身结构；本组通过 Boot 4 的 `spring.web.error.*` 禁止输出消息、绑定错误、异常类型、堆栈与路径，即使请求带 message/trace=true 也不开放。

## 脱敏与追踪边界

- 不信任、不回显来访 X-Trace-Id；每个请求生成独立 UUID。异步 Servlet 分派仍沿用该请求的标识；后台工作流的 Run 关联与追踪传递留给对应任务。
- 自有错误日志只含错误码、traceId、异常类名，不记录异常消息/堆栈、URL/query、请求头、请求体或拒绝值。默认关闭 MVC 请求详情记录；上游适配器后续也须遵循同样约定。
- JSON 凭据字段识别忽略大小写、下划线和连字符，覆盖 apiKey/apiSecret、accessToken/refreshToken、password/passphrase、credentials、privateKey、Authorization/Cookie、executionToken 等，适用于对象、Map 和嵌套数组。totalTokens 等 usage 字段不会因含 token 一词被删除。
- 该过滤只针对保留的凭据字段名，不检测任意自由文本中的秘密，也不替代专用公开 DTO。Controller 不应直接返回 Provider 配置、Entity、原始上游响应或用户凭据；异常字段说明也不得由拒绝值或原始消息拼接。
- 严格 JSON 只解决结构层输入，金融小数单位、权重合计、版本和引用归属等业务检查仍由对应领域实现。

## 验收

实际环境：JDK 17.0.12、Maven Wrapper 3.10.0、Spring Boot 4.0.9、Spring Framework 7.0.10、Jackson 3.1.7。复用本机依赖缓存离线执行，无数据库、Redis、模型或行情请求。

```powershell
Set-Location backend
$env:JAVA_HOME = 'D:\develop\Java\jdk-17'
.\mvnw.cmd -o -B -ntp '-Dtest=ApiInfrastructureMvcTests,RequestTraceFilterTests,ApiExceptionHandlerTests' test
```

结果：**41 项检查通过，失败 0、错误 0、跳过 0**。包含 24 个业务错误码的独立 HTTP 预期，以及以下代表行为：

- 200/201/202、Location、RUN_IN_PROGRESS 与自定义 ResponseEntity 状态保留。
- 未确认输入、版本/幂等冲突 409，422/429/502/503/504 与内部 500。
- 嵌套 Bean Validation、方法参数约束、服务输入/返回值校验、缺参数与类型错误。
- 非法 JSON、未知字段、重复 key、404/405/415 及 Allow。
- 伪造来访追踪头、连续请求隔离、MDC 失败清理与异步/错误分派。
- 错误响应及捕获日志不含测试凭据、数据库连接信息、认证头或拒绝值；嵌套凭据被移除，usage/金融字符串保留。
- Boot 容器错误兜底即使请求主动索取详情也不泄漏消息、异常或私有路径。

主代码与所有测试源码随此命令编译通过；仅执行本组的 MVC/基础单测。原始 `BackendApplicationTests.contextLoads` 未执行，完整应用、MySQL、Redis 和 Flyway 启动/集成仍属 G04，不能用本组结果宣称其已通过。测试结果位于忽略的 `backend/target/surefire-reports/`，本文件记录可复现命令与执行结论。
