package com.tourapi.lib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * username 파생 규칙 검증. 같은 이메일(공백·대소문자 무시)은 항상 같은 username이어야
 * Cognito의 username 중복 오류로 중복 가입이 막힌다.
 */
public class CognitoAuthTest {

    @Test
    public void 이메일_username은_결정적이고_정규화된다() {
        String a = CognitoAuth.usernameForEmail(" User@Example.com ");
        String b = CognitoAuth.usernameForEmail("user@example.com");
        assertEquals(a, b);                                   // trim + 소문자 정규화
        assertTrue(a.startsWith("email_"));
        assertEquals("email_".length() + 32, a.length());     // sha256 hex 앞 32자
    }

    @Test
    public void 다른_이메일은_다른_username() {
        assertNotEquals(
                CognitoAuth.usernameForEmail("a@example.com"),
                CognitoAuth.usernameForEmail("b@example.com"));
    }
}
