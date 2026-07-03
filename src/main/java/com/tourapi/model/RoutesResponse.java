package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** POST /v1/running/routes 응답 (코스 최대 3개). */
@Schema(description = "러닝 코스 추천 응답")
public record RoutesResponse(
        @Schema(description = "코스 형태", example = "loop") String shape,
        @Schema(description = "코스 수(순서 후보 중 계산 성공한 것, 최대 3)") int count,
        List<Course> courses
) {
}
