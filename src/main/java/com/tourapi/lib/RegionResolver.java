package com.tourapi.lib;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 좌표(lat/lng) → 통계청/법정동 시군구코드 변환.
 * <p>locationBasedList2 응답의 lDongRegnCd(시도 2자리) + lDongSignguCd(시군구 3자리)를 이어붙여
 * 집중률 API가 요구하는 signguCd(5자리)를 만든다. 같은 TourAPI 키만 쓰므로 추가 키가 필요 없다.
 */
@ApplicationScoped
public class RegionResolver {

    @Inject
    TourApiClient client;

    @ConfigProperty(name = "tour.api.mobile-app", defaultValue = "tour-api")
    String mobileApp;

    @ConfigProperty(name = "tour.api.op.location-based", defaultValue = "locationBasedList2")
    String opLocationBased;

    /** areaCd=시도(2자리), signguCd=시군구(5자리, 법정동 기준). */
    public record Region(String areaCd, String signguCd) {
    }

    /** 좌표에서 가장 가까운 관광지의 법정동코드로 시군구를 특정. 못 찾으면 null. */
    public Region fromCoordinate(double lat, double lng, int radius) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("MobileOS", "ETC");
        p.put("MobileApp", mobileApp);
        p.put("_type", "json");
        p.put("numOfRows", "10");
        p.put("pageNo", "1");
        p.put("arrange", "E"); // 거리순 → 가장 가까운 것부터
        p.put("mapX", Double.toString(lng));
        p.put("mapY", Double.toString(lat));
        p.put("radius", Integer.toString(radius));

        JsonNode root = client.get(opLocationBased, p);
        PublicData.ensureOk(root);
        for (JsonNode it : PublicData.items(root)) {
            String regn = it.path("lDongRegnCd").asText("").strip();
            String sig = it.path("lDongSignguCd").asText("").strip();
            if (!regn.isEmpty() && !sig.isEmpty()) {
                return new Region(regn, regn + leftPad(sig, 3));
            }
        }
        return null;
    }

    private static String leftPad(String s, int len) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < len) {
            b.insert(0, '0');
        }
        return b.toString();
    }
}
