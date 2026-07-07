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
}
