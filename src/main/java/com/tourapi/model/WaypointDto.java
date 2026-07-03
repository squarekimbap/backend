package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** 경유지(요청용). */
@Schema(description = "경유지 좌표")
public record WaypointDto(
        @Schema(description = "이름(선택)", example = "덕수궁") String name,
        @Schema(description = "위도", example = "37.5658") Double lat,
        @Schema(description = "경도", example = "126.9751") Double lng
) {
}
