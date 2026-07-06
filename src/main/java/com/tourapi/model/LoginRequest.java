package com.tourapi.model;

/** POST /v1/auth/login 요청 바디. */
public record LoginRequest(String email, String password) {
}
