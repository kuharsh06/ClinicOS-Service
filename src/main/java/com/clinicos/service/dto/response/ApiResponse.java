package com.clinicos.service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private T data;
    private Meta meta;
    private ApiError error;

    // Success response with just data
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .build();
    }

    // Success response with data and meta (for paginated results)
    public static <T> ApiResponse<T> success(T data, Meta meta) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .meta(meta)
                .build();
    }


    // Error response
    public static <T> ApiResponse<T> error(String code, String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .error(ApiError.builder()
                        .code(code)
                        .message(message)
                        .retryable(false)
                        .build())
                .build();
    }

    // Error response with retryable flag
    public static <T> ApiResponse<T> error(String code, String message, boolean retryable) {
        return ApiResponse.<T>builder()
                .success(false)
                .error(ApiError.builder()
                        .code(code)
                        .message(message)
                        .retryable(retryable)
                        .build())
                .build();
    }

    // Error response with retry after
    public static <T> ApiResponse<T> error(String code, String message, boolean retryable, Long retryAfterMs) {
        return ApiResponse.<T>builder()
                .success(false)
                .error(ApiError.builder()
                        .code(code)
                        .message(message)
                        .retryable(retryable)
                        .retryAfterMs(retryAfterMs)
                        .build())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Meta {
        private CursorPagination pagination;
        private Long serverTimestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CursorPagination {
        private Boolean hasMore;
        private String nextCursor;
        private Integer totalEstimate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ApiError {
        private String code;           // machine-readable: "INSUFFICIENT_PERMISSION"
        private String message;        // human-readable
        private Map<String, Object> details;
        private Boolean retryable;
        private Long retryAfterMs;
    }
}
