package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** 집중률 순위 관광지 1건. */
@Schema(description = "집중률 순위 관광지 1건")
public record PopularPlace(
        @Schema(description = "순위(1부터)", example = "1") int rank,
        @Schema(description = "관광지명", example = "명동") String name,
        @Schema(description = "향후 30일 평균 집중률", example = "83.2") double avgConcentration,
        @Schema(description = "기간 중 최대 집중률", example = "98.3") double peakConcentration,
        @Schema(description = "집계에 쓰인 일수", example = "30") int days
) {
}
