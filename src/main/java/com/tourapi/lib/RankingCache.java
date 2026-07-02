package com.tourapi.lib;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB 단일 테이블 캐시(키-값, TTL 자동 청소).
 * <p>CACHE_TABLE 미설정이면 조용히 비활성(로컬 dev/테스트 기본) — 캐시 실패는 절대 요청을 죽이지 않는다.
 */
@ApplicationScoped
public class RankingCache {

    private static final Logger LOG = Logger.getLogger(RankingCache.class);

    // 빈 defaultValue는 SmallRye가 '값 없음'으로 취급해 기동이 깨지므로 Optional로 받는다
    @ConfigProperty(name = "cache.table")
    Optional<String> tableOpt;

    @Inject
    ObjectMapper mapper;

    private volatile DynamoDbClient client;

    public boolean enabled() {
        return tableOpt.filter(t -> !t.isBlank()).isPresent();
    }

    private String table() {
        return tableOpt.orElseThrow();
    }

    private DynamoDbClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    String region = System.getenv().getOrDefault("AWS_REGION", "ap-northeast-2");
                    client = DynamoDbClient.builder()
                            .region(Region.of(region))
                            .httpClientBuilder(UrlConnectionHttpClient.builder())
                            .build();
                }
            }
        }
        return client;
    }

    /** 캐시 조회. 미스/비활성/오류 모두 null(호출측은 업스트림으로 폴백). */
    public <T> T get(String key, Class<T> type) {
        if (!enabled()) {
            return null;
        }
        try {
            GetItemResponse res = client().getItem(b -> b.tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(key))));
            if (!res.hasItem()) {
                return null;
            }
            AttributeValue payload = res.item().get("payload");
            if (payload == null || payload.s() == null) {
                return null;
            }
            return mapper.readValue(payload.s(), type);
        } catch (Exception e) {
            LOG.warnf("캐시 조회 실패(무시하고 업스트림 폴백): %s", e.toString());
            return null;
        }
    }

    /** 캐시 저장(실패해도 무시). */
    public void put(String key, Object value, Duration ttl) {
        if (!enabled()) {
            return;
        }
        try {
            long expireAt = System.currentTimeMillis() / 1000 + ttl.toSeconds();
            String json = mapper.writeValueAsString(value);
            client().putItem(b -> b.tableName(table()).item(Map.of(
                    "pk", AttributeValue.fromS(key),
                    "payload", AttributeValue.fromS(json),
                    "ttl", AttributeValue.fromN(Long.toString(expireAt)))));
        } catch (Exception e) {
            LOG.warnf("캐시 저장 실패(무시): %s", e.toString());
        }
    }
}
