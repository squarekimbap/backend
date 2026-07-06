package com.tourapi.lib;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TokenPayloadTest {

    @Test
    public void payload에서_sub를_읽는다() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"u-123\",\"email\":\"a@b.c\"}".getBytes());
        String jwt = "eyJhbGciOiJub25lIn0." + payload + ".sig";
        assertEquals("u-123", TokenPayload.payload(jwt).path("sub").asText());
        assertEquals("a@b.c", TokenPayload.payload(jwt).path("email").asText());
    }

    @Test
    public void 형식이_깨진_토큰은_예외() {
        assertThrows(IllegalArgumentException.class, () -> TokenPayload.payload("not-a-jwt"));
    }
}
