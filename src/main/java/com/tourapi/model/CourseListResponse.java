package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** GET /v1/courses 응답 스키마(OpenAPI 계약용). */
@Schema(description = "코스 목록 응답")
public record CourseListResponse(
        @Schema(example = "64") int count,
        List<CourseListItem> items
) {
}
