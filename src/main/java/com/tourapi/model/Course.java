package com.tourapi.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** 러닝 코스 1개(경유 순서 후보 하나를 실제 경로·고도로 계산한 결과). */
@Schema(description = "러닝 코스 1개")
public record Course(
        @Schema(description = "경유 순서 라벨(선택순/역순/근접순)", example = "선택순") String label,
        @Schema(description = "방문 순서(경유지 이름)") List<String> waypointOrder,
        @Schema(description = "총 거리(m, TMAP)", example = "5120") int distanceM,
        @Schema(description = "도보 기준 소요(초, TMAP) — 러닝 시간은 앱에서 페이스로 환산", example = "4400") int walkDurationS,
        @Schema(description = "누적 상승고도(m, Google Elevation)", example = "42.5") double ascentM,
        @Schema(description = "km당 상승고도(m)", example = "8.3") double ascentPerKm,
        @Schema(description = "난이도: km당 상승 10m↓=하, 25m↓=중, 초과=상", example = "하") String difficulty,
        @Schema(description = "경로 좌표 [[lat,lng],...] (지도에 그리기용, 최대 200점)") List<double[]> path
) {
}
