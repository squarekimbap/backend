package com.tourapi.services;

import com.tourapi.lib.NaverSearchClient;
import com.tourapi.model.NearbyPlace;
import com.tourapi.model.Place;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 교차검증 병합 규칙만 검증한다(외부 호출 없음). */
class NearbyPlaceServiceTest {

    private static NearbyPlace place(String name, String kind, String trust, Integer distance) {
        return new NearbyPlace(null, kind, name, null, 0, 0, distance, null, null,
                "테스트", trust, null, null);
    }

    @Test
    void 교차검증된_곳만_앞에_서고_나머지는_거리순() {
        List<NearbyPlace> out = NearbyPlaceService.topBalanced(List.of(
                place("가까운 관광공사만", "restaurant", "tour", 100),
                place("네이버 인기", "restaurant", "trending", 400),
                place("양쪽 모두", "restaurant", "verified", 900)), 8);

        // trending이 tour를 무조건 앞서면 더 가까운 가게가 밀려난다 — 거리로 섞는다
        assertEquals(List.of("양쪽 모두", "가까운 관광공사만", "네이버 인기"),
                out.stream().map(NearbyPlace::name).toList());
    }

    @Test
    void 같은_등급이면_가까운_순() {
        List<NearbyPlace> out = NearbyPlaceService.topBalanced(List.of(
                place("먼 곳", "restaurant", "verified", 800),
                place("가까운 곳", "restaurant", "verified", 200)), 8);

        assertEquals("가까운 곳", out.get(0).name());
    }

    @Test
    void 뛰고나서_들를곳은_한업종이부족해도_최대8개를채운다() {
        List<NearbyPlace> out = NearbyPlaceService.topBalanced(List.of(
                place("식당1", "restaurant", "verified", 100),
                place("식당2", "restaurant", "tour", 200),
                place("식당3", "restaurant", "tour", 300),
                place("식당4", "restaurant", "tour", 400),
                place("식당5", "restaurant", "tour", 500),
                place("식당6", "restaurant", "tour", 600),
                place("식당7", "restaurant", "tour", 700),
                place("식당8", "restaurant", "tour", 800),
                place("식당9", "restaurant", "tour", 900),
                place("카페1", "cafe", "tour", 150)), 8);

        assertEquals(8, out.size());
        assertEquals(1, out.stream().filter(place -> "cafe".equals(place.kind())).count());
    }

    @Test
    void 장소가부족하면_반경을_2점5km까지넓히고_8개에서멈춘다() {
        List<Integer> searchedRadii = new ArrayList<>();
        NearbyPlaceService service = new NearbyPlaceService() {
            @Override
            public List<NearbyPlace> around(double lat, double lng, int radiusM, String hint, int max) {
                searchedRadii.add(radiusM);
                int count = radiusM < 2500 ? 3 : 8;
                List<NearbyPlace> items = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    items.add(place("장소" + i, i % 2 == 0 ? "restaurant" : "cafe", "tour", 100 + i));
                }
                return items;
            }
        };

        NearbyPlaceService.NearbySearchResult result =
                service.aroundExpanded(37.0, 127.0, 1500, null, 8);

        assertEquals(List.of(1500, 2500), searchedRadii);
        assertEquals(2500, result.radiusM());
        assertEquals(8, result.items().size());
    }

    @Test
    void 네이버_업종이_있으면_카페로_분류() {
        assertEquals("cafe", NearbyPlaceService.kind("무명상회", "카페,디저트"));
        assertEquals("restaurant", NearbyPlaceService.kind("무명상회", "한식>육류,고기"));
        // 업종이 없으면 이름으로 판단
        assertEquals("cafe", NearbyPlaceService.kind("노들 커피", null));
    }

    @Test
    void 이름이_조금_달라도_같은_가게로_매칭() {
        Map<String, NaverSearchClient.LocalPlace> byName = new LinkedHashMap<>();
        NaverSearchClient.LocalPlace naver = new NaverSearchClient.LocalPlace(
                "스타벅스 반포한강공원점", "카페", null, null, null, null, null);
        byName.put(NearbyPlaceService.normalize(naver.name()), naver);

        assertNotNull(NearbyPlaceService.match(byName, "스타벅스 반포한강공원"));
        assertNull(NearbyPlaceService.match(byName, "전혀 다른 국밥집"));
    }

    @Test
    void 주소_다수결로_시군구를_고른다() {
        List<Place> places = List.of(
                new Place("1", 39, "가", "서울특별시 중구 세종대로 110", 0, 0, null, null, null, null),
                new Place("2", 39, "나", "서울특별시 중구 을지로 12", 0, 0, null, null, null, null),
                new Place("3", 39, "다", "서울특별시 종로구 사직로 1", 0, 0, null, null, null, null));

        // 동이 없으면 시군구 폴백. 시도(서울특별시)가 아니라 구 단위를 골라야 한다
        assertEquals("중구", NearbyPlaceService.sigunguOf(places));
        assertNull(NearbyPlaceService.regionOf(places), "괄호 동이 없으면 동은 못 찾는다");
    }

    @Test
    void 괄호_동이_있으면_시군구보다_우선() {
        // 실측: "반포동 맛집"은 5/5가 반경 안, "용산구 맛집"은 0/5 — 동이 훨씬 정확하다
        List<Place> places = List.of(
                new Place("1", 39, "가", "서울특별시 서초구 사평대로 126 (반포동)", 0, 0, null, null, null, null),
                new Place("2", 39, "나", "서울특별시 용산구 서빙고로 297", 0, 0, null, null, null, null),
                new Place("3", 39, "다", "서울특별시 서초구 사평대로22길 5 (반포동)", 0, 0, null, null, null, null));

        assertEquals("반포동", NearbyPlaceService.regionOf(places));
    }

    @Test
    void 시_구가_함께_있으면_더_구체적인_쪽() {
        List<Place> places = List.of(
                new Place("1", 39, "가", "경기도 성남시 분당구 정자일로 1", 0, 0, null, null, null, null));

        assertEquals("분당구", NearbyPlaceService.sigunguOf(places));
    }
}
