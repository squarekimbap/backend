package com.tourapi;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
public class Greeting2Test {

    @Test
    public void hello2_응답() {
        RestAssured.when().get("/hello2").then()
                .statusCode(200)
                .contentType("text/plain")
                .body(equalTo("hello2 from github actions"));
    }
}
