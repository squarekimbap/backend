package com.tourapi.lib;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 취급대장 한 줄이 신고서 양식의 5개 항목(대상·취득경로·제공서비스·제공받는자·이용일시)을
 * 그대로 담는지 본다. 항목 이름이 바뀌면 제출한 캡처와 실제 로그가 어긋난다.
 */
public class LocationAccessLogTest {

    @Test
    public void 대장_한_줄에_필수_항목이_모두_있다() {
        String line = LocationAccessLog.line(
                "8b1c4e2a-sub", "running-route-options", Instant.parse("2026-09-15T00:32:41Z"));

        assertTrue(line.contains("대상=8b1c4e2a-sub"), line);
        assertTrue(line.contains("취득경로=단말GPS(앱 전송)"), line);
        assertTrue(line.contains("제공서비스=running-route-options"), line);
        assertTrue(line.contains("제공받는자=-"), line);
        assertTrue(line.contains("이용일시=2026-09-15T09:32:41+09:00"), line);  // KST·초 단위
    }

    @Test
    public void 토큰이_없는_요청도_빠짐없이_남는다() {
        assertTrue(LocationAccessLog.line(null, "tour-places", Instant.now()).contains("대상=미로그인"));
        assertTrue(LocationAccessLog.line("  ", "tour-places", Instant.now()).contains("대상=미로그인"));
    }
}
