package com.tourapi;

import com.tourapi.services.CourseCatalog;
import com.tourapi.routes.CourseResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

/**
 * 번들 데이터만 쓰므로 외부 의존 없이 도는 테스트.
 * ⚠️ 경로가 포함된 전체 목록·상세는 HTTP로 검증하지 않는다 — 테스트용 MockEventServer가
 * 큰 청크 응답을 종료하지 않아 read timeout(실서버·dev는 정상, 라이브 스모크로 확인).
 */
@QuarkusTest
public class CourseResourceTest {

    @Inject
    CourseCatalog catalog;

    @Inject
    CourseResource courseResource;

    @Test
    public void 카탈로그_전체_64개_로드() {
        Assertions.assertEquals(64, catalog.list(null).size());
        Assertions.assertNotNull(catalog.list(null).get(0).get("id"));
        Assertions.assertNotNull(catalog.list(null).get(0).get("headline"));
        Assertions.assertTrue(catalog.list(null).get(0).path("waypoints").isArray());
        Assertions.assertFalse(catalog.list(null).get(0).path("waypoints").isEmpty());
    }

    @Test
    public void 러닝갤_신규코스_상세() {
        var course = catalog.byId("seoul-seokchon-lake");
        Assertions.assertEquals("러너 후기 · 러닝갤", course.path("src").asText());
        Assertions.assertFalse(course.path("poi").get(0).path("naver").asText().isBlank());
    }

    @Test
    public void 목록_도시명_필터() {
        // 코스 많은 도시(서울 등)는 응답이 MockEventServer 임계 크기를 넘어 hang — 작은 도시로 검증
        RestAssured.when().get("/v1/courses?city=속초")
                .then().statusCode(200)
                .body("count", greaterThan(0))
                .body("items[0].city", equalTo("속초"));
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
        var course = catalog.byId("busan-haeundae");
        Assertions.assertEquals("busan-haeundae", course.path("id").asText());
        Assertions.assertTrue(course.path("body").isArray());
        Assertions.assertFalse(course.path("poi").get(0).path("n").asText().isBlank());
        Assertions.assertTrue(course.path("polyline").size() > 1);
        Assertions.assertFalse(course.path("guide").isEmpty());
        Assertions.assertFalse(course.path("checkpoints").isEmpty());
    }

    @Test
    public void 상세에_공유url과_poi_보강필드() {
        var course = catalog.byId("seoul-banpo-10k");
        Assertions.assertEquals(
                "https://akt4wffwphw5czb3ofr2hy4hhm0emmil.lambda-url.ap-northeast-2.on.aws/v1/courses/seoul-banpo-10k",
                course.path("url").asText());
        Assertions.assertFalse(course.path("poi").get(0).path("naver").asText().isBlank());
    }

    @Test
    public void 주변맛집_없는_id_404() {
        RestAssured.when().get("/v1/courses/no-such-course/nearby")
                .then().statusCode(404)
                .body("error", equalTo("not_found"));
    }

    @Test
    public void 모든_코스에_대표좌표가_있다() {
        // 주변 맛집 기준점이 되는 좌표. 없으면 그 코스만 조용히 빈 목록이 되므로 회귀를 막는다
        for (var course : catalog.list(null)) {
            Assertions.assertTrue(course.hasNonNull("lat") && course.hasNonNull("lng"),
                    "코스 좌표 누락: " + course.path("id").asText());
        }
    }

    @Test
    public void 모든_코스에_시작가능한_경로와_도슨트가_있다() {
        for (var summary : catalog.list(null)) {
            String id = summary.path("id").asText();
            var course = catalog.byId(id);
            var polyline = course.path("polyline");
            var guide = course.path("guide");
            var checkpoints = course.path("checkpoints");

            Assertions.assertTrue(polyline.isArray() && polyline.size() >= 2 && polyline.size() <= 200,
                    "polyline 누락/크기 오류: " + id);
            Assertions.assertTrue(guide.isArray() && !guide.isEmpty(), "guide 누락: " + id);
            Assertions.assertTrue(checkpoints.isArray() && !checkpoints.isEmpty(),
                    "checkpoints 누락: " + id);

            for (var point : polyline) {
                Assertions.assertEquals(2, point.size(), "polyline 좌표 형식 오류: " + id);
                Assertions.assertTrue(point.get(0).asDouble() >= -90 && point.get(0).asDouble() <= 90,
                        "polyline 위도 오류: " + id);
                Assertions.assertTrue(point.get(1).asDouble() >= -180 && point.get(1).asDouble() <= 180,
                        "polyline 경도 오류: " + id);
            }
            for (var item : checkpoints) {
                Assertions.assertFalse(item.path("id").asText().isBlank(), "checkpoint id 누락: " + id);
                Assertions.assertFalse(item.path("name").asText().isBlank(), "checkpoint name 누락: " + id);
                Assertions.assertFalse(item.path("description").asText().isBlank(),
                        "checkpoint description 누락: " + id);
                Assertions.assertTrue(item.path("audioSeconds").asInt() > 0,
                        "checkpoint audioSeconds 오류: " + id);
            }
        }
    }

    @Test
    public void 모든_코스에_경유지_좌표가_두_곳_이상_있다() {
        for (var summary : catalog.list(null)) {
            String id = summary.path("id").asText();
            int coordinateCount = 0;
            for (var poi : catalog.byId(id).path("poi")) {
                if (poi.hasNonNull("lat") && poi.hasNonNull("lng")) {
                    coordinateCount++;
                }
            }
            Assertions.assertTrue(coordinateCount >= 2, "경유지 좌표 2곳 미만: " + id);
        }
    }

    @Test
    public void 없는_id_404() {
        RestAssured.when().get("/v1/courses/no-such-course")
                .then().statusCode(404)
                .body("error", equalTo("not_found"));
    }

    @Test
    public void Garmin용GPX는_표준Track과경유지를내려준다() throws Exception {
        // Mock Lambda 서버는 큰 청크 응답을 종료하지 않는 문제가 있어 리소스를 직접 검증한다.
        Response response = courseResource.gpx("busan-haeundae");
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("application/gpx+xml;charset=UTF-8",
                response.getMediaType().toString());
        Assertions.assertEquals("attachment; filename=\"busan-haeundae.gpx\"",
                response.getHeaderString("Content-Disposition"));
        String gpx = (String) response.getEntity();

        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var document = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(gpx.getBytes(StandardCharsets.UTF_8)));
        String namespace = "http://www.topografix.com/GPX/1/1";
        Assertions.assertEquals("gpx", document.getDocumentElement().getLocalName());
        Assertions.assertEquals(namespace, document.getDocumentElement().getNamespaceURI());
        Assertions.assertTrue(document.getElementsByTagNameNS(namespace, "trk").getLength() > 0);
        Assertions.assertTrue(document.getElementsByTagNameNS(namespace, "trkseg").getLength() > 0);
        Assertions.assertTrue(document.getElementsByTagNameNS(namespace, "trkpt").getLength() > 1);
        Assertions.assertTrue(document.getElementsByTagNameNS(namespace, "wpt").getLength() > 0);
    }

    @Test
    public void 없는코스의_GPX는404() {
        RestAssured.when().get("/v1/courses/no-such-course/gpx")
                .then().statusCode(404)
                .contentType(ContentType.JSON)
                .body("error", equalTo("not_found"));
    }
}
