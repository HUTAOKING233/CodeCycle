package me.codecycle.common.web;

/** V1 外部/内部 HTTP 接口统一响应外壳。requestId 用于跨 Gateway 和服务定位请求。 */
public record ApiResponse<T>(boolean ok, T data, ApiError error, String requestId) {
    public static <T> ApiResponse<T> success(T data, String requestId) {
        return new ApiResponse<>(true, data, null, requestId);
    }

    /** 错误码保持稳定，message 可面向调用方，retryable 指示客户端是否可安全重试。 */
    public record ApiError(String code, String message, boolean retryable) {}
}
