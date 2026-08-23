package com.tourapi.model;

/** POST /v1/auth/resend-code 요청 바디(가입 확인코드 재발송). */
public record ResendCodeRequest(String email) {
}
