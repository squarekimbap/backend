package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** GET /v1/tour/popular 응답 (좌표 주변 인기 관광지 순위, 집중률 기반). */
@Schema(description = "좌표 주변 인기 관광지 순위 응답(집중률 기반)")
public record PopularResponse(
        @Schema(description = "입력 위도", example = "37.5665") double lat,
        @Schema(description = "입력 경도", example = "126.9780") double lng,
        @Schema(description = "시도 코드(통계청 2자리)", example = "11") String areaCd,
        @Schema(description = "시군구 코드(법정동 5자리)", example = "11140") String signguCd,
        @Schema(description = "시도명", example = "서울특별시") String areaNm,
        @Schema(description = "시군구명", example = "중구") String signguNm,
        @Schema(description = "집계된 관광지 수", example = "34") int attractionCount,
        @Schema(description = "반환 순위 개수", example = "20") int size,
        List<PopularPlace> items
) {
}
