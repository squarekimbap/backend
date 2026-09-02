package com.tourapi;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

/**
 * admin.emails를 설정하지 않은 상태(= 기본 application.properties). 이 경우 관리 API는
 * 누구에게도 열리지 않아야 한다 — 설정을 깜빡한 채 배포해도 안전하도록 기본이 잠금이다.
 */
@QuarkusTest
public class AdminLockedByDefaultTest {

    @Test
    @TestSecurity(user = "u-1", roles = {})
    @JwtSecurity(claims = {
            @Claim(key = "sub", value = "u-1"),
            @Claim(key = "email", value = "anyone@example.com"),
            @Claim(key = "email_verified", value = "true")})
    public void 허용목록_미설정이면_아무도_못_들어온다() {
        RestAssured.when().get("/v1/admin/users").then().statusCode(403);
        RestAssured.when().get("/v1/admin/courses/review").then().statusCode(403);
    }
}
