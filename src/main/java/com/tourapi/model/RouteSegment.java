package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** 최종 코스의 출발지/경유지 사이 구간 거리. */
@Schema(description = "코스 구간")
public record RouteSegment(
        String from,
        String to,
        @Schema(description = "해당 구간 거리(m)") int distanceM
) {
}
