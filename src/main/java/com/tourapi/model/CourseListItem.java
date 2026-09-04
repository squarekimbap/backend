package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** GET /v1/courses의 홈 카드용 코스 요약. */
@Schema(description = "코스 목록 항목. waypoints는 이름 문자열 배열이며 상세 객체 배열은 poi를 사용한다.")
public record CourseListItem(
        @Schema(example = "sokcho-yeongnangho") String id,
        @Schema(example = "영랑호 순환") String n,
        @Schema(example = "속초") String city,
        @Schema(example = "sokcho") String cityId,
        @Schema(example = "강원") String region,
        @Schema(description = "표시 거리(km)", example = "8.4") double km,
        @Schema(description = "예상 러닝 시간(분)", example = "52") int min,
        @Schema(description = "표시 난이도", example = "쉬움") String lv,
        @Schema(example = "호수·강변") String mood,
        List<String> tags,
        String headline,
        String subhead,
        @Schema(description = "대표 사진 URL. 사진을 확보하지 못한 코스는 null", nullable = true)
        String photo,
        @Schema(description = "따라 달릴 보행 경로가 있는가. false면 '길 안내는 아직 준비 중이에요'. "
                + "목록에는 polyline을 싣지 않으므로 이 값을 그대로 쓴다(직접 파생 금지).",
                example = "true") boolean routed,
        @Schema(description = "공유용 코스 상세 URL") String url,
        @Schema(description = "대표 위도") double lat,
        @Schema(description = "대표 경도") double lng,
        @Schema(description = "홈 카드용 경유지 이름 배열. 좌표 객체가 아니며 상세에서는 poi를 사용한다.",
                example = "[\"영랑호\",\"영랑호수윗길\",\"범바위\"]")
        List<String> waypoints
) {
}
