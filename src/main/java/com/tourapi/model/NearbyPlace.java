package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** 뛰고 나서 들를 음식점/카페. */
@Schema(description = "코스 도착지 주변 맛집/카페")
public record NearbyPlace(
        String contentId,
        @Schema(description = "restaurant | cafe") String kind,
        String name,
        String addr,
        double lat,
        double lng,
        Integer distanceM,
        String image,
        String tel,
        @Schema(description = "정보 출처", example = "한국관광공사 TourAPI · 네이버") String source,
        @Schema(description = "교차검증 결과 — verified(양쪽 모두) · trending(네이버 최신 인기) · tour(관광공사만)",
                example = "verified") String trust,
        @Schema(description = "네이버 업종 분류, 없으면 null", example = "한식>육류,고기") String category,
        @Schema(description = "네이버 상세 링크, 없으면 null") String link
) {
}
