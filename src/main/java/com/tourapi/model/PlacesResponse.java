package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** GET /v1/tour/places 응답. */
@Schema(description = "좌표 주변 관광지 목록 응답")
public record PlacesResponse(
        @Schema(description = "페이지 번호", example = "1") int page,
        @Schema(description = "페이지 크기", example = "20") int size,
        @Schema(description = "전체 결과 수", example = "459") int totalCount,
        List<Place> items
) {
}
