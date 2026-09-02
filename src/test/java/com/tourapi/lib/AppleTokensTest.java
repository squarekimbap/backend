package com.tourapi.lib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Apple client_secret(ES256 JWT) 제작 검증 — 네트워크 없이 자체 EC 키쌍으로.
 * 여기가 조용히 깨지는 자리다: DER 서명을 그대로 실으면 Apple이 invalid_client로 거절하는데
 * 로컬에선 아무 증상이 없고 실제 폐기 요청에서만 드러난다.
 */
public class AppleTokensTest {

    static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);
    static final ObjectMapper MAPPER = new ObjectMapper();

    static KeyPair keys;

    @BeforeAll
    static void EC_키쌍_생성() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keys = generator.generateKeyPair();
    }

    @Test
    public void client_secret은_Apple이_보는_클레임을_담는다() throws Exception {
        String[] parts = AppleTokens.clientSecret(
                keys.getPrivate(), "KEY123", "TEAM12345", "com.mega.dali", NOW).split("\\.");

        JsonNode header = MAPPER.readTree(decode(parts[0]));
        assertEquals("ES256", header.path("alg").asText());
        assertEquals("KEY123", header.path("kid").asText());

        JsonNode claims = MAPPER.readTree(decode(parts[1]));
        assertEquals("TEAM12345", claims.path("iss").asText());
        assertEquals("com.mega.dali", claims.path("sub").asText());
        assertEquals("https://appleid.apple.com", claims.path("aud").asText());
        assertEquals(NOW.getEpochSecond(), claims.path("iat").asLong());
        assertEquals(NOW.getEpochSecond() + AppleTokens.SECRET_TTL_SECONDS,
                claims.path("exp").asLong());
    }

    @Test
    public void 서명은_DER이_아니라_JOSE_원시형식() throws Exception {
        String token = AppleTokens.clientSecret(
                keys.getPrivate(), "KEY123", "TEAM12345", "com.mega.dali", NOW);
        String[] parts = token.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);

        // P-256의 R‖S는 정확히 64바이트. DER이면 70바이트 안팎이고 0x30으로 시작한다.
        assertEquals(64, signature.length);

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(signature));
    }

    @Test
    public void p8은_한줄이든_PEM이든_읽는다() throws Exception {
        String base64 = Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + String.join("\n", base64.split("(?<=\\G.{64})")) + "\n-----END PRIVATE KEY-----\n";

        assertEquals(keys.getPrivate(), AppleTokens.parseP8(pem));
        // 배포 워크플로가 넘기는 형태(줄·헤더 제거된 base64 한 줄)
        assertEquals(keys.getPrivate(), AppleTokens.parseP8(base64));
        // GitHub Secret → env 를 거치며 줄바꿈이 문자 '\n' 두 글자로 들어오는 경우
        assertEquals(keys.getPrivate(), AppleTokens.parseP8(pem.replace("\n", "\\n")));
    }

    static String decode(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
    }
}
