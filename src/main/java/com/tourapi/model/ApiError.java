package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** 공통 에러 응답 본문. */
@Schema(description = "에러 응답")
public record ApiError(
        @Schema(description = "에러 코드", example = "bad_request") String error,
        @Schema(description = "설명 메시지", example = "lat 필수") String message
) {
}
