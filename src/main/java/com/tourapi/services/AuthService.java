package com.tourapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.tourapi.lib.CognitoAuth;
import com.tourapi.lib.KakaoVerifier;
import com.tourapi.lib.TokenPayload;
import com.tourapi.lib.UserStore;
import com.tourapi.model.TokenResponse;
import com.tourapi.model.UserProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;

import java.time.Instant;

/** 인증 오케스트레이션. Cognito 예외는 그대로 위로 — HTTP 매핑은 AuthResource 책임. */
@ApplicationScoped
public class AuthService {

    @Inject
    CognitoAuth cognito;

    @Inject
    UserStore userStore;

    @Inject
    KakaoVerifier kakaoVerifier;

    public void signup(String email, String password, String nickname) {
        cognito.signUp(email, password, nickname);
    }

    public void confirm(String email, String code) {
        cognito.confirm(email, code);
    }

    public void resendCode(String email) {
        cognito.resendConfirmationCode(email);
    }

    public void forgotPassword(String email) {
        cognito.forgotPassword(email);
    }

    public void resetPassword(String email, String code, String newPassword) {
        cognito.confirmForgotPassword(email, code, newPassword);
    }

    public TokenResponse login(String email, String password) {
        AuthenticationResultType r =
                cognito.loginWithPassword(CognitoAuth.usernameForEmail(email), password);
        upsertFromIdToken(r, "email");
        return toResponse(r);
    }

    public TokenResponse refresh(String refreshToken) {
        return toResponse(cognito.refresh(refreshToken));
    }

    /** 로그아웃. refresh 토큰을 폐기해 자동로그인을 끊는다(발급된 access 토큰도 무효화). */
    public void logout(String refreshToken) {
        cognito.revokeRefreshToken(refreshToken);
    }

    /**
     * 카카오 브릿지: 토큰 검증 → kakao_<id> 사용자 확보 → 비밀번호 회전 로그인.
     * 동시 로그인이 서로의 회전 비밀번호를 덮어쓰면 NotAuthorized가 나므로 1회 재시도한다.
     */
    public TokenResponse kakaoLogin(String kakaoAccessToken) {
        KakaoVerifier.KakaoUser ku = kakaoVerifier.verify(kakaoAccessToken);
        String username = cognito.ensureKakaoUser(ku.id(), ku.nickname());
        AuthenticationResultType r;
        try {
            r = cognito.rotatePasswordAndLogin(username);
        } catch (NotAuthorizedException e) {
            r = cognito.rotatePasswordAndLogin(username);
        }
        upsertFromIdToken(r, "kakao");
        return toResponse(r);
    }

    /**
     * 방금 Cognito가 발급한 idToken에서 sub/email/nickname을 꺼내 프로필을 최초 1회 생성.
     * 저장 실패는 UserStore가 삼키므로 로그인 응답에는 영향 없다.
     */
    void upsertFromIdToken(AuthenticationResultType r, String provider) {
        JsonNode claims = TokenPayload.payload(r.idToken());
        String email = claims.path("email").asText("");
        userStore.putIfAbsent(new UserProfile(
                claims.path("sub").asText(),
                email.isBlank() ? null : email,
                claims.path("nickname").asText(""),
                provider,
                Instant.now().toString()));
    }

    static TokenResponse toResponse(AuthenticationResultType r) {
        return new TokenResponse(r.accessToken(), r.idToken(), r.refreshToken(), r.expiresIn());
    }
}
