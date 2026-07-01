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
import java.util.Map;

/**
 * 공공데이터포털 호출 전담. serviceKey 인코딩을 한 곳에서 통일한다.
 * <p>TOUR_API_KEY 에는 '디코딩(Decoding)' 키(raw)를 넣는다. 여기서 한 번만 URL 인코딩하므로
 * 이미 인코딩된 키를 넣으면 이중 인코딩으로 깨진다.
 */
@ApplicationScoped
public class TourApiClient {

    @ConfigProperty(name = "tour.api.base-url")
    String baseUrl;

    @ConfigProperty(name = "tour.api.service-key", defaultValue = "")
    String serviceKey;

    @Inject
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * operation(예: locationBasedList2) 호출 후 JSON 트리를 반환한다.
     *
     * @param operation 서비스 오퍼레이션 이름
     * @param params    serviceKey 를 제외한 쿼리 파라미터(삽입 순서 유지 권장)
     */
    public JsonNode get(String operation, Map<String, String> params) {
        return getFrom(baseUrl, operation, params);
    }

    /**
     * 기본 base-url 대신 다른 서비스 base(예: TatsCnctrRateService)를 지정해 호출한다.
     *
     * @param base 서비스 base URL (오퍼레이션 이름 제외)
     */
    public JsonNode getFrom(String base, String operation, Map<String, String> params) {
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new UpstreamException("TOUR_API_KEY 미설정 (공공데이터포털 인증키 필요)");
        }

        String url = base + "/" + operation + "?" + query(params);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 메시지에 url(=serviceKey 포함)을 절대 넣지 않는다
            throw new UpstreamException("TourAPI 호출 실패: " + e.getClass().getSimpleName(), e);
        }

        if (res.statusCode() != 200) {
            throw new UpstreamException("TourAPI HTTP " + res.statusCode());
        }

        String body = res.body();
        // data.go.kr 은 _type=json 이어도 키/한도 오류 시 XML 에러 봉투를 준다
        if (body == null || body.isBlank() || body.stripLeading().charAt(0) != '{') {
            throw new UpstreamException("TourAPI 비정상 응답(JSON 아님): " + snippet(body));
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new UpstreamException("TourAPI 응답 파싱 실패", e);
        }
    }

    private String query(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("serviceKey=").append(enc(serviceKey));
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            sb.append('&').append(e.getKey()).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String snippet(String s) {
        if (s == null) {
            return "(빈 응답)";
        }
        String t = s.strip();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
