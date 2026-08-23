package com.tourapi;

import com.tourapi.lib.CognitoAuth;
import com.tourapi.lib.InvalidKakaoTokenException;
import com.tourapi.lib.KakaoVerifier;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CodeMismatchException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotConfirmedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UnsupportedTokenTypeException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
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

    @InjectMock
    KakaoVerifier kakao;

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

    @Test
    public void logout_성공시_204_토큰폐기() {
        given().contentType(ContentType.JSON).body("{\"refreshToken\":\"rt\"}")
                .post("/v1/auth/logout").then().statusCode(204);
        verify(cognito).revokeRefreshToken("rt");
    }

    @Test
    public void logout_이미_폐기된_토큰도_204() { // 앱 입장에선 이미 로그아웃된 상태
        doThrow(NotAuthorizedException.builder().build())
                .when(cognito).revokeRefreshToken(any());
        given().contentType(ContentType.JSON).body("{\"refreshToken\":\"gone\"}")
                .post("/v1/auth/logout").then().statusCode(204);
    }

    @Test
    public void logout_폐기기능_비활성이면_502() { // 조용히 성공시키면 안 되는 설정 오류
        doThrow(UnsupportedTokenTypeException.builder().build())
                .when(cognito).revokeRefreshToken(any());
        given().contentType(ContentType.JSON).body("{\"refreshToken\":\"rt\"}")
                .post("/v1/auth/logout").then().statusCode(502)
                .body("error", equalTo("upstream_error"));
    }

    @Test
    public void logout_토큰누락_400() {
        given().contentType(ContentType.JSON).body("{}")
                .post("/v1/auth/logout").then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }

    @Test
    public void kakao_성공시_토큰3종_경합시_1회재시도() {
        when(kakao.verify("kt")).thenReturn(new KakaoVerifier.KakaoUser(7, "kim"));
        when(cognito.ensureKakaoUser(7, "kim")).thenReturn("kakao_7");
        when(cognito.rotatePasswordAndLogin("kakao_7"))
                .thenThrow(NotAuthorizedException.builder().build()) // 동시 로그인 경합 1회
                .thenReturn(tokens("u-7"));                          // 재시도 성공
        given().contentType(ContentType.JSON).body("{\"kakaoAccessToken\":\"kt\"}")
                .post("/v1/auth/kakao").then().statusCode(200)
                .body("accessToken", notNullValue())
                .body("refreshToken", equalTo("rt"));
        verify(cognito, times(2)).rotatePasswordAndLogin("kakao_7");
    }

    @Test
    public void kakao_무효토큰_401() {
        when(kakao.verify(any())).thenThrow(new InvalidKakaoTokenException());
        given().contentType(ContentType.JSON).body("{\"kakaoAccessToken\":\"bad\"}")
                .post("/v1/auth/kakao").then().statusCode(401)
                .body("error", equalTo("invalid_kakao_token"));
    }

    @Test
    public void kakao_토큰누락_400() {
        given().contentType(ContentType.JSON).body("{}")
                .post("/v1/auth/kakao").then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }
}
