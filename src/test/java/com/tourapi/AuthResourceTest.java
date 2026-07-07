package com.tourapi;

import com.tourapi.lib.CognitoAuth;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CodeMismatchException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 인증 라우트 계약 테스트. CognitoAuth를 목으로 바꿔 AWS 없이
 * "요청 검증 + Cognito 예외 → HTTP 매핑"을 검증한다.
 */
@QuarkusTest
public class AuthResourceTest {

    @InjectMock
    CognitoAuth cognito;

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
}
