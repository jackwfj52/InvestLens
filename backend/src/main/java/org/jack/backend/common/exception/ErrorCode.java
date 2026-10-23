package org.jack.backend.common.exception;

import org.springframework.http.HttpStatus;

/** G01 domain codes and their public, configuration-free messages. */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "请求格式或参数不正确"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求字段未通过校验"),
    INVALID_WEIGHTS(HttpStatus.BAD_REQUEST, "组合权重不合法"),
    CURRENCY_MISMATCH(HttpStatus.BAD_REQUEST, "资产计价币种不一致"),
    INSUFFICIENT_FUNDING(HttpStatus.BAD_REQUEST, "指定资金来源不足"),
    UNSUPPORTED_INSTRUMENT(HttpStatus.BAD_REQUEST, "暂不支持该标的"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "请求的资源不存在"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "版本已变化，请读取最新版本后重试"),
    SNAPSHOT_MISMATCH(HttpStatus.CONFLICT, "输入与快照不匹配"),
    NEEDS_INPUT(HttpStatus.CONFLICT, "请先补齐必要的输入字段"),
    INPUT_NOT_CONFIRMED(HttpStatus.CONFLICT, "请先确认输入版本"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "幂等键已用于其他请求"),
    INVALID_STATE(HttpStatus.CONFLICT, "当前状态不允许此操作"),
    REPORT_UNAVAILABLE(HttpStatus.CONFLICT, "当前任务没有可发布报告"),
    LICENSE_BLOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "当前数据使用条件不满足研究要求"),
    DATA_UNUSABLE(HttpStatus.UNPROCESSABLE_CONTENT, "没有可用的计算输入"),
    BUDGET_EXHAUSTED(HttpStatus.TOO_MANY_REQUESTS, "调用预算已耗尽"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求受到限流，请稍后重试"),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "上游服务请求失败"),
    MODEL_OUTPUT_INVALID(HttpStatus.BAD_GATEWAY, "模型输出未通过校验"),
    PROVIDER_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "数据或模型服务尚未配置"),
    PERSISTENCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "持久化服务暂不可用"),
    UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "上游服务请求超时"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误，请使用追踪标识查询");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
