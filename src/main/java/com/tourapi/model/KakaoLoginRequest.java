package com.tourapi.model;

/** POST /v1/auth/kakao 요청 바디(앱이 카카오 SDK로 획득한 액세스 토큰). */
public record KakaoLoginRequest(String kakaoAccessToken) {
}
