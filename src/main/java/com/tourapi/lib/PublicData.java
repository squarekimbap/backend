package com.tourapi.lib;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 공공데이터포털(TourAPI) 응답의 함정을 한 곳에 가둔다.
 * 모든 tour 엔드포인트(places·festivals·images·hubs)가 공유한다.
 * <ul>
 *   <li>resultCode "0000" 확인 (아니면 {@link UpstreamException})</li>
 *   <li>items.item : 배열 / 단일객체 / 빈문자열("") / 누락 정규화 → List</li>
 * </ul>
 */
public final class PublicData {

    private static final String OK = "0000";

    private PublicData() {
    }

    /** resultCode가 "0000"이 아니면 UpstreamException. */
    public static void ensureOk(JsonNode root) {
        JsonNode header = root.path("response").path("header");
        String code = header.path("resultCode").asText("");
        // 데이터랩 계열(집중률 등)은 에러를 평평한 {resultCode,resultMsg}로 준다(성공은 KorService와 동일 nested)
        if (code.isEmpty()) {
            code = root.path("resultCode").asText("");
        }
        if (!OK.equals(code)) {
            String msg = header.path("resultMsg").asText(root.path("resultMsg").asText("UNKNOWN"));
            throw new UpstreamException("TourAPI resultCode=" + (code.isEmpty() ? "?" : code) + " (" + msg + ")");
        }
    }

    /**
     * response.body.items.item 을 List로 정규화.
     * 결과 0건이면 items 가 "" (빈 문자열)로 오는 함정 → 빈 리스트.
     * 결과 1건이면 배열이 아니라 단일 객체로 오는 함정 → 원소 1개 리스트.
     */
    public static List<JsonNode> items(JsonNode root) {
        JsonNode items = root.path("response").path("body").path("items");
        // items 가 객체가 아니면(예: "") 결과 없음
        if (!items.isObject()) {
            return List.of();
        }
        JsonNode item = items.path("item");
        if (item.isArray()) {
            List<JsonNode> out = new ArrayList<>(item.size());
            item.forEach(out::add);
            return out;
        }
        if (item.isObject()) {
            return List.of(item);
        }
        return List.of();
    }

    /** response.body.totalCount (없으면 0). */
    public static int totalCount(JsonNode root) {
        return root.path("response").path("body").path("totalCount").asInt(0);
    }
}
