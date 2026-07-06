package com.tourapi.model;

/** POST /v1/auth/refresh 요청 바디. */
public record RefreshRequest(String refreshToken) {
}
