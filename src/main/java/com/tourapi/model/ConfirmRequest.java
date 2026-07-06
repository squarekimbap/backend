package com.tourapi.model;

/** POST /v1/auth/confirm 요청 바디(가입 확인코드). */
public record ConfirmRequest(String email, String code) {
}
