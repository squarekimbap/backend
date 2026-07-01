package com.tourapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.tourapi.lib.PublicData;
import com.tourapi.lib.TourApiClient;
import com.tourapi.model.Place;
import com.tourapi.model.PlacesResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 관광 도메인 서비스: TourAPI 위치기반 조회 → 앱 응답으로 가공. */
@ApplicationScoped
public class TourService {

    @Inject
    TourApiClient client;

    @ConfigProperty(name = "tour.api.mobile-app", defaultValue = "tour-api")
    String mobileApp;

    @ConfigProperty(name = "tour.api.op.location-based", defaultValue = "locationBasedList2")
    String opLocationBased;

    /** 좌표 주변 관광 정보(거리순). */
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
        String v = text(it, field);
        if (v.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0.0;
        }
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
}
