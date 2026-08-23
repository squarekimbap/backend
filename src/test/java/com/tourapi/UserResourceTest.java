package com.tourapi;

import com.tourapi.lib.CognitoAuth;
import com.tourapi.lib.UserStore;
import com.tourapi.model.UserProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JWT 보호 동작 검증. @TestSecurity+@JwtSecurity로 가짜 인증 컨텍스트를 주입하고,
 * UserStore는 목으로 바꿔 DynamoDB 없이 검증한다.
 */
@QuarkusTest
public class UserResourceTest {

    @InjectMock
    UserStore userStore;

    @InjectMock
    CognitoAuth cognito;

    @Test
    public void 토큰없으면_401() {
        RestAssured.when().get("/v1/users/me").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "u-1", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "u-1")})
    public void 토큰있으면_프로필_반환() {
        when(userStore.get("u-1")).thenReturn(
                new UserProfile("u-1", "a@b.c", "nick", "email", "2026-07-03T00:00:00Z"));
        RestAssured.when().get("/v1/users/me").then().statusCode(200)
                .body("userId", equalTo("u-1"))
                .body("nickname", equalTo("nick"))
                .body("provider", equalTo("email"));
    }

    @Test
    @TestSecurity(user = "u-2", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "u-2")})
    public void 프로필없으면_404() {
        when(userStore.get("u-2")).thenReturn(null);
        RestAssured.when().get("/v1/users/me").then().statusCode(404)
                .body("error", equalTo("profile_not_found"));
    }

    @Test
    public void 수정_토큰없으면_401() {
        given().contentType(ContentType.JSON).body("{\"nickname\":\"새닉\"}")
                .patch("/v1/users/me").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "u-1", roles = {})
    @JwtSecurity(claims = {
            @Claim(key = "sub", value = "u-1"),
            @Claim(key = "cognito:username", value = "email_abc")})
    public void 닉네임_수정시_Cognito와_테이블_모두_갱신() {
        when(userStore.updateNickname("u-1", "새닉")).thenReturn(true);
        when(userStore.get("u-1")).thenReturn(
                new UserProfile("u-1", "a@b.c", "새닉", "email", "2026-07-03T00:00:00Z"));
        given().contentType(ContentType.JSON).body("{\"nickname\":\"  새닉  \"}")
                .patch("/v1/users/me").then().statusCode(200)
                .body("nickname", equalTo("새닉"));
        verify(cognito).updateNickname("email_abc", "새닉"); // username 클레임을 그대로 씀
    }

    @Test
    @TestSecurity(user = "u-9", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "u-9")})
    public void username_클레임_없으면_sub로_대체() {
        when(userStore.updateNickname("u-9", "닉")).thenReturn(true);
        when(userStore.get("u-9")).thenReturn(
                new UserProfile("u-9", null, "닉", "kakao", "2026-07-03T00:00:00Z"));
        given().contentType(ContentType.JSON).body("{\"nickname\":\"닉\"}")
                .patch("/v1/users/me").then().statusCode(200);
        verify(cognito).updateNickname("u-9", "닉");
    }

    @Test
    @TestSecurity(user = "u-2", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "u-2")})
    public void 수정_프로필없으면_404() {
        when(userStore.updateNickname("u-2", "닉")).thenReturn(false);
        given().contentType(ContentType.JSON).body("{\"nickname\":\"닉\"}")
                .patch("/v1/users/me").then().statusCode(404)
                .body("error", equalTo("profile_not_found"));
    }

    @Test
    @TestSecurity(user = "u-1", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "u-1")})
    public void 수정_닉네임_비면_400() {
        given().contentType(ContentType.JSON).body("{\"nickname\":\"   \"}")
                .patch("/v1/users/me").then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }

    @Test
    @TestSecurity(user = "u-1", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "u-1")})
    public void 수정_닉네임_길면_400() {
        given().contentType(ContentType.JSON).body("{\"nickname\":\"012345678901234567890\"}")
                .patch("/v1/users/me").then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }
}
