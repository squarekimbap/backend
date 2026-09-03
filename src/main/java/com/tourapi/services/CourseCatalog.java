package com.tourapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 편집 코스 카탈로그(어디 뛰지 수집본). 원본은 running-courses/data/*.json —
 * build.py 산출물 courses.json을 리소스로 번들한다(정적 데이터, DB 불필요).
 * 갱신 절차: running-courses에서 재생성 → src/main/resources/data/courses.json 교체 → 배포.
 */
@ApplicationScoped
public class CourseCatalog {

    /** 스토어 출시 전 iOS 번들에 저장됐던 ID 중 현재 카탈로그와 동일한 코스. */
    private static final Map<String, String> LEGACY_IDS = Map.ofEntries(
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

    /** 목록(카드) 응답에 내려줄 필드 — 본문(body 등)은 상세에서만. */
    private static final String[] SUMMARY_FIELDS = {
            "id", "n", "city", "cityId", "region", "km", "min", "lv", "mood",
            "tags", "headline", "subhead", "photo", "photoTitle", "photoLicense", "url",
            // 홈 피드에서 지도에 찍거나 현재 위치와의 거리를 계산할 수 있게 좌표도 내려준다
            "lat", "lng"
    };

    @Inject
    ObjectMapper mapper;

    @Inject
    CourseOverrides overrides;

    /** 공유 기능용 코스 url 베이스(응답의 url = base + /v1/courses/{id}). */
    @ConfigProperty(name = "course.share-base-url", defaultValue = "")
    String shareBaseUrl;

    private Map<String, JsonNode> byId;
    private ArrayNode summaries;

    @PostConstruct
    void load() {
        try (InputStream in = getClass().getResourceAsStream("/data/courses.json")) {
            if (in == null) {
                throw new IllegalStateException("data/courses.json 리소스 누락 (번들 확인)");
            }
            JsonNode root = mapper.readTree(in);
            Map<String, JsonNode> map = new LinkedHashMap<>();
            ArrayNode sums = mapper.createArrayNode();
            for (JsonNode c : root) {
                String id = c.path("id").asText("");
                if (id.isEmpty() || map.containsKey(id)) {
                    throw new IllegalStateException("코스 id 누락/중복: " + id);
                }
                if (!shareBaseUrl.isBlank()) { // 공유용 url — 데이터가 아니라 서버가 부여
                    ((ObjectNode) c).put("url", shareBaseUrl + "/v1/courses/" + id);
                }
                map.put(id, c);
                ObjectNode s = mapper.createObjectNode();
                for (String f : SUMMARY_FIELDS) {
                    if (c.has(f)) {
                        s.set(f, c.get(f));
                    }
                }
                // 홈 카드는 코스 거리보다 실제로 지나는 곳을 보여준다. 상세를 카드 수만큼
                // 추가 호출하지 않도록 이름만 가벼운 배열로 목록 응답에 포함한다.
                ArrayNode waypoints = mapper.createArrayNode();
                for (JsonNode poi : c.path("poi")) {
                    String name = poi.path("n").asText("").trim();
                    if (!name.isEmpty()) {
                        waypoints.add(name);
                    }
                }
                s.set("waypoints", waypoints);
                sums.add(s);
            }
            for (Map.Entry<String, String> alias : LEGACY_IDS.entrySet()) {
                if (map.containsKey(alias.getKey())) {
                    throw new IllegalStateException("구 ID가 현재 코스 id와 충돌: " + alias.getKey());
                }
                if (!map.containsKey(alias.getValue())) {
                    throw new IllegalStateException("구 ID 대상 코스 누락: " + alias.getKey()
                            + " -> " + alias.getValue());
                }
            }
            this.byId = map;
            this.summaries = sums;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("코스 카탈로그 로드 실패", e);
        }
    }

    /**
     * 요약 목록. city(도시명 또는 cityId)로 필터, null이면 전체.
     * 관리자가 고친 원고가 있으면 덮어서 내려간다 — 수정이 하나도 없으면 원본을 그대로 쓴다.
     */
    public ArrayNode list(String city) {
        boolean all = city == null || city.isBlank();
        if (all && overrides.isEmpty()) {
            return summaries;
        }
        String q = all ? null : city.trim();
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode s : summaries) {
            if (all || q.equals(s.path("city").asText())
                    || q.equalsIgnoreCase(s.path("cityId").asText())) {
                out.add(overrides.apply(s));
            }
        }
        return out;
    }

    /** 전체 코스(수정 반영). 검수 화면이 사진·경로를 한 번에 훑을 때 쓴다. */
    public java.util.Collection<JsonNode> all() {
        if (overrides.isEmpty()) {
            return byId.values();
        }
        return byId.values().stream().map(overrides::apply).toList();
    }

    /** 번들 원본 그대로(수정 미반영). 편집 화면이 "되돌리면 뭐가 되는지" 보여줄 때 쓴다. */
    public JsonNode originalById(String id) {
        return id == null ? null : byId.get(LEGACY_IDS.getOrDefault(id, id));
    }

    /** 상세(전체 필드, 수정 반영). 없으면 null. */
    public JsonNode byId(String id) {
        JsonNode c = originalById(id);
        return c == null ? null : overrides.apply(c);
    }
}
