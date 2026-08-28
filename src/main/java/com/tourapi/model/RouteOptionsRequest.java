package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** 화면 2에서 고른 관광지와 전체 후보로 화면 3의 두 코스 옵션을 만드는 요청. */
@Schema(description = "관광지 우선/거리 우선 코스 옵션 생성 요청")
public record RouteOptionsRequest(
        @Schema(description = "출발점") WaypointDto start,
        @Schema(description = "사용자가 고른 관광지(0~5개)") List<WaypointDto> selectedWaypoints,
        @Schema(description = "화면 2에 노출했던 전체 후보. 선택이 없거나 거리 보정 시 사용") List<WaypointDto> candidateWaypoints,
        @Schema(description = "loop(왕복, 기본) | oneway(편도)", example = "loop") String shape,
        @Schema(description = "희망 거리(km)", example = "5") Double targetDistanceKm
) {
}
