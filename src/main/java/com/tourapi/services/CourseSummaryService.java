package com.tourapi.services;

import com.tourapi.model.Course;
import com.tourapi.model.CourseSummaryResponse;
import com.tourapi.model.NearbyPlace;
import com.tourapi.model.RouteOption;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** 선택된 코스에 도착지 주변 음식점/카페를 붙여 화면 4 총정리를 만든다. */
@ApplicationScoped
public class CourseSummaryService {

    @Inject
    NearbyPlaceService nearbyPlaceService;

    public CourseSummaryResponse summary(RouteOption option, int radiusM) {
        Course course = option.course();
        if (course.path() == null || course.path().isEmpty()) {
            return new CourseSummaryResponse(option, 0, List.of());
        }
        double[] finish = course.path().get(course.path().size() - 1);
        // 검색어 힌트는 넘기지 않는다 — 도착지 주변 주소에서 시군구를 뽑는 편이 위치에 더 정확하다
        List<NearbyPlace> selected = nearbyPlaceService.around(finish[0], finish[1], radiusM, null, 8);
        return new CourseSummaryResponse(option, selected.size(), selected);
    }
}
