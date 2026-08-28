package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** POST /v1/running/candidates 응답. */
@Schema(description = "러닝 경유지 후보 응답(집중률 순위 우선 정렬)")
public record CandidatesResponse(
        @Schema(description = "출발 위도") double lat,
        @Schema(description = "출발 경도") double lng,
        @Schema(description = "코스 형태", example = "loop") String shape,
        @Schema(description = "후보 검색 반경(m)", example = "1667") int radiusM,
        @Schema(description = "시도명", example = "서울특별시") String areaNm,
        @Schema(description = "시군구명", example = "중구") String signguNm,
        @Schema(description = "반환 후보 수") int count,
        @Schema(description = "반환 후보 중 도슨트가 있는 장소 수") int storyCount,
        List<RunningCandidate> items
) {
}
