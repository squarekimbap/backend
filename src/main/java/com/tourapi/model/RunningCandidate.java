package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** 러닝 경유지 후보 1건 (주변 관광지 + 집중률 순위 매칭). */
@Schema(description = "러닝 경유지 후보 1건")
public record RunningCandidate(
        @Schema(description = "이름", example = "덕수궁") String name,
        @Schema(description = "위도", example = "37.5658") double lat,
        @Schema(description = "경도", example = "126.9751") double lng,
        @Schema(description = "출발좌표로부터 거리(m)", example = "260") Integer distanceM,
        @Schema(description = "콘텐츠 타입(12관광지·14문화시설·28레포츠)", example = "12") int contentTypeId,
        @Schema(description = "주소") String addr,
        @Schema(description = "이미지 URL, 없으면 null") String image,
        @Schema(description = "해당 시군구 집중률 순위(1부터), 매칭 안 되면 null", example = "6") Integer popularityRank,
        @Schema(description = "향후 30일 평균 집중률, 매칭 안 되면 null", example = "83.2") Double popularityAvg
) {
}
