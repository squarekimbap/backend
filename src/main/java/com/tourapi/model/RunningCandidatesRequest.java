package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** POST /v1/running/candidates 요청(설문). */
@Schema(description = "러닝 경유지 후보 요청(설문)")
public record RunningCandidatesRequest(
        @Schema(description = "출발 위도(WGS84)", example = "37.5665") Double lat,
        @Schema(description = "출발 경도(WGS84)", example = "126.9780") Double lng,
        @Schema(description = "희망 거리(km)", example = "5") Double distanceKm,
        @Schema(description = "코스 형태: loop(출발지 복귀, 기본) | oneway(편도)", example = "loop") String shape,
        @Schema(description = "후보 개수. 기본 10, 최대 30", example = "10") Integer count
) {
}
