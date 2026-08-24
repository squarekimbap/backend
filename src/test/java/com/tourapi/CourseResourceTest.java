package com.tourapi;

import com.tourapi.services.CourseCatalog;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * 번들 데이터만 쓰므로 외부 의존 없이 도는 테스트.
 * ⚠️ 전체 목록(24KB)은 HTTP로 검증하지 않는다 — 테스트용 MockEventServer가
 * 큰 청크 응답을 종료하지 않아 read timeout(실서버·dev는 정상, 라이브 스모크로 확인).
 */
@QuarkusTest
public class CourseResourceTest {

    @Inject
    CourseCatalog catalog;

    @Test
    public void 카탈로그_전체_42개_로드() {
        Assertions.assertEquals(42, catalog.list(null).size());
        Assertions.assertNotNull(catalog.list(null).get(0).get("id"));
        Assertions.assertNotNull(catalog.list(null).get(0).get("headline"));
    }

    @Test
    public void 목록_도시명_필터() {
        RestAssured.when().get("/v1/courses?city=서울")
                .then().statusCode(200)
                .body("count", greaterThan(0))
                .body("items[0].city", equalTo("서울"));
    }

    @Test
    public void 목록_cityId_필터() {
        RestAssured.when().get("/v1/courses?city=busan")
                .then().statusCode(200)
                .body("count", greaterThan(0))
                .body("items[0].cityId", equalTo("busan"));
    }

    @Test
    public void 상세_조회() {
        RestAssured.when().get("/v1/courses/busan-haeundae")
                .then().statusCode(200)
                .body("id", equalTo("busan-haeundae"))
                .body("body", notNullValue())
                .body("poi[0].n", notNullValue());
    }

    @Test
    public void 없는_id_404() {
        RestAssured.when().get("/v1/courses/no-such-course")
                .then().statusCode(404)
                .body("error", equalTo("not_found"));
    }
}
