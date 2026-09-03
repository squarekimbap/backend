package com.tourapi.lib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 관리자가 바꾼 값의 영구 저장소. 캐시 테이블을 같이 쓰되 <b>ttl 속성을 넣지 않는다</b> —
 * DynamoDB TTL은 그 속성이 있는 항목만 지우므로 없는 항목은 남는다. 테이블을 새로 만들면
 * provisioned 합계가 always-free 25/25를 넘어 과금되기 때문에 이렇게 얹었다.
 *
 * <p>캐시와 정반대의 실패 철학을 쓴다: <b>쓰기 실패는 던진다.</b> 관리자가 저장을 눌렀는데
 * 조용히 사라지면 안 된다({@link UserStore#updateNickname}과 같은 이유).
 */
@ApplicationScoped
public class AdminStore {

    /** 캐시 항목과 섞이지 않게 붙이는 접두사. 이 접두사 항목엔 ttl이 없다. */
    public static final String PREFIX = "admin#";

    @ConfigProperty(name = "cache.table")
    Optional<String> tableOpt;

    @Inject
    ObjectMapper mapper;

    private volatile DynamoDbClient client;

    public boolean enabled() {
        return tableOpt.filter(t -> !t.isBlank()).isPresent();
    }

    /** 없거나 비활성이면 null. 읽기 실패는 던진다 — 관리 화면이 빈 값을 진짜 값으로 착각하면 안 된다. */
    public JsonNode get(String key) {
        if (!enabled()) {
            return null;
        }
        try {
            GetItemResponse res = client().getItem(b -> b.tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(PREFIX + key)))
                    .consistentRead(true));
            AttributeValue json = res.hasItem() ? res.item().get("json") : null;
            return json == null ? null : mapper.readTree(json.s());
        } catch (Exception e) {
            throw new IllegalStateException("관리 저장소 조회 실패: " + e, e);
        }
    }

    /** 접두사가 같은 항목을 전부 읽는다. 항목 수가 적어(설정 1 + 코스 64) scan으로 충분하다. */
    public Map<String, JsonNode> getAll(String keyPrefix) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (!enabled()) {
            return out;
        }
        String full = PREFIX + keyPrefix;
        try {
            Map<String, AttributeValue> start = null;
            do {
                final Map<String, AttributeValue> from = start;
                ScanResponse res = client().scan(b -> {
                    b.tableName(table());
                    b.filterExpression("begins_with(pk, :p)");
                    b.expressionAttributeValues(Map.of(":p", AttributeValue.fromS(full)));
                    if (from != null) {
                        b.exclusiveStartKey(from);
                    }
                });
                for (var item : res.items()) {
                    AttributeValue json = item.get("json");
                    if (json != null && json.s() != null) {
                        out.put(item.get("pk").s().substring(full.length()), mapper.readTree(json.s()));
                    }
                }
                start = res.hasLastEvaluatedKey() ? res.lastEvaluatedKey() : null;
            } while (start != null);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("관리 저장소 목록 조회 실패: " + e, e);
        }
    }

    /** 저장. ttl을 넣지 않아 만료되지 않는다. 실패는 던진다. */
    public void put(String key, Object value) {
        if (!enabled()) {
            throw new IllegalStateException("관리 저장소가 꺼져 있다(CACHE_TABLE 미설정)");
        }
        try {
            String json = mapper.writeValueAsString(value);
            client().putItem(b -> b.tableName(table()).item(Map.of(
                    "pk", AttributeValue.fromS(PREFIX + key),
                    "json", AttributeValue.fromS(json))));
        } catch (Exception e) {
            throw new IllegalStateException("관리 저장소 저장 실패: " + e, e);
        }
    }

    /** 삭제(= 기본값으로 되돌리기). 없는 키를 지워도 성공. */
    public void remove(String key) {
        if (!enabled()) {
            return;
        }
        try {
            client().deleteItem(b -> b.tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(PREFIX + key))));
        } catch (Exception e) {
            throw new IllegalStateException("관리 저장소 삭제 실패: " + e, e);
        }
    }

    private String table() {
        return tableOpt.orElseThrow();
    }

    private DynamoDbClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    String region = System.getenv().getOrDefault("AWS_REGION", "ap-northeast-2");
                    client = DynamoDbClient.builder().region(Region.of(region))
                            .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
                }
            }
        }
        return client;
    }
}
