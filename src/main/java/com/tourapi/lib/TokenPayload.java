package com.tourapi.lib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * JWT payload 파싱(서명 검증 없음). Cognito가 방금 TLS로 발급한 토큰에서 sub/email을
 * 꺼낼 때만 사용한다 — 외부에서 들어온 토큰 검증은 반드시 smallrye-jwt(@Authenticated)를 거칠 것.
 */
public final class TokenPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TokenPayload() {
    }

    public static JsonNode payload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            byte[] json = Base64.getUrlDecoder().decode(parts[1]);
            return MAPPER.readTree(new String(json, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("JWT payload 파싱 실패", e);
        }
    }
}
