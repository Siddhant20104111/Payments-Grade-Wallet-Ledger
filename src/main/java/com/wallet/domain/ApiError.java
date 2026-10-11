package com.wallet.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record ApiError(
        @JsonProperty("code") String code,
        @JsonProperty("message") String message,
        @JsonProperty("request_id") String requestId
) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, UUID.randomUUID().toString());
    }

    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(code, message, requestId != null ? requestId : UUID.randomUUID().toString());
    }
}
