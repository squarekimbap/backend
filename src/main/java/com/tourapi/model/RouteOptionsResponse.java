package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** POST /v1/running/route-options 응답. */
@Schema(description = "화면 3 코스 선택지")
public record RouteOptionsResponse(
        String shape,
        double targetDistanceKm,
        int count,
        List<RouteOption> options
) {
}
