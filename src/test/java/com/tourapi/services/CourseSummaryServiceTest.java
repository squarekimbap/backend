package com.tourapi.services;

import com.tourapi.lib.NaverSearchClient;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.Course;
import com.tourapi.model.CourseSummaryResponse;
import com.tourapi.model.Place;
import com.tourapi.model.PlacesResponse;
import com.tourapi.model.RouteOption;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CourseSummaryServiceTest {

    @Test
    void 도착지주변음식점을총정리에붙인다() {
        TourService tours = mock(TourService.class);
        Place cafe = new Place("1", 39, "프리퍼 커피", "서울", 37.0, 127.0,
                100, null, null, null);
        Place restaurant = new Place("2", 39, "동보성", "서울", 37.0, 127.0,
                50, null, null, null);
        when(tours.nearbyPlaces(anyDouble(), anyDouble(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new PlacesResponse(1, 40, 2, List.of(cafe, restaurant)));
        CourseSummaryService service = service(tours);

        CourseSummaryResponse response = service.summary(option(), 500);

        assertEquals(500, response.nearbyRadiusM());
        assertEquals(2, response.nearbyCount());
        assertEquals("restaurant", response.afterRunPlaces().get(0).kind());
        assertEquals("cafe", response.afterRunPlaces().get(1).kind());
        // 네이버가 꺼져 있으면 교차검증 없이 관광공사 출처로만 내려간다
        assertEquals("tour", response.afterRunPlaces().get(0).trust());
    }

    @Test
    void 외부Api장애만빈목록으로폴백한다() {
        CourseSummaryService service = serviceThrowing(new UpstreamException("TourAPI timeout"));

        CourseSummaryResponse response = service.summary(option(), 500);

        assertEquals(0, response.nearbyCount());
    }

    @Test
    void 예상하지못한코드오류는숨기지않는다() {
        CourseSummaryService service = serviceThrowing(new IllegalStateException("bug"));

        assertThrows(IllegalStateException.class, () -> service.summary(option(), 500));
    }

    /** 네이버 키가 없는 기본 상태(enabled()=false)로 조립한다. */
    private static CourseSummaryService service(TourService tours) {
        NearbyPlaceService nearby = new NearbyPlaceService();
        nearby.tourService = tours;
        nearby.naver = mock(NaverSearchClient.class);
        nearby.trendingRadiusFactor = 1.5;
        CourseSummaryService service = new CourseSummaryService();
        service.nearbyPlaceService = nearby;
        return service;
    }

    private static CourseSummaryService serviceThrowing(RuntimeException error) {
        TourService tours = mock(TourService.class);
        when(tours.nearbyPlaces(anyDouble(), anyDouble(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenThrow(error);
        return service(tours);
    }

    private static RouteOption option() {
        Course course = new Course("", List.of(), 3000, 1000, 0, 0, "하",
                List.of(new double[]{37.0, 127.0}));
        return new RouteOption("distance_priority", "거리가 딱 맞아요", course,
                List.of(), List.of(), 0, true, 0, List.of(), List.of());
    }
}
