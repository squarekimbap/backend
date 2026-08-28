package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** 코스 주변 도슨트의 존재와 트리거 위치만 노출한다. 대본/음성 URL은 잠금 해제 전 노출하지 않는다. */
@Schema(description = "코스 주변 도슨트 가용 정보")
public record StorySpot(
        @Schema(description = "Odii 이야기 ID") String storyId,
        @Schema(description = "위도") double lat,
        @Schema(description = "경도") double lng,
        @Schema(description = "코스로부터 최단거리(m)") int distanceToRouteM,
        @Schema(description = "재생시간(초), 없으면 null") Integer playTimeS
) {
}
