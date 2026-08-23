package com.tourapi.model;

/** PATCH /v1/users/me 요청 바디. 지금은 닉네임만 수정 가능. */
public record UpdateProfileRequest(String nickname) {
}
