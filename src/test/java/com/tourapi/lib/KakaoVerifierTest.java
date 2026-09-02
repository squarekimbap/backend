package com.tourapi.lib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 카카오 /v2/user/me 응답 파싱 검증(네트워크 없이 순수 파싱만). */
public class KakaoVerifierTest {

    @Test
    public void 카카오_응답에서_id와_닉네임을_읽는다() {
        String json = "{\"id\":12345,\"kakao_account\":{\"profile\":{\"nickname\":\"kim\"}}}";
        KakaoVerifier.KakaoUser u = KakaoVerifier.parse(json);
        assertEquals(12345L, u.id());
        assertEquals("kim", u.nickname());
    }

    @Test
    public void 프로필_미동의면_기본_닉네임() {
        KakaoVerifier.KakaoUser u = KakaoVerifier.parse("{\"id\":9}");
        assertEquals(9L, u.id());
        assertEquals("카카오사용자", u.nickname());
    }

    @Test
    public void id_없으면_업스트림_예외() {
        assertThrows(UpstreamException.class, () -> KakaoVerifier.parse("{\"msg\":\"no id\"}"));
    }

    @Test
    public void 우리_앱_토큰이면_통과() { // app_id는 숫자로 오므로 문자열 비교가 성립해야 한다
        KakaoVerifier.ensureAppId("123456", "{\"id\":9,\"expires_in\":3600,\"app_id\":123456}");
    }

    @Test
    public void 다른_앱_토큰은_무효_토큰_취급() {
        assertThrows(InvalidKakaoTokenException.class,
                () -> KakaoVerifier.ensureAppId("123456", "{\"id\":9,\"app_id\":999999}"));
    }

    @Test
    public void app_id_없는_응답도_거부() { // 조용히 통과시키면 검사가 무력화된다
        assertThrows(InvalidKakaoTokenException.class,
                () -> KakaoVerifier.ensureAppId("123456", "{\"id\":9}"));
    }
}
