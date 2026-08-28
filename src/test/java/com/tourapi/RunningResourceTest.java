package com.tourapi;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

/** 인증키/업스트림 없이 도는 검증 테스트. */
@QuarkusTest
public class RunningResourceTest {

    @Test
    public void candidates_lat누락_400() {
        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"lng\":126.978,\"distanceKm\":5}")
                .when().post("/v1/running/candidates")
                .then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }

    @Test
    public void candidates_shape이상하면_400() {
        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"lat\":37.5665,\"lng\":126.978,\"distanceKm\":5,\"shape\":\"zigzag\"}")
                .when().post("/v1/running/candidates")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "routes-empty", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "routes-empty")})
    public void routes_경유지없으면_400() {
        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},\"waypoints\":[]}")
                .when().post("/v1/running/routes")
                .then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }

    @Test
    @TestSecurity(user = "routes-six", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "routes-six")})
    public void routes_경유지6개면_400() {
        String wp = "{\"lat\":37.56,\"lng\":126.97}";
        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},\"waypoints\":["
                        + wp + "," + wp + "," + wp + "," + wp + "," + wp + "," + wp + "]}")
                .when().post("/v1/running/routes")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "routes-null", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "routes-null")})
    public void routes_경유지null이면_400() {
        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},\"waypoints\":[null]}")
                .when().post("/v1/running/routes")
                .then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }

    @Test
    public void routeOptions_토큰없으면_401() {
        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[{\"lat\":37.56,\"lng\":126.97}],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "route-empty", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "route-empty")})
    public void routeOptions_후보가하나도없으면_400() {
        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"selectedWaypoints\":[],\"candidateWaypoints\":[],\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }

    @Test
    @TestSecurity(user = "route-distance", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "route-distance")})
    public void routeOptions_목표거리누락이면_400() {
        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"selectedWaypoints\":[{\"lat\":37.56,\"lng\":126.97}]}")
                .when().post("/v1/running/route-options")
                .then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }

    @Test
    public void summary_코스경로없으면_400() {
        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"option\":{\"course\":{\"path\":[]}}}")
                .when().post("/v1/running/summary")
                .then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }
}
