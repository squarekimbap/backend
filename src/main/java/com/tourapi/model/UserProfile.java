package com.tourapi.model;

/** users 테이블 프로필. userId = Cognito sub. 카카오 사용자는 email이 null일 수 있다. */
public record UserProfile(String userId, String email, String nickname, String provider, String createdAt) {
}
