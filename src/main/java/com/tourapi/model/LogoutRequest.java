package com.tourapi.model;

/** POST /v1/auth/logout 요청 바디. 폐기할 refresh 토큰. */
public record LogoutRequest(String refreshToken) {
}
