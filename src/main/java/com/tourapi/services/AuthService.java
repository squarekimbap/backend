package com.tourapi.services;

import com.tourapi.lib.CognitoAuth;
import com.tourapi.model.TokenResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;

/** 인증 오케스트레이션. Cognito 예외는 그대로 위로 — HTTP 매핑은 AuthResource 책임. */
@ApplicationScoped
public class AuthService {

    @Inject
    CognitoAuth cognito;

    public void signup(String email, String password, String nickname) {
        cognito.signUp(email, password, nickname);
    }

    public void confirm(String email, String code) {
        cognito.confirm(email, code);
    }

    public TokenResponse login(String email, String password) {
        AuthenticationResultType r =
                cognito.loginWithPassword(CognitoAuth.usernameForEmail(email), password);
        return toResponse(r);
    }

    public TokenResponse refresh(String refreshToken) {
        return toResponse(cognito.refresh(refreshToken));
    }

    static TokenResponse toResponse(AuthenticationResultType r) {
        return new TokenResponse(r.accessToken(), r.idToken(), r.refreshToken(), r.expiresIn());
    }
}
