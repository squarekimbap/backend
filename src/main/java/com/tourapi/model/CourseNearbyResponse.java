package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** GET /v1/courses/{id}/nearby 응답 — 코스 상세 화면의 "주변 맛집·카페". */
@Schema(description = "코스 주변 맛집/카페(관광공사 + 네이버 교차검증)")
public record CourseNearbyResponse(
        String courseId,
        @Schema(description = "기준으로 삼은 경유지 이름. 좌표를 못 찾으면 null", example = "노들섬")
        String basedOn,
        @Schema(description = "기준 위도, 없으면 null") Double lat,
        @Schema(description = "기준 경도, 없으면 null") Double lng,
        @Schema(description = "장소를 채우기 위해 실제로 사용한 검색 반경(m)", example = "2500") int radiusM,
        int count,
        List<NearbyPlace> items
) {
}
