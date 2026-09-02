package com.tourapi.services;

import com.tourapi.lib.AppleTokens;
import com.tourapi.lib.CognitoAuth;
import com.tourapi.lib.UserStore;
import com.tourapi.model.UserProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** 사용자 프로필 서비스. 생성은 로그인 시 AuthService가 담당한다. */
@ApplicationScoped
public class UserService {

    @Inject
    UserStore userStore;

    @Inject
    CognitoAuth cognito;

    @Inject
    AppleTokens appleTokens;

    /** 프로필 조회. 없으면 null. */
    public UserProfile find(String userId) {
        return userStore.get(userId);
    }

    /**
     * 탈퇴. Apple 토큰 폐기 → 프로필 행(개인정보) → Cognito 계정 순서.
     * 행 삭제가 멱등이라 Cognito 삭제가 실패해도 같은 요청을 그대로 재시도할 수 있다.
     * 역순으로 하면 재시도가 UserNotFound로 끊겨 남은 행을 치울 길이 없어진다.
     *
     * <p>Apple 폐기(심사 5.1.1(v))가 맨 앞인 이유: 토큰이 프로필 행에 있어서 행을 지우면 못 읽고,
     * 뒤에 두면 Cognito 삭제가 실패한 재시도에서 이미 행이 없어 영영 폐기하지 못한다.
     * 저장된 토큰이 없으면(=다른 로그인 수단) no-op이라 username 접두사를 볼 필요가 없다.
     */
    public void delete(String username, String userId) {
        appleTokens.revoke(userStore.appleRefreshToken(userId));
        userStore.delete(userId);
        cognito.deleteUser(username);
    }

    /**
     * 닉네임 변경. Cognito(신원 저장소) → users 테이블(읽기 모델) 순서.
     * 프로필 행이 없으면 false — 이때 Cognito 값은 이미 바뀌었으므로
     * 다음 로그인에 새 닉네임으로 프로필이 다시 생성된다.
     */
    public boolean updateNickname(String username, String userId, String nickname) {
        cognito.updateNickname(username, nickname);
        return userStore.updateNickname(userId, nickname);
    }
}
