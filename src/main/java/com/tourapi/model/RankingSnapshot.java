package com.tourapi.model;

import java.util.List;

/** 시군구+날짜 단위로 캐시하는 집중률 순위 전체(잘라내기 전). API로 직접 노출되지 않는다. */
public record RankingSnapshot(
        String areaNm,
        String signguNm,
        List<PopularPlace> items
) {
}
