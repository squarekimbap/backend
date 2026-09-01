package com.tourapi.lib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * TMAP 보행자 경로 (⚠️ 내부 전용 — appKey는 헤더로만, 로그/응답에 절대 노출 금지).
 * 키는 env TMAP_APP_KEY로만 주입(MP-Config 자동 매핑).
 */
@ApplicationScoped
public class TmapClient {

    static final int MAX_CACHED_PATH_POINTS = 400;
    // TMAP 약관은 저장 데이터를 24시간 이상 사용할 수 없으므로 5분의 안전 여유를 둔다.
    static final Duration ROUTE_CACHE_TTL = Duration.ofHours(23).plusMinutes(55);
    // 전체 생성 예산(22초)+DynamoDB 저장 여유보다 길게 잡아 정상 실행 중 takeover를 막는다.
    static final Duration ROUTE_CACHE_LEASE = Duration.ofSeconds(30);

    @ConfigProperty(name = "tmap.app-key")
    Optional<String> appKey;

    @ConfigProperty(name = "tmap.pedestrian-url",
            defaultValue = "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&format=json")
    String pedestrianUrl;

    @Inject
    ObjectMapper mapper;

    @Inject
    ExternalApiCache externalApiCache;

    @ConfigProperty(name = "tmap.request-timeout-seconds", defaultValue = "8")
    int requestTimeoutSeconds;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 보행 경로 결과: path는 [lat,lng] 목록, distanceM/durationS는 TMAP 합계(도보 기준). */
    public record TmapRoute(List<double[]> path, int distanceM, int durationS) {
    }

    /**
     * start→(via…)→end 보행 경로. 좌표는 [lat,lng], via는 최대 5개(TMAP passList 제한).
     */
    public TmapRoute pedestrian(double[] start, List<double[]> via, double[] end) {
        return pedestrian(start, via, end, Duration.ofSeconds(requestTimeoutSeconds));
    }

    /** 상위 코스 생성 deadline의 남은 시간을 개별 HTTP timeout에도 적용한다. */
    public TmapRoute pedestrian(double[] start, List<double[]> via, double[] end, Duration remaining) {
        String cacheKey = routeCacheKey(start, via, end);
        return externalApiCache.getOrLoad(cacheKey, TmapRoute.class,
                ROUTE_CACHE_TTL, ROUTE_CACHE_LEASE, remaining,
                budget -> requestPedestrian(start, via, end, budget));
    }

    private TmapRoute requestPedestrian(double[] start,
                                        List<double[]> via,
                                        double[] end,
                                        Duration remaining) {
        String key = appKey.filter(k -> !k.isBlank())
                .orElseThrow(() -> new UpstreamException("TMAP_APP_KEY 미설정"));

        ObjectNode body = mapper.createObjectNode();
        body.put("startX", fmt(start[1]));
        body.put("startY", fmt(start[0]));
        body.put("endX", fmt(end[1]));
        body.put("endY", fmt(end[0]));
        if (via != null && !via.isEmpty()) {
            body.put("passList", via.stream()
                    .map(p -> fmt(p[1]) + "," + fmt(p[0]))
                    .collect(Collectors.joining("_")));
        }
        body.put("reqCoordType", "WGS84GEO");
        body.put("resCoordType", "WGS84GEO");
        // 이름은 응답에서 안 쓰므로 ASCII 고정(한글 인코딩 이슈 회피)
        body.put("startName", "start");
        body.put("endName", "end");

        HttpRequest req = HttpRequest.newBuilder(URI.create(pedestrianUrl))
                .timeout(effectiveTimeout(remaining, requestTimeoutSeconds))
                .header("appKey", key)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new UpstreamException("TMAP 호출 실패: " + e.getClass().getSimpleName(), e);
        }

        JsonNode root;
        try {
            root = mapper.readTree(res.body());
        } catch (Exception e) {
            throw new UpstreamException("TMAP 응답 파싱 실패(HTTP " + res.statusCode() + ")");
        }
        if (res.statusCode() != 200) {
            String msg = root.path("error").path("message").asText("");
            throw new UpstreamException("TMAP HTTP " + res.statusCode() + (msg.isEmpty() ? "" : " (" + msg + ")"));
        }

        List<double[]> path = new ArrayList<>();
        Integer dist = null;
        Integer time = null;
        for (JsonNode f : root.path("features")) {
            JsonNode props = f.path("properties");
            if (dist == null && props.hasNonNull("totalDistance")) {
                dist = props.path("totalDistance").asInt();
                time = props.path("totalTime").asInt();
            }
            JsonNode geom = f.path("geometry");
            if ("LineString".equals(geom.path("type").asText())) {
                for (JsonNode c : geom.path("coordinates")) {
                    path.add(new double[]{c.path(1).asDouble(), c.path(0).asDouble()}); // [lng,lat]→[lat,lng]
                }
            }
        }
        if (dist == null || path.isEmpty()) {
            throw new UpstreamException("TMAP 경로 없음");
        }
        // route-options 응답은 최종 200점이고 구간 매칭에도 400점이면 충분하다.
        // raw TMAP 수천 점을 그대로 DynamoDB에 쓰지 않아 cold miss WCU 폭주를 제한한다.
        List<double[]> compactPath = List.copyOf(Geo.downsample(path, MAX_CACHED_PATH_POINTS));
        return new TmapRoute(compactPath, dist, time == null ? 0 : time);
    }

    /** 약 10m 안의 GPS 흔들림은 같은 키로 보되 실제 TMAP 요청에는 원본 좌표를 쓴다. */
    static String routeCacheKey(double[] start, List<double[]> via, double[] end) {
        StringBuilder raw = new StringBuilder("tmap-pedestrian-v1|");
        appendCachePoint(raw, start);
        raw.append('|');
        if (via != null) {
            for (double[] point : via) {
                appendCachePoint(raw, point);
                raw.append(';');
            }
        }
        raw.append('|');
        appendCachePoint(raw, end);
        return CacheKey.sha256("tmap-route-v1", raw.toString());
    }

    private static void appendCachePoint(StringBuilder out, double[] point) {
        out.append(String.format(Locale.ROOT, "%.4f,%.4f", point[0], point[1]));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static Duration effectiveTimeout(Duration remaining, int configuredSeconds) {
        Duration configured = Duration.ofSeconds(configuredSeconds);
        if (remaining == null || remaining.isNegative() || remaining.isZero()) {
            throw new UpstreamException("TMAP 호출 가용 시간 없음");
        }
        Duration timeout = remaining.compareTo(configured) < 0 ? remaining : configured;
        return timeout.compareTo(Duration.ofMillis(1)) < 0 ? Duration.ofMillis(1) : timeout;
    }
}
