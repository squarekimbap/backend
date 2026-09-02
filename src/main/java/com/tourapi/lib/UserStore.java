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

    /**
     * Apple refresh 토큰 보관(탈퇴 시 폐기용 — 심사 5.1.1(v)). 로그인마다 최신 값으로 덮는다.
     * 내부 전용이라 UserProfile에는 넣지 않는다 — /users/me 응답에 실려 나가면 안 된다.
     * 저장 실패는 삼킨다: 부가 작업이 로그인을 죽이면 안 되고 다음 로그인이 다시 시도한다.
     */
    public void putAppleRefreshToken(String userId, String token) {
        if (!enabled()) {
            return;
        }
        try {
            client().updateItem(b -> b.tableName(tableOpt.orElseThrow())
                    .key(Map.of("userId", AttributeValue.fromS(userId)))
                    .updateExpression("SET appleRefreshToken = :t")
                    .expressionAttributeValues(Map.of(":t", AttributeValue.fromS(token)))
                    .conditionExpression("attribute_exists(userId)"));
        } catch (ConditionalCheckFailedException e) {
            // 프로필 행이 아직 없음(putIfAbsent도 실패) — 다음 로그인에 다시 저장된다
        } catch (Exception e) {
            LOG.warnf("Apple 토큰 저장 실패(무시): %s", e.toString());
        }
    }

    /** 폐기할 Apple refresh 토큰. 없으면 null(다른 로그인 수단이거나 저장 실패 — 둘 다 정상 흐름). */
    public String appleRefreshToken(String userId) {
        if (!enabled()) {
            return null;
        }
        try {
            GetItemResponse res = client().getItem(b -> b.tableName(tableOpt.orElseThrow())
                    .key(Map.of("userId", AttributeValue.fromS(userId)))
                    .projectionExpression("appleRefreshToken"));
            return res.hasItem() && res.item().containsKey("appleRefreshToken")
                    ? res.item().get("appleRefreshToken").s() : null;
        } catch (Exception e) {
            LOG.warnf("Apple 토큰 조회 실패: %s", e.toString());
            return null;
        }
    }

    /**
     * 전 사용자 목록(관리자 조회용). 가입자가 수천 명을 넘으면 scan은 더 이상 맞지 않는다.
     * ponytail: 지금 규모(수 명)에선 scan이 맞다, 페이지네이션은 느려지면 붙인다.
     * appleRefreshToken은 내부 전용이라 여기서 걸러낸다 — 화면에 값이 나가면 안 된다.
     */
    public java.util.List<Map<String, String>> listAll() {
        if (!enabled()) {
            return java.util.List.of();
        }
        var out = new java.util.ArrayList<Map<String, String>>();
        Map<String, AttributeValue> start = null;
        do {
            final Map<String, AttributeValue> from = start;
            var res = client().scan(b -> {
                b.tableName(tableOpt.orElseThrow());
                if (from != null) {
                    b.exclusiveStartKey(from);
                }
            });
            for (var item : res.items()) {
                var row = new HashMap<String, String>();
                item.forEach((k, v) -> {
                    if (!"appleRefreshToken".equals(k) && v.s() != null) {
                        row.put(k, v.s());
                    }
                });
                row.put("hasAppleToken", String.valueOf(item.containsKey("appleRefreshToken")));
                out.add(row);
            }
            start = res.hasLastEvaluatedKey() ? res.lastEvaluatedKey() : null;
        } while (start != null);
        out.sort(java.util.Comparator.comparing(r -> r.getOrDefault("createdAt", "")));
        return out;
    }

    /** 프로필 삭제(탈퇴). 없는 행을 지워도 성공 — 재시도 안전. 실패는 위로 던진다. */
    public void delete(String userId) {
        if (!enabled()) {
            return;
        }
        client().deleteItem(b -> b.tableName(tableOpt.orElseThrow())
                .key(Map.of("userId", AttributeValue.fromS(userId))));
    }

    /**
     * 닉네임 변경. 프로필 행이 없으면 false(조회의 404와 같은 처리를 위해).
     * putIfAbsent와 달리 실패를 삼키지 않는다 — 사용자가 명시적으로 요청한 변경이
     * 조용히 사라지면 안 되므로 예외는 그대로 위로 던진다.
     */
    public boolean updateNickname(String userId, String nickname) {
        if (!enabled()) {
            return false;
        }
        try {
            client().updateItem(b -> b.tableName(tableOpt.orElseThrow())
                    .key(Map.of("userId", AttributeValue.fromS(userId)))
                    .updateExpression("SET #nick = :nick")
                    .expressionAttributeNames(Map.of("#nick", "nickname"))
                    .expressionAttributeValues(Map.of(":nick", AttributeValue.fromS(nickname)))
                    .conditionExpression("attribute_exists(userId)"));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
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
