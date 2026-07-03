package com.tourapi.lib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Google Elevation (⚠️ 내부 전용 — 키는 URL 쿼리로만, 로그/응답에 절대 노출 금지).
 * 키는 env GOOGLE_MAPS_API_KEY로만 주입(MP-Config 자동 매핑).
 */
@ApplicationScoped
public class ElevationClient {

    @ConfigProperty(name = "google.maps-api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "google.elevation-url",
            defaultValue = "https://maps.googleapis.com/maps/api/elevation/json")
    String elevationUrl;

    @Inject
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 좌표([lat,lng]) 목록의 고도(m). 입력 순서 유지. 한 번에 512점 이하로 호출할 것. */
    public double[] elevations(List<double[]> latLngs) {
        String key = apiKey.filter(k -> !k.isBlank())
                .orElseThrow(() -> new UpstreamException("GOOGLE_MAPS_API_KEY 미설정"));

        String locations = latLngs.stream()
                .map(p -> String.format(Locale.ROOT, "%.5f,%.5f", p[0], p[1]))
                .collect(Collectors.joining("|"));
        String url = elevationUrl + "?locations=" + URLEncoder.encode(locations, StandardCharsets.UTF_8)
                + "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 메시지에 url(=key 포함) 절대 금지
            throw new UpstreamException("Elevation 호출 실패: " + e.getClass().getSimpleName(), e);
        }
        if (res.statusCode() != 200) {
            throw new UpstreamException("Elevation HTTP " + res.statusCode());
        }

        JsonNode root;
        try {
            root = mapper.readTree(res.body());
        } catch (Exception e) {
            throw new UpstreamException("Elevation 응답 파싱 실패");
        }
        String status = root.path("status").asText("");
        if (!"OK".equals(status)) {
            throw new UpstreamException("Elevation status=" + status);
        }
        JsonNode results = root.path("results");
        if (!results.isArray() || results.size() != latLngs.size()) {
            throw new UpstreamException("Elevation 결과 수 불일치");
        }
        double[] out = new double[results.size()];
        for (int i = 0; i < results.size(); i++) {
            out[i] = results.get(i).path("elevation").asDouble(0);
        }
        return out;
    }
}
