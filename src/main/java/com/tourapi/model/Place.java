package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 앱에 내려주는 관광지 1건.
 * (TMAP·Google 등 내부 전용 데이터/키는 포함하지 않는다.)
 */
@Schema(description = "관광지 1건 (앱 노출용, 내부 전용 데이터 미포함)")
public record Place(
        @Schema(description = "콘텐츠 ID", example = "130183") String contentId,
        @Schema(description = "콘텐츠 타입(12관광지·14문화시설·15축제·25여행코스·28레포츠·32숙박·38쇼핑·39음식점)", example = "12") int contentTypeId,
        @Schema(description = "명칭", example = "서울도서관") String title,
        @Schema(description = "주소", example = "서울특별시 중구 세종대로 110") String addr,
        @Schema(description = "위도(WGS84)", example = "37.5665") double lat,
        @Schema(description = "경도(WGS84)", example = "126.9783") double lng,
        @Schema(description = "조회 좌표로부터 거리(m), 없으면 null", example = "35") Integer distanceM,
        @Schema(description = "대표 이미지 URL, 없으면 null") String image,
        @Schema(description = "썸네일 URL, 없으면 null") String thumbnail,
        @Schema(description = "전화번호, 없으면 null") String tel
) {
}
