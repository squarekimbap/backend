package com.tourapi.model;

/** 로그인/refresh 응답. refresh 시에는 refreshToken이 null일 수 있다(Cognito는 재발급 안 함). */
public record TokenResponse(String accessToken, String idToken, String refreshToken, Integer expiresIn) {
}
