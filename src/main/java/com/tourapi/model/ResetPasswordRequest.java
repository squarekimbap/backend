package com.tourapi.model;

/** POST /v1/auth/reset-password 요청 바디(코드 + 새 비밀번호). */
public record ResetPasswordRequest(String email, String code, String newPassword) {
}
