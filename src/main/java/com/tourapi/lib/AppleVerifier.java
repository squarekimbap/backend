package com.tourapi.lib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Apple identityToken(JWT) 검증. Apple 공개키(JWKS)로 서명을 확인하고 iss·aud·exp를 본다.
 * 검증 대상이 "외부에서 들어온 토큰"이므로 TokenPayload(서명 미검증)를 쓰면 안 되는 자리다.
 * 이름은 첫 로그인에 요청 바디로만 오므로 여기서는 sub·email만 꺼낸다. 토큰은 로그에 남기지 않는다.
 */
@ApplicationScoped
public class AppleVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ISSUER = "https://appleid.apple.com";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** Apple 키는 드물게 회전한다. kid 미스가 나면 캐시를 버리고 한 번 더 받는다. */
    private static final Duration KEYS_TTL = Duration.ofHours(24);

    @ConfigProperty(name = "auth.apple.keys-url", defaultValue = "https://appleid.apple.com/auth/keys")
    String keysUrl;

    /** aud = 앱 번들 ID. 다른 앱에 발급된 토큰으로 로그인하는 것을 막는다. */
    @ConfigProperty(name = "auth.apple.audience", defaultValue = "com.mega.dali")
    String audience;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    private volatile String cachedKeys;
    private volatile Instant cachedAt = Instant.EPOCH;

    /** Apple이 확인해 준 사용자. sub가 고유 식별자, email은 릴레이 주소이거나 없을 수 있다. */
    public record AppleUser(String sub, String email) {
    }

    /** 토큰의 kid가 받아 둔 JWKS에 없을 때 — 키 회전 재시도 신호(내부용). */
    static final class UnknownKeyException extends RuntimeException {
    }

    public AppleUser verify(String identityToken) {
        try {
            return verify(identityToken, keys(false), audience, Instant.now());
        } catch (UnknownKeyException e) {
            try {
                return verify(identityToken, keys(true), audience, Instant.now());
            } catch (UnknownKeyException again) {
                throw new InvalidAppleTokenException();
            }
        }
    }

    /**
     * 검증 본체(순수 함수 — 테스트가 자체 키쌍으로 부른다).
     * 서명 → iss → aud → exp → sub 순서로 확인하고 하나라도 어긋나면 무효 토큰이다.
     */
    static AppleUser verify(String token, String jwksJson, String audience, Instant now) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            throw new InvalidAppleTokenException();
        }
        try {
            JsonNode header = MAPPER.readTree(decode(parts[0]));
            if (!"RS256".equals(header.path("alg").asText())) {
                throw new InvalidAppleTokenException();
            }
            RSAPublicKey key = publicKey(findKey(jwksJson, header.path("kid").asText()));

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw new InvalidAppleTokenException();
            }

            JsonNode claims = MAPPER.readTree(decode(parts[1]));
            if (!ISSUER.equals(claims.path("iss").asText())
                    || !audience.equals(claims.path("aud").asText())
                    || claims.path("exp").asLong(0) <= now.getEpochSecond()) {
                throw new InvalidAppleTokenException();
            }
            String sub = claims.path("sub").asText("");
            if (sub.isBlank()) {
                throw new InvalidAppleTokenException();
            }
            String email = claims.path("email").asText("");
            return new AppleUser(sub, email.isBlank() ? null : email);
        } catch (InvalidAppleTokenException | UnknownKeyException e) {
            throw e;
        } catch (Exception e) {
            // 잘못된 base64, 깨진 JSON, 형식이 어긋난 키 — 전부 무효 토큰으로 본다.
            throw new InvalidAppleTokenException();
        }
    }

    private static byte[] decode(String base64Url) {
        return Base64.getUrlDecoder().decode(base64Url);
    }

    private static JsonNode findKey(String jwksJson, String kid) throws Exception {
        for (JsonNode key : MAPPER.readTree(jwksJson).path("keys")) {
            if (kid.equals(key.path("kid").asText())) {
                return key;
            }
        }
        throw new UnknownKeyException();
    }

    private static RSAPublicKey publicKey(JsonNode jwk) throws Exception {
        BigInteger n = new BigInteger(1, decode(jwk.path("n").asText()));
        BigInteger e = new BigInteger(1, decode(jwk.path("e").asText()));
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(n, e));
    }

    /** JWKS 조회(24시간 캐시). Lambda 인스턴스가 살아 있는 동안 재사용된다. */
    private String keys(boolean force) {
        String cached = cachedKeys;
        if (!force && cached != null && cachedAt.plus(KEYS_TTL).isAfter(Instant.now())) {
            return cached;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(keysUrl))
                    .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new UpstreamException("Apple 키 조회 실패: HTTP " + res.statusCode());
            }
            cachedKeys = res.body();
            cachedAt = Instant.now();
            return res.body();
        } catch (UpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw new UpstreamException("Apple 키 조회 실패: " + e.getClass().getSimpleName());
        }
    }
}
