package com.tourapi.lib;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Apple 토큰 교환·폐기. App Store 심사 5.1.1(v)는 탈퇴 시 Sign in with Apple 토큰 폐기를 요구한다.
 * 폐기에는 Apple refresh 토큰이 필요한데, 그걸 받으려면 authorizationCode가 있어야 하고
 * 코드는 5분·1회용이라 탈퇴 때까지 들고 있을 수 없다 → 로그인 순간에 교환해 두고 탈퇴 때 폐기한다.
 *
 * <p>세 설정(team-id·key-id·private-key)이 다 있어야 동작한다. 하나라도 없으면 조용히 꺼진 상태로
 * 로그인·탈퇴가 그대로 돌아간다(로컬 dev·테스트 기본). 키와 토큰은 로그에 남기지 않는다.
 */
@ApplicationScoped
public class AppleTokens {

    private static final Logger LOG = Logger.getLogger(AppleTokens.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String APPLE_AUDIENCE = "https://appleid.apple.com";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** client_secret 수명. Apple 상한은 6개월이지만 요청 1건에만 쓰므로 짧게 끊는다. */
    static final long SECRET_TTL_SECONDS = 300;

    @ConfigProperty(name = "auth.apple.token-url",
            defaultValue = "https://appleid.apple.com/auth/token")
    String tokenUrl;

    @ConfigProperty(name = "auth.apple.revoke-url",
            defaultValue = "https://appleid.apple.com/auth/revoke")
    String revokeUrl;

    /** client_id = 앱 번들 ID. identityToken의 aud와 같은 값이라 설정을 공유한다. */
    @ConfigProperty(name = "auth.apple.audience")
    String clientId;

    @ConfigProperty(name = "auth.apple.team-id")
    Optional<String> teamId;

    @ConfigProperty(name = "auth.apple.key-id")
    Optional<String> keyId;

    /** Apple Developer의 Sign in with Apple 키(.p8) 내용. 한 줄로 붙여 넣어도 된다. */
    @ConfigProperty(name = "auth.apple.private-key")
    Optional<String> privateKeyPem;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    private volatile PrivateKey signingKey;

    public boolean enabled() {
        return present(teamId) && present(keyId) && present(privateKeyPem);
    }

    /**
     * authorizationCode → Apple refresh 토큰. 실패하면 null이고 예외를 던지지 않는다 —
     * 탈퇴 대비용 부가 작업이라 로그인 자체를 깨뜨리면 안 된다.
     */
    public String exchangeCode(String authorizationCode) {
        if (blank(authorizationCode)) {
            return null;
        }
        if (!enabled()) {
            // 코드는 왔는데 설정이 없으면 폐기를 영영 못 한다. 조용히 넘기면 "로그인이 안 온 것"과
            // 구분이 안 돼 진단이 막히므로, 이 경우만은 드러낸다.
            LOG.warn("Apple 폐기 설정 없음 — authorizationCode를 받았지만 교환하지 않음"
                    + "(AUTH_APPLE_TEAM_ID/KEY_ID/PRIVATE_KEY 확인, 심사 5.1.1(v) 미충족)");
            return null;
        }
        try {
            HttpResponse<String> res = post(tokenUrl, Map.of(
                    "client_id", clientId,
                    "client_secret", clientSecret(),
                    "code", authorizationCode,
                    "grant_type", "authorization_code"));
            if (res.statusCode() != 200) {
                LOG.warnf("Apple code 교환 실패: HTTP %d %s", res.statusCode(), errorOf(res.body()));
                return null;
            }
            String refresh = MAPPER.readTree(res.body()).path("refresh_token").asText("");
            if (refresh.isBlank()) {
                LOG.warn("Apple code 교환 응답에 refresh_token 없음");
                return null;
            }
            // 성공도 남긴다 — 조용하면 "코드를 안 보낸 것"과 구분이 안 돼 검증이 불가능하다.
            LOG.info("Apple refresh 토큰 교환 성공 — 탈퇴 시 폐기 가능");
            return refresh;
        } catch (Exception e) {
            LOG.warnf("Apple code 교환 실패: %s", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 탈퇴 시 Apple 토큰 폐기(심사 5.1.1(v)). 이미 폐기된 토큰도 Apple은 200으로 답한다.
     * 폐기가 실패해도 탈퇴는 계속돼야 하므로 예외를 던지지 않고 로그만 남긴다.
     */
    public void revoke(String appleRefreshToken) {
        if (!enabled() || blank(appleRefreshToken)) {
            return;
        }
        try {
            HttpResponse<String> res = post(revokeUrl, Map.of(
                    "client_id", clientId,
                    "client_secret", clientSecret(),
                    "token", appleRefreshToken,
                    "token_type_hint", "refresh_token"));
            if (res.statusCode() != 200) {
                LOG.warnf("Apple 토큰 폐기 실패: HTTP %d %s", res.statusCode(), errorOf(res.body()));
            } else {
                LOG.info("Apple 토큰 폐기 완료 — 심사 5.1.1(v) 충족");
            }
        } catch (Exception e) {
            LOG.warnf("Apple 토큰 폐기 실패: %s", e.getClass().getSimpleName());
        }
    }

    // ── 내부 ────────────────────────────────────────────────────────

    String clientSecret() throws Exception {
        return clientSecret(signingKey(), keyId.orElseThrow(), teamId.orElseThrow(),
                clientId, Instant.now());
    }

    /**
     * client_secret = .p8 키로 ES256 서명한 짧은 JWT(순수 함수 — 테스트가 자체 키쌍으로 부른다).
     * Apple은 iss=팀 ID, sub=번들 ID, aud=appleid.apple.com, kid=키 ID를 본다.
     */
    static String clientSecret(PrivateKey key, String keyId, String teamId, String clientId,
                               Instant now) throws Exception {
        String header = b64(("{\"alg\":\"ES256\",\"kid\":\"" + keyId + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        String claims = b64(("{\"iss\":\"" + teamId + "\",\"iat\":" + now.getEpochSecond()
                + ",\"exp\":" + (now.getEpochSecond() + SECRET_TTL_SECONDS)
                + ",\"aud\":\"" + APPLE_AUDIENCE + "\",\"sub\":\"" + clientId + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        // JOSE는 서명을 R‖S 원시 64바이트로 요구한다. 기본 "SHA256withECDSA"는 DER을 뱉어
        // 그대로 실으면 Apple이 invalid_client로 거절한다 — P1363 변형이 곧 JOSE 형식이다.
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(key);
        signature.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + b64(signature.sign());
    }

    /** .p8(PKCS#8 PEM) 파싱. 줄바꿈이 없거나 '\n'이 문자 그대로 들어와도 받아 준다. */
    static PrivateKey parseP8(String pem) throws Exception {
        String body = pem.replace("\\n", "")
                .replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    private PrivateKey signingKey() throws Exception {
        if (signingKey == null) {
            synchronized (this) {
                if (signingKey == null) {
                    signingKey = parseP8(privateKeyPem.orElseThrow());
                }
            }
        }
        return signingKey;
    }

    private HttpResponse<String> post(String url, Map<String, String> form) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(encode(form), StandardCharsets.UTF_8))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    /** Apple 오류 본문에서 error 코드만 꺼낸다 — 본문 전체를 로그에 흘리지 않기 위해. */
    private static String errorOf(String body) {
        try {
            return MAPPER.readTree(body).path("error").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean present(Optional<String> v) {
        return v.filter(s -> !s.isBlank()).isPresent();
    }
}
