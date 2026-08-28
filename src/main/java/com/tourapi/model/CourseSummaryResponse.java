package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** POST /v1/running/summary 응답. */
@Schema(description = "최종 코스와 뛰고 나서 들를 곳")
public record CourseSummaryResponse(
        RouteOption option,
        int nearbyCount,
        List<NearbyPlace> afterRunPlaces
) {
}
