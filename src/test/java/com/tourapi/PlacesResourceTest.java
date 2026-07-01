package com.tourapi;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

/**
 * 인증키 없이도 도는 검증 테스트(업스트림 호출 전 400 처리).
 */
@QuarkusTest
public class PlacesResourceTest {

    @Test
    public void latLng_누락시_400() {
        RestAssured.when().get("/v1/tour/places")
                .then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }

    @Test
    public void lat_범위벗어나면_400() {
        RestAssured.when().get("/v1/tour/places?lat=999&lng=127")
                .then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }

    @Test
    public void lat_숫자아니면_400() {
        RestAssured.when().get("/v1/tour/places?lat=abc&lng=127")
                .then().statusCode(400)
                .body("error", equalTo("bad_request"));
    }
}
