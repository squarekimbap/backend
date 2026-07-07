package com.tourapi.lib;

import com.tourapi.model.UserProfile;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * users 테이블(pk=userId) 접근. USERS_TABLE 미설정이면 비활성(로컬 dev/테스트 기본).
 * 프로필 저장 실패는 로그인 자체를 죽이지 않는다 — RankingCache와 같은 폴백 철학.
 */
@ApplicationScoped
public class UserStore {

    private static final Logger LOG = Logger.getLogger(UserStore.class);

    @ConfigProperty(name = "users.table")
    Optional<String> tableOpt;

    private volatile DynamoDbClient client;

    public boolean enabled() {
        return tableOpt.filter(t -> !t.isBlank()).isPresent();
    }

    /** 최초 1회만 생성(이미 있으면 그대로 둠 — 이후 닉네임 변경 등을 덮어쓰지 않기 위해). 실패는 경고 로그 후 무시. */
    public void putIfAbsent(UserProfile p) {
        if (!enabled()) {
            return;
        }
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("userId", AttributeValue.fromS(p.userId()));
            if (p.email() != null) {
                item.put("email", AttributeValue.fromS(p.email()));
            }
            item.put("nickname", AttributeValue.fromS(p.nickname() == null ? "" : p.nickname()));
            item.put("provider", AttributeValue.fromS(p.provider()));
            item.put("createdAt", AttributeValue.fromS(p.createdAt()));
            client().putItem(b -> b.tableName(tableOpt.orElseThrow()).item(item)
                    .conditionExpression("attribute_not_exists(userId)"));
        } catch (ConditionalCheckFailedException e) {
            // 이미 존재 — 정상 (재로그인)
        } catch (Exception e) {
            LOG.warnf("프로필 저장 실패(무시): %s", e.toString());
        }
    }

    /** 프로필 조회. 없음/비활성/오류 모두 null. */
    public UserProfile get(String userId) {
        if (!enabled()) {
            return null;
        }
        try {
            GetItemResponse res = client().getItem(b -> b.tableName(tableOpt.orElseThrow())
                    .key(Map.of("userId", AttributeValue.fromS(userId))));
            if (!res.hasItem()) {
                return null;
            }
            Map<String, AttributeValue> it = res.item();
            return new UserProfile(userId,
                    it.containsKey("email") ? it.get("email").s() : null,
                    it.containsKey("nickname") ? it.get("nickname").s() : null,
                    it.containsKey("provider") ? it.get("provider").s() : null,
                    it.containsKey("createdAt") ? it.get("createdAt").s() : null);
        } catch (Exception e) {
            LOG.warnf("프로필 조회 실패: %s", e.toString());
            return null;
        }
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
