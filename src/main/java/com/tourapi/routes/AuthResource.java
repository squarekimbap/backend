package com.tourapi.routes;

import com.tourapi.model.ApiError;
import com.tourapi.model.ConfirmRequest;
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
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

/** 인증 라우트. 얇게 유지: 요청 검증 → 서비스 위임 → Cognito 예외를 HTTP로 매핑. */
@Path("/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "auth", description = "회원가입·로그인 (Cognito 프록시)")
public class AuthResource {

    private static final Logger LOG = Logger.getLogger(AuthResource.class);

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
