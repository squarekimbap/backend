package com.tourapi.services;

import com.tourapi.lib.NaverSearchClient;
import com.tourapi.model.NearbyPlace;
import com.tourapi.model.Place;
import org.junit.jupiter.api.Test;

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
    void 교차검증된_곳이_먼저_나온다() {
        List<NearbyPlace> out = NearbyPlaceService.topBalanced(List.of(
                place("가까운 관광공사만", "restaurant", "tour", 100),
                place("네이버 인기", "restaurant", "trending", 400),
                place("양쪽 모두", "restaurant", "verified", 900)), 8);

        assertEquals(List.of("양쪽 모두", "네이버 인기", "가까운 관광공사만"),
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

        // 시도 단위(서울특별시)가 아니라 구 단위를 골라야 검색이 엉뚱해지지 않는다
        assertEquals("중구", NearbyPlaceService.regionOf(places));
    }

    @Test
    void 시_구가_함께_있으면_더_구체적인_쪽() {
        List<Place> places = List.of(
                new Place("1", 39, "가", "경기도 성남시 분당구 정자일로 1", 0, 0, null, null, null, null));

        assertEquals("분당구", NearbyPlaceService.regionOf(places));
    }
}
