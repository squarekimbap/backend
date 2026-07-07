package com.tourapi;

import com.tourapi.lib.UserStore;
import com.tourapi.model.UserProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

/**
 * JWT 보호 동작 검증. @TestSecurity+@JwtSecurity로 가짜 인증 컨텍스트를 주입하고,
 * UserStore는 목으로 바꿔 DynamoDB 없이 검증한다.
 */
@QuarkusTest
public class UserResourceTest {

    @InjectMock
    UserStore userStore;

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
}
