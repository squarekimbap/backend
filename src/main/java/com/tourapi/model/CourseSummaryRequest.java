package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** 사용자가 고른 코스에 맛집/카페 정보를 붙이는 요청. */
@Schema(description = "코스 총정리 요청")
public record CourseSummaryRequest(
        RouteOption option,
        @Schema(description = "도착지 주변 조회 반경(m), 기본 500, 최대 2000", example = "500") Integer nearbyRadiusM
) {
}
