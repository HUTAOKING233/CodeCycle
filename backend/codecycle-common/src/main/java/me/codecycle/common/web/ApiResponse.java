package me.codecycle.common.web;

public record ApiResponse<T>(boolean ok, T data, ApiError error, String requestId) {
    public static <T> ApiResponse<T> success(T data, String requestId) {
        return new ApiResponse<>(true, data, null, requestId);
    }

    public record ApiError(String code, String message, boolean retryable) {}
}
