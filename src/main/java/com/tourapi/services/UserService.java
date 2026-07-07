package com.tourapi.services;

import com.tourapi.lib.UserStore;
import com.tourapi.model.UserProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** 사용자 프로필 서비스. 조회 전용(생성은 로그인 시 AuthService가 담당). */
@ApplicationScoped
public class UserService {

    @Inject
    UserStore userStore;

    /** 프로필 조회. 없으면 null. */
    public UserProfile find(String userId) {
        return userStore.get(userId);
    }
}
