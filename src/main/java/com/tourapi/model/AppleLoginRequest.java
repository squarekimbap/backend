package com.tourapi.model;

/**
 * POST /v1/auth/apple 요청 바디. identityToken은 앱이 Apple 로그인으로 받은 JWT.
 * fullName은 Apple이 첫 로그인에만 앱에 주므로 그때만 실려 온다 — 놓치면 다시 받을 수 없다.
 * authorizationCode는 선택이지만 없으면 탈퇴 때 Apple 토큰을 폐기할 수 없다(심사 5.1.1(v)) —
 * 5분·1회용이라 서버가 로그인 순간에 refresh 토큰으로 교환해 둔다. 매 로그인마다 새로 온다.
 */
public record AppleLoginRequest(String identityToken, String fullName, String authorizationCode) {
}
