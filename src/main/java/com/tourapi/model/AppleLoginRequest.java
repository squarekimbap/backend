package com.tourapi.model;

/**
 * POST /v1/auth/apple 요청 바디. identityToken은 앱이 Apple 로그인으로 받은 JWT.
 * fullName은 Apple이 첫 로그인에만 앱에 주므로 그때만 실려 온다 — 놓치면 다시 받을 수 없다.
 */
public record AppleLoginRequest(String identityToken, String fullName) {
}
