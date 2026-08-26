package com.tourapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.tourapi.lib.AppleVerifier;
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

    @Inject
    AppleVerifier appleVerifier;

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

    /**
     * 자동로그인. 앱이 보관한 refresh 토큰으로 access 토큰을 다시 받는다.
     * 로그인 때 프로필 저장이 실패했으면(UserStore가 삼킨다) /users/me가 계속 404로 남으므로,
     * 여기서 한 번 더 시도해 스스로 복구시킨다.
     */
    public TokenResponse refresh(String refreshToken) {
        AuthenticationResultType r = cognito.refresh(refreshToken);
        upsertFromIdToken(r);
        return toResponse(r);
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
     * Apple 브릿지: identityToken 검증 → apple_<sub> 사용자 확보 → 비밀번호 회전 로그인.
     * 이름은 첫 로그인에만 오므로 없으면 기본 닉네임으로 만든다. 카카오와 같은 경합 재시도 1회.
     */
    public TokenResponse appleLogin(String identityToken, String fullName) {
        AppleVerifier.AppleUser au = appleVerifier.verify(identityToken);
        String nickname = fullName == null || fullName.isBlank() ? "러너" : fullName.trim();
        String username = cognito.ensureAppleUser(au.sub(), nickname, au.email());
        AuthenticationResultType r;
        try {
            r = cognito.rotatePasswordAndLogin(username);
        } catch (NotAuthorizedException e) {
            r = cognito.rotatePasswordAndLogin(username);
        }
        upsertFromIdToken(r, "apple");
        return toResponse(r);
    }

    /**
     * 방금 Cognito가 발급한 idToken에서 sub/email/nickname을 꺼내 프로필을 최초 1회 생성.
     * 저장 실패는 UserStore가 삼키므로 로그인 응답에는 영향 없다.
     */
    void upsertFromIdToken(AuthenticationResultType r, String provider) {
        upsert(TokenPayload.payload(r.idToken()), provider);
    }

    /** provider를 알 수 없는 경로(자동로그인) — username 규약으로 되짚는다. */
    void upsertFromIdToken(AuthenticationResultType r) {
        JsonNode claims = TokenPayload.payload(r.idToken());
        upsert(claims, CognitoAuth.providerOfUsername(claims.path("cognito:username").asText("")));
    }

    private void upsert(JsonNode claims, String provider) {
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
