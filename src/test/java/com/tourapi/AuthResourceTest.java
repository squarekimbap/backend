package com.tourapi;

import com.tourapi.lib.CognitoAuth;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CodeMismatchException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotConfirmedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 인증 라우트 계약 테스트. CognitoAuth를 목으로 바꿔 AWS 없이
 * "요청 검증 + Cognito 예외 → HTTP 매핑"을 검증한다.
 */
@QuarkusTest
public class AuthResourceTest {

    @InjectMock
    CognitoAuth cognito;

    /** Cognito가 발급한 것처럼 sub/email/nickname 클레임을 담은 가짜 토큰 3종 생성. */
    static AuthenticationResultType tokens(String sub) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + sub + "\",\"email\":\"a@b.c\",\"nickname\":\"nick\"}").getBytes());
        String jwt = "eyJhbGciOiJub25lIn0." + payload + ".sig";
        return AuthenticationResultType.builder()
                .accessToken(jwt).idToken(jwt).refreshToken("rt").expiresIn(3600).build();
    }

    @Test
    public void signup_성공시_201() {
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"a@b.c\",\"password\":\"Abcd1234\",\"nickname\":\"nick\"}")
                .post("/v1/auth/signup").then().statusCode(201);
        verify(cognito).signUp("a@b.c", "Abcd1234", "nick");
    }

    @Test
    public void signup_필드누락시_400() {
        given().contentType(ContentType.JSON).body("{\"email\":\"a@b.c\"}")
                .post("/v1/auth/signup").then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }

    @Test
    public void signup_중복이면_409() {
        doThrow(UsernameExistsException.builder().build())
                .when(cognito).signUp(any(), any(), any());
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"a@b.c\",\"password\":\"Abcd1234\",\"nickname\":\"n\"}")
                .post("/v1/auth/signup").then().statusCode(409)
                .body("error", equalTo("email_exists"));
    }

    @Test
    public void confirm_성공시_204() {
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"a@b.c\",\"code\":\"123456\"}")
                .post("/v1/auth/confirm").then().statusCode(204);
        verify(cognito).confirm("a@b.c", "123456");
    }

    @Test
    public void confirm_코드불일치_400() {
        doThrow(CodeMismatchException.builder().build()).when(cognito).confirm(any(), any());
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"a@b.c\",\"code\":\"000000\"}")
                .post("/v1/auth/confirm").then().statusCode(400)
                .body("error", equalTo("invalid_code"));
    }

    @Test
    public void login_성공시_토큰3종() {
        when(cognito.loginWithPassword(any(), eq("Abcd1234"))).thenReturn(tokens("u-1"));
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"a@b.c\",\"password\":\"Abcd1234\"}")
                .post("/v1/auth/login").then().statusCode(200)
                .body("accessToken", notNullValue())
                .body("refreshToken", equalTo("rt"))
                .body("expiresIn", equalTo(3600));
    }

    @Test
    public void login_비밀번호틀리면_401_단일메시지() {
        when(cognito.loginWithPassword(any(), any()))
                .thenThrow(NotAuthorizedException.builder().build());
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"a@b.c\",\"password\":\"wrongpw1\"}")
                .post("/v1/auth/login").then().statusCode(401)
                .body("error", equalTo("invalid_credentials"));
    }

    @Test
    public void login_계정없어도_같은_401() { // 계정 존재 여부 누설 금지
        when(cognito.loginWithPassword(any(), any()))
                .thenThrow(UserNotFoundException.builder().build());
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"x@b.c\",\"password\":\"Abcd1234\"}")
                .post("/v1/auth/login").then().statusCode(401)
                .body("error", equalTo("invalid_credentials"));
    }

    @Test
    public void login_미확인계정_403() {
        when(cognito.loginWithPassword(any(), any()))
                .thenThrow(UserNotConfirmedException.builder().build());
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"a@b.c\",\"password\":\"Abcd1234\"}")
                .post("/v1/auth/login").then().statusCode(403)
                .body("error", equalTo("user_not_confirmed"));
    }

    @Test
    public void refresh_성공시_200() {
        when(cognito.refresh("rt")).thenReturn(tokens("u-1"));
        given().contentType(ContentType.JSON).body("{\"refreshToken\":\"rt\"}")
                .post("/v1/auth/refresh").then().statusCode(200)
                .body("accessToken", notNullValue());
    }

    @Test
    public void refresh_무효토큰_401() {
        when(cognito.refresh(any())).thenThrow(NotAuthorizedException.builder().build());
        given().contentType(ContentType.JSON).body("{\"refreshToken\":\"bad\"}")
                .post("/v1/auth/refresh").then().statusCode(401)
                .body("error", equalTo("invalid_refresh_token"));
    }
}
