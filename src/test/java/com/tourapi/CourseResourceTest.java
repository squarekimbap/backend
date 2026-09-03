package com.tourapi;

import com.tourapi.lib.Geo;
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
import java.util.Map;

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
    public void 카탈로그_전체_로드() {
        Assertions.assertEquals(69, catalog.list(null).size());
        Assertions.assertNotNull(catalog.list(null).get(0).get("id"));
        Assertions.assertNotNull(catalog.list(null).get(0).get("headline"));
        Assertions.assertTrue(catalog.list(null).get(0).path("waypoints").isArray());
        Assertions.assertFalse(catalog.list(null).get(0).path("waypoints").isEmpty());
        for (var summary : catalog.list(null)) {
            var detailPoi = catalog.byId(summary.path("id").asText()).path("poi");
            Assertions.assertEquals(detailPoi.size(), summary.path("waypoints").size(),
                    "목록 waypoints와 상세 poi 개수 불일치: " + summary.path("id").asText());
            for (int i = 0; i < detailPoi.size(); i++) {
                Assertions.assertEquals(detailPoi.get(i).path("n").asText(),
                        summary.path("waypoints").get(i).asText(),
                        "목록 waypoints와 상세 poi 이름·순서 불일치: " + summary.path("id").asText());
            }
            for (var waypoint : summary.path("waypoints")) {
                Assertions.assertTrue(waypoint.isTextual(),
                        "목록 waypoints는 이름 문자열이어야 함: " + summary.path("id").asText());
            }
            Assertions.assertTrue(catalog.byId(summary.path("id").asText()).path("poi").get(0).isObject(),
                    "상세 poi는 객체여야 함: " + summary.path("id").asText());
        }
    }

    @Test
    public void 구코스_id_10개는_확인된_신코스로_조회된다() {
        Map<String, String> aliases = Map.ofEntries(
                Map.entry("seoul-banpo-night", "seoul-banpo-10k"),
                Map.entry("seoul-namsan", "seoul-namsan-loop"),
                Map.entry("busan-gwangalli", "busan-gwangalli-night"),
                Map.entry("busan-songdo", "busan-songdo-cloud"),
                Map.entry("gyeongju-bomun", "gyeongju-bomunho"),
                Map.entry("gangneung-anmok", "gangneung-anmok-sunrise"),
                Map.entry("jeju-yongduam", "jeju-yongdam-coast"),
                Map.entry("seoul-yeouido", "seoul-yeouido-5k"),
                Map.entry("seoul-gyeongbok", "seoul-gyeongbokgung-wall"),
                Map.entry("seoul-olympic", "seoul-olympic-loop")
        );

        aliases.forEach((legacyId, canonicalId) -> {
            var course = catalog.byId(legacyId);
            Assertions.assertNotNull(course, "구 ID 조회 실패: " + legacyId);
            Assertions.assertEquals(canonicalId, course.path("id").asText(), "구 ID 정규화 실패: " + legacyId);
        });
    }

    @Test
    public void 확인되지_않은_구코스_id는_다른코스로_추측하지_않는다() {
        // gyeongju-daereungwon 은 2026-09-03에 실제 코스로 추가돼 여기서 뺐다
        Assertions.assertNull(catalog.byId("seoul-nodeul"));
        Assertions.assertNull(catalog.byId("jeju-seogwipo"));
        Assertions.assertNull(catalog.byId("busan-gwangan-bridge"));
    }

    @Test
    public void 대표사진이_없던_다섯코스에_출처있는_사진이_있다() {
        for (String id : new String[]{
                "gwangju-yeongsangang",
                "yeosu-old-railroad",
                "anyang-pyeongchon-freedom",
                "anyang-hakuicheon",
                "osan-osancheon"
        }) {
            var course = catalog.byId(id);
            Assertions.assertTrue(course.path("photo").asText().startsWith("https://"), "사진 누락: " + id);
            Assertions.assertFalse(course.path("photoTitle").asText().isBlank(), "사진 제목 누락: " + id);
            Assertions.assertFalse(course.path("photoLicense").asText().isBlank(), "사진 출처 누락: " + id);
        }
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
    public void OpenAPI_목록_waypoints는_문자열배열이다() throws Exception {
        String body = RestAssured.given()
                .queryParam("format", "json")
                .when().get("/q/openapi")
                .then().statusCode(200)
                .extract().asString();
        var properties = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                .path("components").path("schemas").path("CourseListItem").path("properties");
        var waypoints = properties.path("waypoints");
        Assertions.assertEquals("array", waypoints.path("type").asText());
        Assertions.assertEquals("string", waypoints.path("items").path("type").asText());
        for (String property : new String[]{"photo", "photoTitle", "photoLicense"}) {
            var photoField = properties.path(property);
            boolean nullable = photoField.path("nullable").asBoolean()
                    || (photoField.path("type").isArray() && photoField.path("type").toString().contains("null"));
            Assertions.assertTrue(nullable, property + " nullable 계약 누락: " + photoField.toPrettyString());
        }
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
    public void 편집원고는_확인한_출처와_확인일을_함께_보관한다() {
        var course = catalog.byId("seoul-namsan-loop");
        var sources = course.path("sources");
        Assertions.assertTrue(sources.isArray() && sources.size() >= 2);
        for (var source : sources) {
            Assertions.assertFalse(source.path("title").asText().isBlank());
            Assertions.assertTrue(source.path("url").asText().startsWith("https://"));
            Assertions.assertTrue(source.path("type").asText().equals("official")
                    || source.path("type").asText().equals("community"));
            Assertions.assertEquals("2026-09-01", source.path("checkedAt").asText());
        }
    }

    @Test
    public void 근거가_없거나_시효가_지난_대표_표현은_노출하지_않는다() {
        for (var summary : catalog.list(null)) {
            var course = catalog.byId(summary.path("id").asText());
            String copy = course.path("headline").asText() + " "
                    + course.path("subhead").asText() + " "
                    + course.path("body") + " " + course.path("deep") + " "
                    + course.path("ops") + " " + course.path("poi") + " "
                    + course.path("checkpoints");
            Assertions.assertFalse(copy.contains("서울 양대 마라톤의 출발선"));
            Assertions.assertFalse(copy.contains("한강을 가로질러 달릴 수 있는 사실상 유일한 다리"));
            Assertions.assertFalse(copy.contains("내리막은 차로 내려와 2회전 하는 방식이 현지 표준"));
        }
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

            String shape = course.path("shape").asText();
            Assertions.assertTrue(shape.equals("roundTrip") || shape.equals("oneWay"),
                    "shape 누락/오류: " + id);
            Assertions.assertEquals(shape.equals("roundTrip") ? "loop" : "oneway",
                    course.path("routeShape").asText(), "shape과 routeShape 불일치: " + id);

            Assertions.assertTrue(polyline.isArray() && polyline.size() >= 2 && polyline.size() <= 200,
                    "polyline 누락/크기 오류: " + id);
            Assertions.assertTrue(guide.isArray() && !guide.isEmpty(), "guide 누락: " + id);
            Assertions.assertTrue(checkpoints.isArray() && !checkpoints.isEmpty(),
                    "checkpoints 누락: " + id);
            // 경유지는 전부 checkpoint 여야 하되, 그 사이에 오디 도슨트가 더 끼어들 수 있다
            Assertions.assertTrue(checkpoints.size() >= course.path("poi").size(),
                    "모든 실제 경유지는 checkpoint여야 함: " + id);

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
                // 앱이 http를 https로 강제 치환하므로(ATS) http 주소는 재생이 실패한다
                String audioUrl = item.path("audioUrl").asText("");
                Assertions.assertTrue(audioUrl.isEmpty() || audioUrl.startsWith("https://"),
                        "checkpoint 오디오가 https가 아님: " + id);
                // 오디 규격은 jp다. ja로 넣으면 앱이 일본어를 못 찾는다
                Assertions.assertFalse(item.path("audio").has("ja"),
                        "언어 키는 jp여야 함(ja 발견): " + id);
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
        var trackPoints = document.getElementsByTagNameNS(namespace, "trkpt");
        var sourcePath = catalog.byId("busan-haeundae").path("polyline");
        Assertions.assertEquals(sourcePath.size(), trackPoints.getLength(),
                "GPX는 제목만이 아니라 서버 polyline 전체를 Track 좌표로 내려줘야 함");
        var firstTrackPoint = (org.w3c.dom.Element) trackPoints.item(0);
        Assertions.assertEquals(sourcePath.get(0).get(0).asText(), firstTrackPoint.getAttribute("lat"));
        Assertions.assertEquals(sourcePath.get(0).get(1).asText(), firstTrackPoint.getAttribute("lon"));
        Assertions.assertTrue(document.getElementsByTagNameNS(namespace, "wpt").getLength() > 0);
    }

    @Test
    public void 구코스_id의_GPX파일명은_신id로_정규화된다() {
        Response response = courseResource.gpx("seoul-banpo-night");
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals("attachment; filename=\"seoul-banpo-10k.gpx\"",
                response.getHeaderString("Content-Disposition"));
    }

    @Test
    public void 없는코스의_GPX는404() {
        RestAssured.when().get("/v1/courses/no-such-course/gpx")
                .then().statusCode(404)
                .contentType(ContentType.JSON)
                .body("error", equalTo("not_found"));
    }

    @Test
    public void 한코스에_좌표가같은_경유지가없다() {
        // '한남대교'가 동명 술집으로, '진밭골자연휴양림'이 '진밭골'과 같은 점으로 박혔던 회귀를 막는다.
        // 지오코더는 같은 검색어에 서로 다른 지점을 주므로 마커가 겹치면 둘 중 하나는 틀린 것이다.
        for (var summary : catalog.list(null)) {
            String id = summary.path("id").asText();
            var seen = new java.util.HashSet<String>();
            for (var poi : catalog.byId(id).path("poi")) {
                String key = String.format("%.6f,%.6f", poi.path("lat").asDouble(), poi.path("lng").asDouble());
                Assertions.assertTrue(seen.add(key),
                        "경유지 좌표 중복: " + id + " / " + poi.path("n").asText());
            }
        }
    }

    @Test
    public void 모든_경유지가_코스경로_100m안에있다() {
        // poi는 실제로 지나는 곳만 둔다. 멀리서 보는 장소는 landmarks로 분리한다.
        for (var summary : catalog.list(null)) {
            String id = summary.path("id").asText();
            var course = catalog.byId(id);
            var path = new java.util.ArrayList<double[]>();
            for (var point : course.path("polyline")) {
                path.add(new double[]{point.get(0).asDouble(), point.get(1).asDouble()});
            }
            for (var poi : course.path("poi")) {
                double d = Geo.distanceToPathMeters(poi.path("lat").asDouble(), poi.path("lng").asDouble(), path);
                Assertions.assertTrue(d <= 100,
                        String.format("경유지가 경로에서 %.0fm 떨어짐: %s / %s", d, id, poi.path("n").asText()));
            }
        }
    }

    @Test
    public void 표기거리가_실제경로길이와_같다() {
        // 앱은 km를 보여주고 사용자는 distanceM 경로를 뛴다. 둘이 어긋나면 10km인 줄 알고 8.4km를 뛰게 된다.
        // 허용치 50m = km를 0.1 단위로 표기하면서 생기는 반올림 폭.
        for (var summary : catalog.list(null)) {
            String id = summary.path("id").asText();
            var course = catalog.byId(id);
            double gap = Math.abs(course.path("distanceM").asDouble() - course.path("km").asDouble() * 1000);
            Assertions.assertTrue(gap <= 50,
                    String.format("표기 거리와 경로 길이가 %.0fm 다름: %s (km=%s, distanceM=%s)",
                            gap, id, course.path("km").asText(), course.path("distanceM").asText()));
        }
    }

    @Test
    public void 코스형태_거리_안내좌표가_경로와일치한다() {
        for (var summary : catalog.list(null)) {
            String id = summary.path("id").asText();
            var course = catalog.byId(id);
            var polyline = course.path("polyline");
            var path = new java.util.ArrayList<double[]>();
            double geometryM = 0;
            for (var point : polyline) {
                path.add(new double[]{point.get(0).asDouble(), point.get(1).asDouble()});
                if (path.size() > 1) {
                    double[] before = path.get(path.size() - 2);
                    double[] current = path.get(path.size() - 1);
                    geometryM += Geo.haversineMeters(before[0], before[1], current[0], current[1]);
                }
            }

            double displayM = course.path("km").asDouble() * 1000;
            Assertions.assertTrue(Math.abs(geometryM - displayM) / displayM <= 0.10,
                    String.format("polyline 기하길이와 km 오차 10%% 초과: %s (%.0fm/%.0fm)",
                            id, geometryM, displayM));

            String shape = course.path("shape").asText();
            double[] first = path.get(0);
            double[] last = path.get(path.size() - 1);
            double startEndM = Geo.haversineMeters(first[0], first[1], last[0], last[1]);
            if (shape.equals("roundTrip")) {
                Assertions.assertTrue(startEndM <= 100,
                        String.format("roundTrip이 닫히지 않음: %s (%.0fm)", id, startEndM));
            }

            for (var guide : course.path("guide")) {
                double distance = Geo.distanceToPathMeters(
                        guide.path("lat").asDouble(), guide.path("lng").asDouble(), path);
                Assertions.assertTrue(distance <= 1,
                        String.format("guide가 polyline 위에 없음: %s (%.1fm)", id, distance));
            }
            if (shape.equals("oneWay")) {
                var arrival = course.path("guide").get(course.path("guide").size() - 1);
                double finishM = Geo.haversineMeters(last[0], last[1],
                        arrival.path("lat").asDouble(), arrival.path("lng").asDouble());
                Assertions.assertTrue(finishM <= 1,
                        String.format("oneWay 종점과 마지막 guide 불일치: %s (%.1fm)", id, finishM));
            }
        }
    }

    @Test
    public void 경유지와랜드마크가분리되고_경유지주소가검증돼있다() {
        var suspiciousAddress = java.util.regex.Pattern.compile("(?:\\d+층|\\d+호(?:\\D|$)|상가|오피스텔|빌딩)");
        for (var summary : catalog.list(null)) {
            String id = summary.path("id").asText();
            var course = catalog.byId(id);
            var poiNames = new java.util.HashSet<String>();
            for (var poi : course.path("poi")) {
                String name = poi.path("n").asText();
                poiNames.add(name);
                String address = poi.path("addr").asText("").trim();
                Assertions.assertFalse(address.isBlank(), "POI 주소 누락: " + id + " / " + name);
                Assertions.assertFalse(suspiciousAddress.matcher(address).find(),
                        "동명 업소로 의심되는 POI 주소: " + id + " / " + name + " / " + address);
            }
            for (var landmark : course.path("landmarks")) {
                Assertions.assertFalse(poiNames.contains(landmark.path("n").asText()),
                        "POI와 landmark 중복: " + id + " / " + landmark.path("n").asText());
            }
        }
    }

    @Test
    public void 전체64개_GPX트랙이_polyline과정확히같다() throws Exception {
        String namespace = "http://www.topografix.com/GPX/1/1";
        for (var summary : catalog.list(null)) {
            String id = summary.path("id").asText();
            Response response = courseResource.gpx(id);
            Assertions.assertEquals(200, response.getStatus(), "GPX 생성 실패: " + id);
            String gpx = (String) response.getEntity();
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(gpx.getBytes(StandardCharsets.UTF_8)));
            var trackPoints = document.getElementsByTagNameNS(namespace, "trkpt");
            var sourcePath = catalog.byId(id).path("polyline");
            Assertions.assertEquals(sourcePath.size(), trackPoints.getLength(), "GPX 점 개수 불일치: " + id);
            for (int index = 0; index < sourcePath.size(); index++) {
                var point = (org.w3c.dom.Element) trackPoints.item(index);
                Assertions.assertEquals(sourcePath.get(index).get(0).asText(), point.getAttribute("lat"),
                        "GPX 위도 불일치: " + id + " / " + index);
                Assertions.assertEquals(sourcePath.get(index).get(1).asText(), point.getAttribute("lon"),
                        "GPX 경도 불일치: " + id + " / " + index);
            }
        }
    }
}
