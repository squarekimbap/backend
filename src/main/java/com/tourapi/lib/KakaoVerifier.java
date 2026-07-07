package com.tourapi.lib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 카카오 사용자 토큰 검증(kapi /v2/user/me). 앱이 보낸 액세스 토큰 자체로 검증하므로
 * 서버용 카카오 키는 필요 없다. 토큰은 절대 로그에 남기지 않는다.
 */
@ApplicationScoped
public class KakaoVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_NICKNAME = "카카오사용자";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    @ConfigProperty(name = "auth.kakao.user-me-url",
            defaultValue = "https://kapi.kakao.com/v2/user/me")
    String userMeUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT).build();

    /** 카카오가 확인해 준 사용자. id = 회원번호(서비스 내 고유). */
    public record KakaoUser(long id, String nickname) {
    }

    /** 토큰 검증 + 사용자 조회. 무효 토큰 401 → InvalidKakaoTokenException, 그 외 실패 → UpstreamException. */
    public KakaoUser verify(String accessToken) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(userMeUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401) {
                throw new InvalidKakaoTokenException();
            }
            if (res.statusCode() != 200) {
                throw new UpstreamException("카카오 API 오류: HTTP " + res.statusCode());
            }
            return parse(res.body());
        } catch (InvalidKakaoTokenException | UpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw new UpstreamException("카카오 API 호출 실패: " + e.getClass().getSimpleName());
        }
    }

    /** 응답 파싱. 닉네임은 kakao_account.profile → properties 순으로 찾고 없으면 기본값. */
    static KakaoUser parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            long id = root.path("id").asLong(0);
            if (id == 0) {
                throw new UpstreamException("카카오 응답에 id 없음");
            }
            String nickname = root.path("kakao_account").path("profile").path("nickname")
                    .asText(root.path("properties").path("nickname").asText(DEFAULT_NICKNAME));
            return new KakaoUser(id, nickname.isBlank() ? DEFAULT_NICKNAME : nickname);
        } catch (UpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw new UpstreamException("카카오 응답 파싱 실패");
        }
    }
}
