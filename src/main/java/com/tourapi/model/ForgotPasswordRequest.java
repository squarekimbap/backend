package com.tourapi.model;

/** POST /v1/auth/forgot-password 요청 바디(재설정 코드 발송 요청). */
public record ForgotPasswordRequest(String email) {
}
