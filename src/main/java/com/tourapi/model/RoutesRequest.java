package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** POST /v1/running/routes 요청(사용자가 고른 경유지). */
@Schema(description = "러닝 코스 계산 요청")
public record RoutesRequest(
        @Schema(description = "출발점") WaypointDto start,
        @Schema(description = "경유지 1~5개(선택 순서대로)") List<WaypointDto> waypoints,
        @Schema(description = "loop(출발지 복귀, 기본) | oneway(마지막 경유지 도착)", example = "loop") String shape,
        @Schema(description = "희망 거리(km, 선택) — 있으면 이 거리에 가까운 순으로 정렬", example = "5") Double targetDistanceKm
) {
}
