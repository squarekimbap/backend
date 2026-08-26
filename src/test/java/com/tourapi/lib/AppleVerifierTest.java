package com.tourapi.lib;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Apple identityToken 검증(네트워크 없이 자체 RSA 키쌍으로 서명·검증). */
public class AppleVerifierTest {

    static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);
    static final String AUD = "com.seungchan.eodirun";

    static KeyPair keys;
    static String jwks;

    @BeforeAll
    static void 키쌍과_JWKS_생성() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        jwks = "{\"keys\":[{\"kid\":\"k1\",\"alg\":\"RS256\","
                + "\"n\":\"" + b64(unsigned(pub.getModulus())) + "\","
                + "\"e\":\"" + b64(unsigned(pub.getPublicExponent())) + "\"}]}";
    }

    @Test
    public void 정상_토큰이면_sub와_email을_준다() throws Exception {
        AppleVerifier.AppleUser u = AppleVerifier.verify(
                token("k1", claims(AUD, NOW.getEpochSecond() + 600)), jwks, AUD, NOW);
        assertEquals("sub-1", u.sub());
        assertEquals("relay@privaterelay.appleid.com", u.email());
    }

    @Test
    public void 이메일_없으면_null() throws Exception {
        String claims = "{\"iss\":\"https://appleid.apple.com\",\"aud\":\"" + AUD
                + "\",\"exp\":" + (NOW.getEpochSecond() + 600) + ",\"sub\":\"sub-2\"}";
        assertNull(AppleVerifier.verify(token("k1", claims), jwks, AUD, NOW).email());
    }

    @Test
    public void 다른_앱에_발급된_토큰은_거부() throws Exception {
        String token = token("k1", claims("com.other.app", NOW.getEpochSecond() + 600));
        assertThrows(InvalidAppleTokenException.class,
                () -> AppleVerifier.verify(token, jwks, AUD, NOW));
    }

    @Test
    public void 만료된_토큰은_거부() throws Exception {
        String token = token("k1", claims(AUD, NOW.getEpochSecond() - 1));
        assertThrows(InvalidAppleTokenException.class,
                () -> AppleVerifier.verify(token, jwks, AUD, NOW));
    }

    @Test
    public void 서명이_어긋나면_거부() throws Exception {
        String token = token("k1", claims(AUD, NOW.getEpochSecond() + 600));
        // payload 를 바꿔치기해 서명을 깨뜨린다.
        String[] parts = token.split("\\.");
        String tamperedPayload = b64(claims(AUD, NOW.getEpochSecond() + 601)
                .getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];
        assertThrows(InvalidAppleTokenException.class,
                () -> AppleVerifier.verify(tampered, jwks, AUD, NOW));
    }

    @Test
    public void 모르는_kid는_키회전_신호() throws Exception {
        String token = token("k9", claims(AUD, NOW.getEpochSecond() + 600));
        assertThrows(AppleVerifier.UnknownKeyException.class,
                () -> AppleVerifier.verify(token, jwks, AUD, NOW));
    }

    @Test
    public void JWT_형식이_아니면_거부() {
        assertThrows(InvalidAppleTokenException.class,
                () -> AppleVerifier.verify("abc", jwks, AUD, NOW));
        assertThrows(InvalidAppleTokenException.class,
                () -> AppleVerifier.verify(null, jwks, AUD, NOW));
    }

    // ── 토큰 제작 도우미 ─────────────────────────────────────────────

    static String claims(String aud, long exp) {
        return "{\"iss\":\"https://appleid.apple.com\",\"aud\":\"" + aud + "\",\"exp\":" + exp
                + ",\"sub\":\"sub-1\",\"email\":\"relay@privaterelay.appleid.com\"}";
    }

    static String token(String kid, String claimsJson) throws Exception {
        String header = b64(("{\"alg\":\"RS256\",\"kid\":\"" + kid + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        String payload = b64(claimsJson.getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keys.getPrivate());
        signature.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "." + b64(signature.sign());
    }

    /** BigInteger.toByteArray()의 부호 바이트(선행 0)를 떼어 JWK n/e 형식으로. */
    static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
