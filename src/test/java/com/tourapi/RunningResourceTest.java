package com.tourapi;

import io.quarkus.test.junit.QuarkusTest;
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
    public void routes_경유지없으면_400() {
        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},\"waypoints\":[]}")
                .when().post("/v1/running/routes")
                .then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }

    @Test
    public void routes_경유지6개면_400() {
        String wp = "{\"lat\":37.56,\"lng\":126.97}";
        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},\"waypoints\":["
                        + wp + "," + wp + "," + wp + "," + wp + "," + wp + "," + wp + "]}")
                .when().post("/v1/running/routes")
                .then().statusCode(400);
    }
}
