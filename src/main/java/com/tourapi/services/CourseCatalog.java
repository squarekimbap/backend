package com.tourapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

    /** 목록(카드) 응답에 내려줄 필드 — 본문(body 등)은 상세에서만. */
    private static final String[] SUMMARY_FIELDS = {
            "id", "n", "city", "cityId", "region", "km", "min", "lv", "mood",
            "tags", "headline", "subhead", "photo", "photoTitle", "photoLicense"
    };

    @Inject
    ObjectMapper mapper;

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
                map.put(id, c);
                ObjectNode s = mapper.createObjectNode();
                for (String f : SUMMARY_FIELDS) {
                    if (c.has(f)) {
                        s.set(f, c.get(f));
                    }
                }
                sums.add(s);
            }
            this.byId = map;
            this.summaries = sums;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("코스 카탈로그 로드 실패", e);
        }
    }

    /** 요약 목록. city(도시명 또는 cityId)로 필터, null이면 전체. */
    public ArrayNode list(String city) {
        if (city == null || city.isBlank()) {
            return summaries;
        }
        String q = city.trim();
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode s : summaries) {
            if (q.equals(s.path("city").asText()) || q.equalsIgnoreCase(s.path("cityId").asText())) {
                out.add(s);
            }
        }
        return out;
    }

    /** 상세(전체 필드). 없으면 null. */
    public JsonNode byId(String id) {
        return byId.get(id);
    }
}
