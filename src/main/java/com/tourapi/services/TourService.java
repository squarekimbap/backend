package com.tourapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.tourapi.lib.PublicData;
import com.tourapi.lib.RegionResolver;
import com.tourapi.lib.TourApiClient;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.Place;
import com.tourapi.model.PlacesResponse;
import com.tourapi.model.PopularPlace;
import com.tourapi.model.PopularResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 관광 도메인 서비스: TourAPI 위치기반 조회 / 집중률 순위 → 앱 응답으로 가공. */
@ApplicationScoped
public class TourService {

    private static final Logger LOG = Logger.getLogger(TourService.class);

    @Inject
    TourApiClient client;

    @Inject
    RegionResolver regionResolver;

    @ConfigProperty(name = "tour.api.mobile-app", defaultValue = "tour-api")
    String mobileApp;

    @ConfigProperty(name = "tour.api.op.location-based", defaultValue = "locationBasedList2")
    String opLocationBased;

    @ConfigProperty(name = "tour.api.concentration-base-url",
            defaultValue = "https://apis.data.go.kr/B551011/TatsCnctrRateService")
    String concentrationBaseUrl;

    @ConfigProperty(name = "tour.api.op.concentration", defaultValue = "tatsCnctrRatedList")
    String opConcentration;

    @ConfigProperty(name = "tour.api.reverse-geocode-radius", defaultValue = "3000")
    int reverseGeocodeRadius;

    @ConfigProperty(name = "tour.api.concentration-max-pages", defaultValue = "6")
    int concentrationMaxPages;

    @ConfigProperty(name = "tour.api.concentration-page-size", defaultValue = "5000")
    int concentrationPageSize;

    // ── 좌표 주변 관광 정보(거리순) ──────────────────────────────
    public PlacesResponse nearbyPlaces(double lat, double lng, int radius,
                                       Integer contentTypeId, int page, int size) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("MobileOS", "ETC");
        p.put("MobileApp", mobileApp);
        p.put("_type", "json");
        p.put("numOfRows", Integer.toString(size));
        p.put("pageNo", Integer.toString(page));
        p.put("mapX", Double.toString(lng)); // 경도
        p.put("mapY", Double.toString(lat)); // 위도
        p.put("radius", Integer.toString(radius));
        p.put("arrange", "E"); // E = 거리순
        if (contentTypeId != null) {
            p.put("contentTypeId", Integer.toString(contentTypeId));
        }

        JsonNode root = client.get(opLocationBased, p);
        PublicData.ensureOk(root);

        List<Place> items = new ArrayList<>();
        for (JsonNode it : PublicData.items(root)) {
            items.add(toPlace(it));
        }
        return new PlacesResponse(page, size, PublicData.totalCount(root), items);
    }

    // ── 좌표 주변 인기 관광지 순위(집중률, 30일 평균) ─────────────
    public PopularResponse popular(double lat, double lng, int size) {
        RegionResolver.Region region = regionResolver.fromCoordinate(lat, lng, reverseGeocodeRadius);
        if (region == null) {
            region = regionResolver.fromCoordinate(lat, lng, 20000); // 넓게 재시도
        }
        if (region == null) {
            throw new UpstreamException("좌표 주변에서 지역(시군구)을 특정할 수 없음");
        }

        Map<String, Acc> byName = new LinkedHashMap<>();
        String areaNm = "";
        String signguNm = "";
        int total = Integer.MAX_VALUE;
        int collected = 0;
        boolean capped = false;

        for (int page = 1; page <= concentrationMaxPages; page++) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("MobileOS", "ETC");
            p.put("MobileApp", mobileApp);
            p.put("_type", "json");
            p.put("numOfRows", Integer.toString(concentrationPageSize));
            p.put("pageNo", Integer.toString(page));
            p.put("areaCd", region.areaCd());
            p.put("signguCd", region.signguCd());

            JsonNode root = client.getFrom(concentrationBaseUrl, opConcentration, p);
            PublicData.ensureOk(root);
            List<JsonNode> items = PublicData.items(root);
            if (items.isEmpty()) {
                break;
            }
            total = PublicData.totalCount(root);
            for (JsonNode it : items) {
                if (areaNm.isEmpty()) {
                    areaNm = it.path("areaNm").asText("").strip();
                    signguNm = it.path("signguNm").asText("").strip();
                }
                String name = it.path("tAtsNm").asText("").strip();
                if (name.isEmpty()) {
                    continue;
                }
                byName.computeIfAbsent(name, k -> new Acc()).add(parseDouble(it.path("cnctrRate").asText("")));
            }
            collected += items.size();
            if (collected >= total) {
                break;
            }
            if (page == concentrationMaxPages) {
                capped = true;
            }
        }

        if (capped) {
            LOG.warnf("집중률 수집 상한(%d페이지) 도달 — signguCd=%s 일부 관광지 누락 가능", concentrationMaxPages, region.signguCd());
        }

        List<PopularPlace> ranked = new ArrayList<>();
        byName.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, Acc> e) -> e.getValue().avg()).reversed())
                .limit(size)
                .forEach(e -> ranked.add(new PopularPlace(
                        ranked.size() + 1,
                        e.getKey(),
                        round1(e.getValue().avg()),
                        round1(e.getValue().peak),
                        e.getValue().count)));

        return new PopularResponse(lat, lng, region.areaCd(), region.signguCd(),
                areaNm, signguNm, byName.size(), ranked.size(), ranked);
    }

    // ── 매핑/집계 헬퍼 ───────────────────────────────────────────

    private static Place toPlace(JsonNode it) {
        return new Place(
                text(it, "contentid"),
                intOf(it, "contenttypeid"),
                text(it, "title"),
                addr(it),
                doubleOf(it, "mapy"), // 위도
                doubleOf(it, "mapx"), // 경도
                distOf(it),
                nullable(it, "firstimage"),
                nullable(it, "firstimage2"),
                nullable(it, "tel")
        );
    }

    private static String addr(JsonNode it) {
        String a1 = text(it, "addr1");
        String a2 = text(it, "addr2");
        return a2.isBlank() ? a1 : (a1 + " " + a2).strip();
    }

    private static String text(JsonNode it, String field) {
        return it.path(field).asText("").strip();
    }

    private static String nullable(JsonNode it, String field) {
        String v = text(it, field);
        return v.isBlank() ? null : v;
    }

    private static int intOf(JsonNode it, String field) {
        String v = text(it, field);
        if (v.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double doubleOf(JsonNode it, String field) {
        return parseDouble(text(it, field));
    }

    private static Integer distOf(JsonNode it) {
        String v = text(it, "dist");
        if (v.isBlank()) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double parseDouble(String s) {
        if (s == null || s.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 관광지별 집중률 누적기(합/최대/일수). */
    private static final class Acc {
        double sum;
        double peak;
        int count;

        void add(double v) {
            sum += v;
            count++;
            if (v > peak) {
                peak = v;
            }
        }

        double avg() {
            return count == 0 ? 0.0 : sum / count;
        }
    }
}
