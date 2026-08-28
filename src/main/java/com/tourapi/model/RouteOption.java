package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** 화면 3에 보여주는 코스 선택지 하나. */
@Schema(description = "관광지 우선 또는 거리 우선 코스 옵션")
public record RouteOption(
        @Schema(description = "waypoint_priority | distance_priority") String strategy,
        @Schema(description = "화면 제목") String title,
        Course course,
        List<WaypointDto> includedWaypoints,
        List<WaypointDto> excludedWaypoints,
        @Schema(description = "희망 거리와 실제 거리 차이(m)") int distanceErrorM,
        @Schema(description = "희망 거리 ±10% 이내 여부") boolean withinTolerance,
        @Schema(description = "코스 주변 도슨트 수") int storyCount,
        List<StorySpot> stories,
        List<RouteSegment> segments
) {
}
