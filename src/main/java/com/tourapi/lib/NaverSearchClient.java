package com.tourapi.lib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 네이버 지역검색 (⚠️ 내부 전용 — client id/secret은 헤더로만, 로그/응답에 절대 노출 금지).
 * 키는 env NAVER_CLIENT_ID / NAVER_CLIENT_SECRET로만 주입(MP-Config 자동 매핑).
 *
 * <p>보강 용도라 실패해도 요청을 죽이지 않는다 — 미설정·오류 모두 빈 리스트(RankingCache 폴백 철학).
 */
@ApplicationScoped
public class NaverSearchClient {

    private static final Logger LOG = Logger.getLogger(NaverSearchClient.class);

    @ConfigProperty(name = "naver.client-id")
    Optional<String> clientId;

    @ConfigProperty(name = "naver.client-secret")
    Optional<String> clientSecret;

    @ConfigProperty(name = "naver.local-url",
            defaultValue = "https://openapi.naver.com/v1/search/local.json")
    String localUrl;

    @ConfigProperty(name = "naver.request-timeout-seconds", defaultValue = "4")
    int requestTimeoutSeconds;

    @Inject
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** 지역검색 결과 1건. 좌표는 확인 가능한 경우에만 채운다. */
    public record LocalPlace(String name, String category, String addr,
                             String tel, String link, Double lat, Double lng) {
    }

    public boolean enabled() {
        return clientId.filter(v -> !v.isBlank()).isPresent()
                && clientSecret.filter(v -> !v.isBlank()).isPresent();
    }

    /**
     * 지역검색. sort=comment면 리뷰(블로그 언급) 많은 순 — 최신 트렌드 반영.
     * display는 API 상한이 5다.
     */
    public List<LocalPlace> local(String query, int display, String sort) {
        if (!enabled() || query == null || query.isBlank()) {
            return List.of();
        }
        String url = localUrl
                + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&display=" + Math.max(1, Math.min(5, display))
                + "&sort=" + ("comment".equals(sort) ? "comment" : "random");

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("X-Naver-Client-Id", clientId.orElseThrow())
                .header("X-Naver-Client-Secret", clientSecret.orElseThrow())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOG.warnf("네이버 지역검색 호출 실패(무시): %s", e.getClass().getSimpleName());
            return List.of();
        }
        if (res.statusCode() != 200) {
            // 본문에 키가 실릴 일은 없지만 상태코드만 남긴다
            LOG.warnf("네이버 지역검색 HTTP %d (query=%s)", res.statusCode(), query);
            return List.of();
        }

        try {
            JsonNode root = mapper.readTree(res.body());
            List<LocalPlace> out = new ArrayList<>();
            for (JsonNode it : root.path("items")) {
                String name = stripTags(it.path("title").asText(""));
                if (name.isBlank()) {
                    continue;
                }
                String addr = firstNonBlank(it.path("roadAddress").asText(""),
                        it.path("address").asText(""));
                double[] coord = wgs84(it.path("mapx").asDouble(0), it.path("mapy").asDouble(0));
                out.add(new LocalPlace(name,
                        blankToNull(it.path("category").asText("")),
                        blankToNull(addr),
                        blankToNull(it.path("telephone").asText("")),
                        blankToNull(it.path("link").asText("")),
                        coord == null ? null : coord[0],
                        coord == null ? null : coord[1]));
            }
            return out;
        } catch (Exception e) {
            LOG.warnf("네이버 지역검색 파싱 실패(무시): %s", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * 지역검색 mapx/mapy → [lat,lng].
     * 현재 API는 WGS84에 10^7을 곱한 정수를 준다. 한반도 범위를 벗어나면(구 KATEC 등)
     * 잘못 변환하느니 좌표 없음으로 두고 이름 매칭만 쓴다.
     */
    static double[] wgs84(double mapx, double mapy) {
        if (mapx <= 0 || mapy <= 0) {
            return null;
        }
        double lng = mapx / 1e7;
        double lat = mapy / 1e7;
        if (lng < 124 || lng > 132 || lat < 33 || lat > 39) {
            return null;
        }
        return new double[]{lat, lng};
    }

    /** 검색어 강조 태그(&lt;b&gt;)와 HTML 엔티티 정리. */
    static String stripTags(String s) {
        return s.replaceAll("<[^>]*>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .strip();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
