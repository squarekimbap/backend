package com.tourapi.model;

/** POST /v1/auth/signup 요청 바디. */
public record SignupRequest(String email, String password, String nickname) {
}
