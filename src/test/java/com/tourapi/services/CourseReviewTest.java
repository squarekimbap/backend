package com.tourapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 검수 계산. 실제 번들 64개를 그대로 쓴다 — 여기서 값이 어긋나면 화면이 잘못된 코스를
 * 짚거나, 반대로 잘못된 코스를 놓친다.
 *
 * <p>HTTP가 아니라 서비스로 부르는 이유: 전체 목록 응답이 커서 테스트 서버가 못 닫는다
 * (코스 목록과 같은 함정 — CLAUDE.md 참고).
 */
@QuarkusTest
public class CourseReviewTest {

    @Inject
    CourseReview review;

    private static List<JsonNode> list(ArrayNode a) {
        return StreamSupport.stream(a.spliterator(), false).toList();
    }

    private JsonNode course(String id) {
        return list(review.summaries()).stream()
                .filter(c -> id.equals(c.path("id").asText())).findFirst().orElseThrow();
    }

    private static List<String> kinds(JsonNode c) {
        return StreamSupport.stream(c.path("flags").spliterator(), false)
                .map(f -> f.path("kind").asText()).toList();
    }

    @Test
    public void 코스_64개에_sigil이_붙는다() {
        List<JsonNode> all = list(review.summaries());
        assertEquals(64, all.size());
        for (JsonNode c : all) {
            assertFalse(c.path("sigil").isEmpty(), c.path("id").asText() + ": sigil 비어 있음");
            assertTrue(c.path("sigil").size() <= CourseReview.SIGIL_POINTS);
            assertEquals(2, c.path("sigil").get(0).size()); // [lat, lng]
        }
    }

    @Test
    public void 경유지는_전부_경로_100m_안이라_off_route가_없다() {
        // 이 불변이 깨지면 validate_courses.py의 배포 게이트도 같이 깨진 것이다
        assertTrue(list(review.summaries()).stream().noneMatch(c -> kinds(c).contains("off-route")));
    }

    @Test
    public void 제3유형_저작권을_잡아낸다() {
        // 동탄은 사진 교체 때 라벨을 제3유형으로 바로잡았다
        assertTrue(kinds(course("hwaseong-dongtan-lake")).contains("license"));
        assertFalse(kinds(course("seoul-wirye-humanring")).contains("license"));
    }

    @Test
    public void 같은_사진을_쓰는_코스는_서로를_가리킨다() {
        JsonNode c = course("changwon-yongji");
        assertTrue(kinds(c).contains("dup-photo"));
        String label = StreamSupport.stream(c.path("flags").spliterator(), false)
                .filter(f -> "dup-photo".equals(f.path("kind").asText()))
                .findFirst().orElseThrow().path("label").asText();
        assertTrue(label.contains("changwon-jinhae-dreamroad"), label);
    }

    @Test
    public void 대체_사진은_photoTitle의_괄호로_알아본다() {
        assertTrue(kinds(course("daegu-jinbatgol-uphill")).contains("substitute"));
    }

    @Test
    public void 상세는_경유지의_경로_이탈_거리를_잰다() {
        ObjectNode d = review.detail("seoul-ttukseom-7k");
        JsonNode poi = d.path("poi");
        assertEquals(3, poi.size());
        for (JsonNode p : poi) {
            assertEquals(0, p.path("offRouteM").asInt(), p.path("name").asText() + " 는 경로 위여야 한다");
        }
        // 청담대교는 다리 중앙 좌표라 원 장소가 경로에서 멀다 — 앱이 이걸 핀에 쓰면 어긋난다
        JsonNode bridge = poi.get(1);
        assertTrue(bridge.path("place").path("offRouteM").asInt() > 100,
                "원 좌표가 멀다는 사실이 상세에 드러나야 한다");
    }

    @Test
    public void 없는_코스는_null() {
        assertNull(review.detail("no-such-course"));
    }
}
