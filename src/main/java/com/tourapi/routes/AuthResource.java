package com.tourapi.routes;

import com.tourapi.lib.InvalidAppleTokenException;
import com.tourapi.lib.InvalidKakaoTokenException;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.ApiError;
import com.tourapi.model.AppleLoginRequest;
import com.tourapi.model.ConfirmRequest;
import com.tourapi.model.ForgotPasswordRequest;
import com.tourapi.model.KakaoLoginRequest;
import com.tourapi.model.LoginRequest;
import com.tourapi.model.LogoutRequest;
import com.tourapi.model.RefreshRequest;
import com.tourapi.model.ResendCodeRequest;
import com.tourapi.model.ResetPasswordRequest;
import com.tourapi.model.SignupRequest;
import com.tourapi.services.AuthService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CodeMismatchException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExpiredCodeException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidParameterException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.LimitExceededException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotConfirmedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.TooManyFailedAttemptsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.TooManyRequestsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UnsupportedTokenTypeException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

/** 인증 라우트. 얇게 유지: 요청 검증 → 서비스 위임 → Cognito 예외를 HTTP로 매핑. */
@Path("/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "auth", description = "회원가입·로그인 (Cognito 프록시)")
public class AuthResource {

    private static final Logger LOG = Logger.getLogger(AuthResource.class);
    // 계정 존재 여부를 누설하지 않는 단일 메시지 (틀린 비밀번호든 없는 계정이든 동일)
    private static final String BAD_CREDENTIALS = "이메일 또는 비밀번호가 올바르지 않음";

    @Inject
    AuthService authService;

    @POST
    @Path("/signup")
    @Operation(summary = "이메일 회원가입(확인코드 발송)")
    public Response signup(SignupRequest req) {
        if (req == null || blank(req.email()) || blank(req.password()) || blank(req.nickname())) {
            return bad("email·password·nickname 필수");
        }
        try {
            authService.signup(req.email(), req.password(), req.nickname());
            return Response.status(201).build();
        } catch (UsernameExistsException e) {
            return error(409, "email_exists", "이미 가입된 이메일");
        } catch (InvalidPasswordException | InvalidParameterException e) {
            return error(400, "weak_password", "비밀번호 정책 미달(8자 이상, 대/소문자·숫자 포함)");
        } catch (CognitoIdentityProviderException e) {
            return upstream("signup", e);
        }
    }

    @POST
    @Path("/confirm")
    @Operation(summary = "가입 확인코드 검증")
    public Response confirm(ConfirmRequest req) {
        if (req == null || blank(req.email()) || blank(req.code())) {
            return bad("email·code 필수");
        }
        try {
            authService.confirm(req.email(), req.code());
            return Response.noContent().build();
        } catch (CodeMismatchException | ExpiredCodeException e) {
            return error(400, "invalid_code", "확인코드가 올바르지 않거나 만료됨");
        } catch (CognitoIdentityProviderException e) {
            return upstream("confirm", e);
        }
    }

    @POST
    @Path("/resend-code")
    @Operation(summary = "가입 확인코드 재발송")
    public Response resendCode(ResendCodeRequest req) {
        if (req == null || blank(req.email())) {
            return bad("email 필수");
        }
        try {
            authService.resendCode(req.email());
            return Response.noContent().build();
        } catch (UserNotFoundException e) {
            // 없는 계정도 204 — 재발송으로 가입 여부를 캐낼 수 없게 한다
            return Response.noContent().build();
        } catch (InvalidParameterException e) {
            // 이미 확인된 계정. signup이 이미 409로 존재를 알려주므로 새로 누설되는 정보는 없고,
            // "로그인하세요"로 안내할 수 있어 앱에 유용하다
            return error(400, "already_confirmed", "이미 인증이 끝난 계정 — 로그인할 것");
        } catch (LimitExceededException | TooManyRequestsException e) {
            return error(429, "too_many_requests", "요청이 너무 잦음 — 잠시 후 다시 시도");
        } catch (CognitoIdentityProviderException e) {
            return upstream("resend-code", e);
        }
    }

    @POST
    @Path("/forgot-password")
    @Operation(summary = "비밀번호 재설정 코드 발송")
    public Response forgotPassword(ForgotPasswordRequest req) {
        if (req == null || blank(req.email())) {
            return bad("email 필수");
        }
        try {
            authService.forgotPassword(req.email());
            return Response.noContent().build();
        } catch (UserNotFoundException e) {
            // 없는 계정도 204 — 가입 여부 누설 금지.
            // 카카오 사용자는 username이 kakao_* 라 이메일로는 조회되지 않아 여기로 온다
            return Response.noContent().build();
        } catch (InvalidParameterException e) {
            // 확인된 이메일이 없는 계정 = 가입 확인 미완료. 재설정 대신 가입 확인으로 보낸다
            return error(403, "user_not_confirmed", "이메일 확인이 필요함 — 확인코드 재발송 후 인증할 것");
        } catch (LimitExceededException | TooManyRequestsException e) {
            return error(429, "too_many_requests", "요청이 너무 잦음 — 잠시 후 다시 시도");
        } catch (CognitoIdentityProviderException e) {
            return upstream("forgot-password", e);
        }
    }

    @POST
    @Path("/reset-password")
    @Operation(summary = "재설정 코드로 새 비밀번호 적용")
    public Response resetPassword(ResetPasswordRequest req) {
        if (req == null || blank(req.email()) || blank(req.code()) || blank(req.newPassword())) {
            return bad("email·code·newPassword 필수");
        }
        try {
            authService.resetPassword(req.email(), req.code(), req.newPassword());
            return Response.noContent().build();
        } catch (CodeMismatchException | ExpiredCodeException | UserNotFoundException e) {
            // 없는 계정도 코드 오류와 같은 응답 — 여기서 계정 존재를 구분해 주면 안 된다
            return error(400, "invalid_code", "재설정 코드가 올바르지 않거나 만료됨");
        } catch (InvalidPasswordException | InvalidParameterException e) {
            return error(400, "weak_password", "비밀번호 정책 미달(8자 이상, 대/소문자·숫자 포함)");
        } catch (TooManyFailedAttemptsException | LimitExceededException | TooManyRequestsException e) {
            return error(429, "too_many_requests", "시도가 너무 잦음 — 잠시 후 다시 시도");
        } catch (CognitoIdentityProviderException e) {
            return upstream("reset-password", e);
        }
    }

    @POST
    @Path("/login")
    @Operation(summary = "이메일 로그인 → 토큰 3종(access/id/refresh)")
    public Response login(LoginRequest req) {
        if (req == null || blank(req.email()) || blank(req.password())) {
            return bad("email·password 필수");
        }
        try {
            return Response.ok(authService.login(req.email(), req.password())).build();
        } catch (UserNotConfirmedException e) {
            return error(403, "user_not_confirmed", "이메일 확인이 필요함");
        } catch (NotAuthorizedException | UserNotFoundException e) {
            return error(401, "invalid_credentials", BAD_CREDENTIALS);
        } catch (CognitoIdentityProviderException e) {
            return upstream("login", e);
        }
    }

    @POST
    @Path("/kakao")
    @Operation(summary = "카카오 로그인(첫 로그인 = 자동 가입) → 토큰 3종")
    public Response kakao(KakaoLoginRequest req) {
        if (req == null || blank(req.kakaoAccessToken())) {
            return bad("kakaoAccessToken 필수");
        }
        try {
            return Response.ok(authService.kakaoLogin(req.kakaoAccessToken())).build();
        } catch (InvalidKakaoTokenException e) {
            return error(401, "invalid_kakao_token", "카카오 토큰이 유효하지 않음");
        } catch (UpstreamException e) {
            LOG.warnf("kakao upstream 실패: %s", e.getMessage());
            return error(502, "upstream_error", "카카오 연동 일시 오류");
        } catch (CognitoIdentityProviderException e) {
            return upstream("kakao", e);
        }
    }

    @POST
    @Path("/apple")
    @Operation(summary = "Apple 로그인(첫 로그인 = 자동 가입) → 토큰 3종")
    public Response apple(AppleLoginRequest req) {
        if (req == null || blank(req.identityToken())) {
            return bad("identityToken 필수");
        }
        try {
            return Response.ok(authService.appleLogin(req.identityToken(), req.fullName())).build();
        } catch (InvalidAppleTokenException e) {
            return error(401, "invalid_apple_token", "Apple 토큰이 유효하지 않음");
        } catch (UpstreamException e) {
            LOG.warnf("apple upstream 실패: %s", e.getMessage());
            return error(502, "upstream_error", "Apple 연동 일시 오류");
        } catch (CognitoIdentityProviderException e) {
            return upstream("apple", e);
        }
    }

    @POST
    @Path("/refresh")
    @Operation(summary = "refresh 토큰으로 access 토큰 갱신")
    public Response refresh(RefreshRequest req) {
        if (req == null || blank(req.refreshToken())) {
            return bad("refreshToken 필수");
        }
        try {
            return Response.ok(authService.refresh(req.refreshToken())).build();
        } catch (NotAuthorizedException e) {
            return error(401, "invalid_refresh_token", "refresh 토큰이 유효하지 않음");
        } catch (CognitoIdentityProviderException e) {
            return upstream("refresh", e);
        }
    }

    @POST
    @Path("/logout")
    @Operation(summary = "로그아웃 — refresh 토큰 폐기(자동로그인 해제)")
    public Response logout(LogoutRequest req) {
        if (req == null || blank(req.refreshToken())) {
            return bad("refreshToken 필수");
        }
        try {
            authService.logout(req.refreshToken());
        } catch (NotAuthorizedException e) {
            // 이미 폐기됐거나 형식이 틀린 토큰 — 앱 입장에선 로그아웃된 상태와 같다
        } catch (UnsupportedTokenTypeException e) {
            // 조용히 성공시키면 "로그아웃했는데 세션이 살아있는" 상태가 된다 — 반드시 드러낸다
            LOG.error("토큰 폐기 비활성 — UserPoolClient의 EnableTokenRevocation 확인 필요");
            return error(502, "upstream_error", "인증 서비스 일시 오류");
        } catch (CognitoIdentityProviderException e) {
            return upstream("logout", e);
        }
        return Response.noContent().build();
    }

    // ── 공용 헬퍼 (routes 계층 책임: 검증·매핑) ─────────────────────

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static Response bad(String msg) {
        return error(400, "bad_request", msg);
    }

    static Response error(int status, String code, String msg) {
        return Response.status(status).entity(new ApiError(code, msg)).build();
    }

    /** 예상 밖 Cognito 오류 → 502. 로그에 오류 코드만 남기고 사용자 입력은 남기지 않는다. */
    Response upstream(String op, CognitoIdentityProviderException e) {
        LOG.warnf("%s Cognito 오류: %s", op, e.awsErrorDetails() == null
                ? e.getClass().getSimpleName() : e.awsErrorDetails().errorCode());
        return error(502, "upstream_error", "인증 서비스 일시 오류");
    }
}
