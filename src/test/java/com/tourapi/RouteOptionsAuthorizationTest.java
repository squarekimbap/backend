package com.tourapi;

import com.tourapi.services.RunningGenerationRateLimiter;
import com.tourapi.services.RunningService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

@QuarkusTest
class RouteOptionsAuthorizationTest {

    @InjectMock
    RunningService runningService;

    @InjectMock
    RunningGenerationRateLimiter rateLimiter;

    @Test
    @TestSecurity(user = "limited-user", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "limited-user")})
    void 분당한도를넘으면_429() {
        when(rateLimiter.allow("limited-user")).thenReturn(false);

        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[{\"lat\":37.56,\"lng\":126.97}],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(429)
                .header("Retry-After", "60")
                .body("error", equalTo("rate_limited"));
    }

    @Test
    void 기존Routes도토큰없으면_401() {
        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"waypoints\":[{\"lat\":37.56,\"lng\":126.97}]}")
                .when().post("/v1/running/routes")
                .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "legacy-limited", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "legacy-limited")})
    void 기존Routes도공통분당한도를적용한다() {
        when(rateLimiter.allow("legacy-limited")).thenReturn(false);

        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"waypoints\":[{\"lat\":37.56,\"lng\":126.97}]}")
                .when().post("/v1/running/routes")
                .then().statusCode(429)
                .body("error", equalTo("rate_limited"));
    }
}
