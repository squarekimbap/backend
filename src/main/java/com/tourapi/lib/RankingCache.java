package com.tourapi.lib;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Duration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * DynamoDB 단일 테이블 캐시(키-값, TTL 자동 청소).
 * <p>CACHE_TABLE 미설정이면 조용히 비활성(로컬 dev/테스트 기본) — 캐시 실패는 절대 요청을 죽이지 않는다.
 */
@ApplicationScoped
public class RankingCache {

    /** 쿼터 예약은 확정됐지만 헤더 표시용 사용량 조회 결과는 알 수 없음. */
    public static final int UNKNOWN_USED = -1;

    private static final Logger LOG = Logger.getLogger(RankingCache.class);

    // 빈 defaultValue는 SmallRye가 '값 없음'으로 취급해 기동이 깨지므로 Optional로 받는다
    @ConfigProperty(name = "cache.table")
    Optional<String> tableOpt;

    @ConfigProperty(name = "route.cache.table")
    Optional<String> routeTableOpt = Optional.empty();

    @Inject
    ObjectMapper mapper;

    volatile DynamoDbClient client;
    private final ConcurrentHashMap<String, LocalCounter> localCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalGenerationLedger> localGenerationLedgers =
            new ConcurrentHashMap<>();

    public boolean enabled() {
        return tableOpt.filter(t -> !t.isBlank()).isPresent();
    }

    private String table() {
        return tableOpt.orElseThrow();
    }

    private boolean routeCacheEnabled() {
        return routeTableOpt.filter(t -> !t.isBlank()).isPresent();
    }

    private String routeTable() {
        return routeTableOpt.orElseThrow();
    }

    private DynamoDbClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    String region = System.getenv().getOrDefault("AWS_REGION", "ap-northeast-2");
                    client = DynamoDbClient.builder()
                            .region(Region.of(region))
                            .httpClientBuilder(UrlConnectionHttpClient.builder())
                            .overrideConfiguration(ClientOverrideConfiguration.builder()
                                    .apiCallAttemptTimeout(Duration.ofMillis(1_200))
                                    .apiCallTimeout(Duration.ofSeconds(2))
                                    .build())
                            .build();
                }
            }
        }
        return client;
    }

    /** 캐시 조회. 미스/비활성/오류 모두 null(호출측은 업스트림으로 폴백). */
    public <T> T get(String key, Class<T> type) {
        return get(key, type, false);
    }

    /** 임대 인계 직후 방금 저장된 값을 놓치지 않기 위한 강한 일관성 조회. */
    public <T> T getConsistent(String key, Class<T> type) {
        return get(key, type, true);
    }

    private <T> T get(String key, Class<T> type, boolean consistentRead) {
        if (!enabled()) {
            return null;
        }
        return getFromTable(table(), key, type, consistentRead);
    }

    /** TMAP 전용 테이블 캐시 조회. */
    public <T> T getRoute(String key, Class<T> type, boolean consistentRead) {
        if (!routeCacheEnabled()) {
            return null;
        }
        return getFromTable(routeTable(), key, type, consistentRead);
    }

    private <T> T getFromTable(String tableName, String key, Class<T> type,
                               boolean consistentRead) {
        try {
            GetItemResponse res = client().getItem(b -> b.tableName(tableName)
                    .key(Map.of("pk", AttributeValue.fromS(key)))
                    .consistentRead(consistentRead));
            if (!res.hasItem()) {
                return null;
            }
            AttributeValue ttl = res.item().get("ttl");
            long now = System.currentTimeMillis() / 1000;
            if (ttl == null || ttl.n() == null || Long.parseLong(ttl.n()) <= now) {
                return null;
            }
            String payload = payloadValue(res.item());
            if (payload == null) {
                return null;
            }
            return mapper.readValue(payload, type);
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
        putToTable(table(), key, value, ttl);
    }

    /** TMAP 전용 테이블 저장. 성공 여부로 임대를 유지할지 결정한다. */
    public boolean putRoute(String key, Object value, Duration ttl) {
        if (!routeCacheEnabled()) {
            return false;
        }
        return putToTable(routeTable(), key, value, ttl);
    }

    private boolean putToTable(String tableName, String key, Object value, Duration ttl) {
        try {
            long expireAt = System.currentTimeMillis() / 1000 + ttl.toSeconds();
            byte[] payload = gzip(mapper.writeValueAsString(value));
            if (payload.length > 350_000) {
                LOG.warnf("캐시 저장 생략(압축 후 크기 초과): key=%s, bytes=%d", key, payload.length);
                return false;
            }
            client().putItem(b -> b.tableName(tableName).item(Map.of(
                    "pk", AttributeValue.fromS(key),
                    "payload", AttributeValue.fromB(SdkBytes.fromByteArray(payload)),
                    "ttl", AttributeValue.fromN(Long.toString(expireAt)))));
            return true;
        } catch (Exception e) {
            LOG.warnf("캐시 저장 실패(무시): %s", e.toString());
            return false;
        }
    }

    /**
     * 여러 Lambda 실행 환경에서 같은 외부 API 캐시 미스를 동시에 계산하지 않도록 짧은 임대를 잡는다.
     * 캐시가 비활성화됐거나 DynamoDB가 불안정하면 호출측이 정상 계산으로 폴백할 수 있게 UNAVAILABLE을 반환한다.
     */
    public CacheLeaseStatus tryAcquireCacheLease(String key, String ownerId, Duration leaseDuration) {
        if (key == null || key.isBlank() || ownerId == null || ownerId.isBlank()
                || leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("올바르지 않은 캐시 임대 설정");
        }
        if (!routeCacheEnabled()) {
            return CacheLeaseStatus.UNAVAILABLE;
        }
        long now = System.currentTimeMillis() / 1000;
        long expiresAt = now + Math.max(1, leaseDuration.toSeconds());
        try {
            client().updateItem(b -> b.tableName(routeTable())
                    .key(Map.of("pk", AttributeValue.fromS(key)))
                    .updateExpression("SET #owner = :owner, #ttl = :expires, #kind = :kind")
                    .conditionExpression("attribute_not_exists(pk) OR #ttl <= :now OR #owner = :owner")
                    .expressionAttributeNames(Map.of(
                            "#owner", "owner",
                            "#ttl", "ttl",
                            "#kind", "kind"))
                    .expressionAttributeValues(Map.of(
                            ":owner", AttributeValue.fromS(ownerId),
                            ":expires", AttributeValue.fromN(Long.toString(expiresAt)),
                            ":now", AttributeValue.fromN(Long.toString(now)),
                            ":kind", AttributeValue.fromS("cache-lease"))));
            return CacheLeaseStatus.ACQUIRED;
        } catch (ConditionalCheckFailedException e) {
            return CacheLeaseStatus.HELD;
        } catch (Exception e) {
            LOG.warnf("캐시 임대 획득 실패(중복 계산 허용): %s", e.toString());
            return CacheLeaseStatus.UNAVAILABLE;
        }
    }

    /** 현재 실행이 소유한 외부 API 캐시 임대만 해제한다. */
    public void releaseCacheLease(String key, String ownerId) {
        if (!routeCacheEnabled() || key == null || key.isBlank()
                || ownerId == null || ownerId.isBlank()) {
            return;
        }
        try {
            client().deleteItem(b -> b.tableName(routeTable())
                    .key(Map.of("pk", AttributeValue.fromS(key)))
                    .conditionExpression("#owner = :owner")
                    .expressionAttributeNames(Map.of("#owner", "owner"))
                    .expressionAttributeValues(Map.of(
                            ":owner", AttributeValue.fromS(ownerId))));
        } catch (ConditionalCheckFailedException ignored) {
            // 임대가 만료돼 다른 실행이 인계한 경우 이전 소유자는 지우지 않는다.
        } catch (Exception e) {
            LOG.warnf("캐시 임대 해제 실패(만료 시 자동 정리): %s", e.toString());
        }
    }

    /**
     * 키별 고정 시간창 호출 제한. 배포 환경에서는 DynamoDB의 원자적 ADD를 사용하고,
     * 로컬/테스트에서는 인스턴스 로컬 카운터를 쓴다. 배포 환경의 DynamoDB 확인이
     * 실패하면 유료 API 보호를 위해 새 요청을 차단한다(fail closed).
     */
    public boolean allow(String key, int limit, Duration window) {
        return consume(key, limit, window) > 0;
    }

    /**
     * 호환용 단발 차감. 정확한 환불이 필요한 호출은 {@link #reserve}를 사용한다.
     */
    public int consume(String key, int limit, Duration window) {
        CounterReservation result = reserve(key, UUID.randomUUID().toString(), limit, window);
        return result.status() == CounterStatus.ALLOWED ? result.used() : 0;
    }

    /**
     * 예약 ID별 사용권을 원자적·멱등적으로 1회 차감한다. DynamoDB가 커밋 후 응답을
     * 잃어 SDK가 재시도해도 같은 ID는 다시 증가하지 않는다.
     */
    public CounterReservation reserve(String key, String reservationId, int limit, Duration window) {
        if (limit < 1 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("올바르지 않은 호출 제한 설정");
        }
        if (key == null || key.isBlank() || reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("키와 예약 ID 필요");
        }
        if (!enabled()) {
            return reserveLocal(key, reservationId, limit, window);
        }
        try {
            long expireAt = System.currentTimeMillis() / 1000 + window.toSeconds();
            var response = client().updateItem(b -> b.tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(key)))
                    .updateExpression("SET #ttl = if_not_exists(#ttl, :ttl) "
                            + "ADD #hits :one, #reservations :reservationSet")
                    .conditionExpression("(attribute_not_exists(#hits) OR #hits < :limit) "
                            + "AND (attribute_not_exists(#reservations) "
                            + "OR NOT contains(#reservations, :reservationId))")
                    .expressionAttributeNames(Map.of(
                            "#hits", "hits", "#ttl", "ttl", "#reservations", "reservations"))
                    .expressionAttributeValues(Map.of(
                            ":one", AttributeValue.fromN("1"),
                            ":limit", AttributeValue.fromN(Integer.toString(limit)),
                            ":reservationSet", AttributeValue.builder().ss(reservationId).build(),
                            ":reservationId", AttributeValue.fromS(reservationId),
                            ":ttl", AttributeValue.fromN(Long.toString(expireAt))))
                    .returnValues(ReturnValue.UPDATED_NEW));
            AttributeValue hits = response.attributes().get("hits");
            if (hits != null && hits.n() != null) {
                return CounterReservation.allowed(Integer.parseInt(hits.n()));
            }
            return resolveReservation(key, reservationId, false);
        } catch (ConditionalCheckFailedException e) {
            return resolveReservation(key, reservationId, true);
        } catch (Exception e) {
            CounterReservation resolved = resolveReservation(key, reservationId, false);
            if (resolved.status() == CounterStatus.ALLOWED) {
                return resolved;
            }
            LOG.warnf("호출 제한 저장 실패(유료 API 보호를 위해 차단): %s", e.toString());
            return CounterReservation.unavailable();
        }
    }

    /**
     * 예약한 사용권을 외부 API/서버 실패 시 예약 ID별로 정확히 한 번 돌려준다.
     * 환불한 ID는 항목에서 제거해 반복 실패에도 DynamoDB String Set이 커지지 않는다.
     */
    public boolean releaseReservation(String key, String reservationId) {
        if (key == null || key.isBlank() || reservationId == null || reservationId.isBlank()) {
            return false;
        }
        if (!enabled()) {
            return releaseLocal(key, reservationId);
        }
        try {
            client().updateItem(b -> b.tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(key)))
                    .updateExpression("ADD #hits :minusOne DELETE #reservations :reservationSet")
                    .conditionExpression("#hits > :zero "
                            + "AND contains(#reservations, :reservationId)")
                    .expressionAttributeNames(Map.of(
                            "#hits", "hits", "#reservations", "reservations"))
                    .expressionAttributeValues(Map.of(
                            ":minusOne", AttributeValue.fromN("-1"),
                            ":zero", AttributeValue.fromN("0"),
                            ":reservationSet", AttributeValue.builder().ss(reservationId).build(),
                            ":reservationId", AttributeValue.fromS(reservationId))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return isReleased(key, reservationId);
        } catch (Exception e) {
            if (isReleased(key, reservationId)) {
                return true;
            }
            LOG.warnf("호출 제한 사용권 환불 실패: %s", e.toString());
            return false;
        }
    }

    /**
     * 일일 사용권과 요청별 IN_PROGRESS 원장을 한 트랜잭션으로 만든다. 이미 완료된
     * 요청은 저장된 JSON을 재생하고, 실행 중이면 서비스 재실행 없이 잠시 대기시킨다.
     */
    public GenerationClaim claimGeneration(String quotaKey, String ledgerKey,
                                            String reservationId, String ownerId,
                                            int limit, Duration storageTtl,
                                            Duration leaseDuration) {
        validateGenerationArgs(quotaKey, ledgerKey, reservationId, ownerId,
                limit, storageTtl, leaseDuration);
        if (!enabled()) {
            return claimGenerationLocal(quotaKey, ledgerKey, reservationId, ownerId,
                    limit, storageTtl, leaseDuration);
        }

        long now = System.currentTimeMillis() / 1000;
        long expireAt = now + storageTtl.toSeconds();
        long leaseUntil = now + leaseDuration.toSeconds();
        try {
            Put ledgerPut = Put.builder()
                    .tableName(table())
                    .item(Map.of(
                            "pk", AttributeValue.fromS(ledgerKey),
                            "state", AttributeValue.fromS("IN_PROGRESS"),
                            "owner", AttributeValue.fromS(ownerId),
                            "reservationId", AttributeValue.fromS(reservationId),
                            "quotaKey", AttributeValue.fromS(quotaKey),
                            "leaseUntil", AttributeValue.fromN(Long.toString(leaseUntil)),
                            "ttl", AttributeValue.fromN(Long.toString(expireAt))))
                    .conditionExpression("attribute_not_exists(#pk)")
                    .expressionAttributeNames(Map.of("#pk", "pk"))
                    .build();
            Update quotaUpdate = Update.builder()
                    .tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(quotaKey)))
                    .updateExpression("SET #ttl = if_not_exists(#ttl, :ttl) "
                            + "ADD #hits :one, #reservations :reservationSet")
                    .conditionExpression("(attribute_not_exists(#hits) OR #hits < :limit) "
                            + "AND (attribute_not_exists(#reservations) "
                            + "OR NOT contains(#reservations, :reservationId))")
                    .expressionAttributeNames(Map.of(
                            "#hits", "hits", "#ttl", "ttl", "#reservations", "reservations"))
                    .expressionAttributeValues(Map.of(
                            ":one", AttributeValue.fromN("1"),
                            ":limit", AttributeValue.fromN(Integer.toString(limit)),
                            ":reservationSet", AttributeValue.builder().ss(reservationId).build(),
                            ":reservationId", AttributeValue.fromS(reservationId),
                            ":ttl", AttributeValue.fromN(Long.toString(expireAt))))
                    .build();
            client().transactWriteItems(b -> b
                    .clientRequestToken(ownerId)
                    .transactItems(
                            TransactWriteItem.builder().put(ledgerPut).build(),
                            TransactWriteItem.builder().update(quotaUpdate).build()));
            return GenerationClaim.owner(readUsedForHeaders(quotaKey, limit), quotaKey);
        } catch (TransactionCanceledException e) {
            return resolveGenerationClaim(quotaKey, ledgerKey, reservationId, ownerId,
                    limit, leaseDuration, now);
        } catch (Exception e) {
            GenerationClaim resolved = resolveGenerationClaim(quotaKey, ledgerKey,
                    reservationId, ownerId, limit, leaseDuration, now);
            if (resolved.status() != GenerationStatus.UNAVAILABLE) {
                return resolved;
            }
            LOG.warnf("코스 생성 상태 예약 실패: %s", e.toString());
            return resolved;
        }
    }

    /** 실행 소유자가 만든 성공 JSON을 짧은 TTL 항목에 저장하고 원장을 완료 처리한다. */
    public boolean completeGeneration(String ledgerKey, String ownerId, String payload,
                                      Duration responseTtl) {
        if (ledgerKey == null || ledgerKey.isBlank() || ownerId == null || ownerId.isBlank()
                || payload == null || responseTtl == null
                || responseTtl.isZero() || responseTtl.isNegative()) {
            return false;
        }
        if (!enabled()) {
            return completeGenerationLocal(ledgerKey, ownerId, payload, responseTtl);
        }
        try {
            byte[] compressed = gzip(payload);
            if (compressed.length > 350_000) {
                LOG.errorf("코스 생성 성공 응답 원장 크기 초과: %d bytes", compressed.length);
                return false;
            }
            String responseKey = ledgerKey + "#response";
            long responseExpireAt = System.currentTimeMillis() / 1000 + responseTtl.toSeconds();
            Update ledgerComplete = Update.builder()
                    .tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(ledgerKey)))
                    .updateExpression("SET #state = :succeeded, #payload = :payload REMOVE #leaseUntil")
                    .conditionExpression("#state = :inProgress AND #owner = :owner")
                    .expressionAttributeNames(Map.of(
                            "#state", "state", "#payload", "responseKey",
                            "#leaseUntil", "leaseUntil", "#owner", "owner"))
                    .expressionAttributeValues(Map.of(
                            ":succeeded", AttributeValue.fromS("SUCCEEDED"),
                            ":inProgress", AttributeValue.fromS("IN_PROGRESS"),
                            ":owner", AttributeValue.fromS(ownerId),
                            ":payload", AttributeValue.fromS(responseKey)))
                    .build();
            Put responsePut = Put.builder()
                    .tableName(table())
                    .item(Map.of(
                            "pk", AttributeValue.fromS(responseKey),
                            "payload", AttributeValue.fromB(SdkBytes.fromByteArray(compressed)),
                            "ttl", AttributeValue.fromN(Long.toString(responseExpireAt))))
                    .build();
            client().transactWriteItems(b -> b
                    .clientRequestToken(UUID.randomUUID().toString())
                    .transactItems(
                            TransactWriteItem.builder().update(ledgerComplete).build(),
                            TransactWriteItem.builder().put(responsePut).build()));
            return true;
        } catch (Exception e) {
            LOG.warnf("코스 생성 성공 응답 원장 저장 실패: %s", e.toString());
            return false;
        }
    }

    /**
     * 현재 실행 소유자의 원장 삭제와 일일 사용권 환불을 한 트랜잭션으로 처리한다.
     * 확인 조회나 동기 재시도를 하지 않아 실패 응답 경로의 추가 대기는 최대 API timeout 2초다.
     */
    public boolean failGeneration(String quotaKey, String ledgerKey,
                                  String reservationId, String ownerId) {
        if (quotaKey == null || quotaKey.isBlank() || ledgerKey == null || ledgerKey.isBlank()
                || reservationId == null || reservationId.isBlank()
                || ownerId == null || ownerId.isBlank()) {
            return false;
        }
        if (!enabled()) {
            return failGenerationLocal(quotaKey, ledgerKey, reservationId, ownerId);
        }
        try {
            Delete ledgerDelete = Delete.builder()
                    .tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(ledgerKey)))
                    .conditionExpression("#state = :inProgress AND #owner = :owner "
                            + "AND #quotaKey = :quotaKey")
                    .expressionAttributeNames(Map.of(
                            "#state", "state", "#owner", "owner", "#quotaKey", "quotaKey"))
                    .expressionAttributeValues(Map.of(
                            ":inProgress", AttributeValue.fromS("IN_PROGRESS"),
                            ":owner", AttributeValue.fromS(ownerId),
                            ":quotaKey", AttributeValue.fromS(quotaKey)))
                    .build();
            Update quotaRefund = Update.builder()
                    .tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(quotaKey)))
                    .updateExpression("ADD #hits :minusOne DELETE #reservations :reservationSet")
                    .conditionExpression("#hits > :zero "
                            + "AND contains(#reservations, :reservationId)")
                    .expressionAttributeNames(Map.of(
                            "#hits", "hits", "#reservations", "reservations"))
                    .expressionAttributeValues(Map.of(
                            ":minusOne", AttributeValue.fromN("-1"),
                            ":zero", AttributeValue.fromN("0"),
                            ":reservationSet", AttributeValue.builder().ss(reservationId).build(),
                            ":reservationId", AttributeValue.fromS(reservationId)))
                    .build();
            client().transactWriteItems(b -> b
                    .clientRequestToken(UUID.randomUUID().toString())
                    .transactItems(
                            TransactWriteItem.builder().delete(ledgerDelete).build(),
                            TransactWriteItem.builder().update(quotaRefund).build()));
            return true;
        } catch (Exception e) {
            LOG.warnf("코스 생성 실패 상태/사용권 정리 실패: %s", e.toString());
            return false;
        }
    }

    private GenerationClaim resolveGenerationClaim(String quotaKey, String ledgerKey,
                                                    String reservationId, String ownerId,
                                                    int limit, Duration leaseDuration,
                                                    long now) {
        try {
            GetItemResponse response = client().getItem(b -> b.tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(ledgerKey)))
                    .consistentRead(true));
            if (response.hasItem()) {
                Map<String, AttributeValue> item = response.item();
                String state = stringValue(item, "state");
                String claimedQuotaKey = stringValue(item, "quotaKey");
                if (claimedQuotaKey == null || claimedQuotaKey.isBlank()) {
                    return GenerationClaim.unavailable();
                }
                if ("SUCCEEDED".equals(state)) {
                    PayloadRead payload = readGenerationPayload(stringValue(item, "responseKey"));
                    return switch (payload.status()) {
                        case FOUND -> GenerationClaim.replay(
                                readUsedForHeaders(quotaKey, limit), payload.payload(),
                                claimedQuotaKey);
                        case EXPIRED -> GenerationClaim.expired();
                        case UNAVAILABLE -> GenerationClaim.unavailable();
                    };
                }
                if ("IN_PROGRESS".equals(state)) {
                    String currentOwner = stringValue(item, "owner");
                    if (ownerId.equals(currentOwner)) {
                        return GenerationClaim.owner(
                                readUsedForHeaders(quotaKey, limit), claimedQuotaKey);
                    }
                    long leaseUntil = numberValue(item, "leaseUntil", Long.MAX_VALUE);
                    if (leaseUntil > now) {
                        return GenerationClaim.inProgress();
                    }
                    return takeOverGeneration(quotaKey, claimedQuotaKey, ledgerKey,
                            ownerId, limit, leaseDuration, now);
                }
                return GenerationClaim.unavailable();
            }
            return resolveMissingLedger(quotaKey, reservationId, limit);
        } catch (Exception e) {
            return GenerationClaim.unavailable();
        }
    }

    private GenerationClaim takeOverGeneration(String currentQuotaKey,
                                                String claimedQuotaKey,
                                                String ledgerKey, String ownerId, int limit,
                                                Duration leaseDuration, long now) {
        long leaseUntil = now + leaseDuration.toSeconds();
        try {
            client().updateItem(b -> b.tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(ledgerKey)))
                    .updateExpression("SET #owner = :owner, #leaseUntil = :leaseUntil")
                    .conditionExpression("#state = :inProgress AND #leaseUntil <= :now")
                    .expressionAttributeNames(Map.of(
                            "#state", "state", "#owner", "owner", "#leaseUntil", "leaseUntil"))
                    .expressionAttributeValues(Map.of(
                            ":inProgress", AttributeValue.fromS("IN_PROGRESS"),
                            ":owner", AttributeValue.fromS(ownerId),
                            ":leaseUntil", AttributeValue.fromN(Long.toString(leaseUntil)),
                            ":now", AttributeValue.fromN(Long.toString(now)))));
            return GenerationClaim.owner(
                    readUsedForHeaders(currentQuotaKey, limit), claimedQuotaKey);
        } catch (ConditionalCheckFailedException e) {
            return GenerationClaim.inProgress();
        } catch (Exception e) {
            return GenerationClaim.unavailable();
        }
    }

    private GenerationClaim resolveMissingLedger(String quotaKey, String reservationId, int limit) {
        try {
            GetItemResponse response = client().getItem(b -> b.tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(quotaKey)))
                    .consistentRead(true));
            if (!response.hasItem()) {
                return GenerationClaim.unavailable();
            }
            Map<String, AttributeValue> item = response.item();
            AttributeValue reservations = item.get("reservations");
            if (reservations != null && reservations.hasSs()
                    && reservations.ss().contains(reservationId)) {
                return GenerationClaim.unavailable();
            }
            int used = (int) numberValue(item, "hits", 0);
            return used >= limit ? GenerationClaim.limited() : GenerationClaim.unavailable();
        } catch (Exception e) {
            return GenerationClaim.unavailable();
        }
    }

    private int readUsedForHeaders(String quotaKey, int limit) {
        try {
            GetItemResponse response = client().getItem(b -> b.tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(quotaKey)))
                    .consistentRead(true));
            return response.hasItem()
                    ? Math.min(limit, (int) numberValue(response.item(), "hits", 0)) : 0;
        } catch (Exception e) {
            LOG.warnf("코스 생성 남은 횟수 조회 실패(예약 상태는 유지): %s", e.toString());
            return UNKNOWN_USED;
        }
    }

    private PayloadRead readGenerationPayload(String responseKey) {
        if (responseKey == null || responseKey.isBlank()) {
            return PayloadRead.expired();
        }
        try {
            GetItemResponse response = client().getItem(b -> b.tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(responseKey)))
                    .consistentRead(true));
            if (!response.hasItem()) {
                return PayloadRead.expired();
            }
            long ttl = numberValue(response.item(), "ttl", 0);
            if (ttl <= System.currentTimeMillis() / 1000) {
                return PayloadRead.expired();
            }
            String payload = payloadValue(response.item());
            return payload == null ? PayloadRead.expired() : PayloadRead.found(payload);
        } catch (Exception e) {
            return PayloadRead.unavailable();
        }
    }

    private static String stringValue(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }

    private static long numberValue(Map<String, AttributeValue> item, String key, long fallback) {
        AttributeValue value = item.get(key);
        if (value == null || value.n() == null) {
            return fallback;
        }
        return Long.parseLong(value.n());
    }

    private static String payloadValue(Map<String, AttributeValue> item) throws IOException {
        AttributeValue value = item.get("payload");
        if (value == null) {
            return null;
        }
        if (value.b() != null) {
            try (GZIPInputStream gzip = new GZIPInputStream(
                    new ByteArrayInputStream(value.b().asByteArray()))) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return value.s();
    }

    private static byte[] gzip(String payload) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(payload.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private enum PayloadStatus {
        FOUND, EXPIRED, UNAVAILABLE
    }

    private record PayloadRead(PayloadStatus status, String payload) {
        static PayloadRead found(String payload) {
            return new PayloadRead(PayloadStatus.FOUND, payload);
        }

        static PayloadRead expired() {
            return new PayloadRead(PayloadStatus.EXPIRED, null);
        }

        static PayloadRead unavailable() {
            return new PayloadRead(PayloadStatus.UNAVAILABLE, null);
        }
    }

    private static void validateGenerationArgs(String quotaKey, String ledgerKey,
                                               String reservationId, String ownerId,
                                               int limit, Duration storageTtl,
                                               Duration leaseDuration) {
        if (quotaKey == null || quotaKey.isBlank() || ledgerKey == null || ledgerKey.isBlank()
                || reservationId == null || reservationId.isBlank()
                || ownerId == null || ownerId.isBlank() || limit < 1
                || storageTtl == null || storageTtl.isZero() || storageTtl.isNegative()
                || leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("올바르지 않은 코스 생성 상태 예약 설정");
        }
    }

    private CounterReservation resolveReservation(String key, String reservationId,
                                                  boolean conditionFailed) {
        try {
            GetItemResponse response = client().getItem(b -> b.tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(key)))
                    .consistentRead(true));
            Map<String, AttributeValue> item = response.item();
            AttributeValue reservations = item.get("reservations");
            if (reservations != null && reservations.hasSs()
                    && reservations.ss().contains(reservationId)) {
                AttributeValue hits = item.get("hits");
                if (hits != null && hits.n() != null) {
                    return CounterReservation.allowed(Integer.parseInt(hits.n()));
                }
            }
            return conditionFailed ? CounterReservation.limited() : CounterReservation.unavailable();
        } catch (Exception e) {
            return CounterReservation.unavailable();
        }
    }

    private boolean isReleased(String key, String reservationId) {
        try {
            GetItemResponse response = client().getItem(b -> b.tableName(table())
                    .key(Map.of("pk", AttributeValue.fromS(key)))
                    .consistentRead(true));
            if (!response.hasItem()) {
                return true;
            }
            AttributeValue reservations = response.item().get("reservations");
            return reservations == null || !reservations.hasSs()
                    || !reservations.ss().contains(reservationId);
        } catch (Exception e) {
            return false;
        }
    }

    private CounterReservation reserveLocal(String key, String reservationId,
                                            int limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowMs = window.toMillis();
        CounterReservation[] result = {CounterReservation.unavailable()};
        localCounters.compute(key, (ignored, current) -> {
            if (current == null || now >= current.expiresAtMs) {
                result[0] = CounterReservation.allowed(1);
                return new LocalCounter(1, now + windowMs, Set.of(reservationId));
            }
            if (current.reservationIds.contains(reservationId)) {
                result[0] = CounterReservation.allowed(current.count);
                return current;
            }
            if (current.count >= limit) {
                result[0] = CounterReservation.limited();
                return current;
            }
            Set<String> reservations = new HashSet<>(current.reservationIds);
            reservations.add(reservationId);
            result[0] = CounterReservation.allowed(current.count + 1);
            return new LocalCounter(current.count + 1, current.expiresAtMs,
                    Set.copyOf(reservations));
        });
        if (localCounters.size() > 10_000) {
            localCounters.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtMs);
        }
        return result[0];
    }

    private boolean releaseLocal(String key, String reservationId) {
        long now = System.currentTimeMillis();
        boolean[] released = {true};
        localCounters.computeIfPresent(key, (ignored, current) -> {
            if (now >= current.expiresAtMs) {
                return null;
            }
            if (!current.reservationIds.contains(reservationId)) {
                return current;
            }
            if (current.count <= 0) {
                released[0] = false;
                return current;
            }
            Set<String> reservations = new HashSet<>(current.reservationIds);
            reservations.remove(reservationId);
            return new LocalCounter(current.count - 1, current.expiresAtMs,
                    Set.copyOf(reservations));
        });
        return released[0];
    }

    private GenerationClaim claimGenerationLocal(String quotaKey, String ledgerKey,
                                                  String reservationId, String ownerId,
                                                  int limit, Duration storageTtl,
                                                  Duration leaseDuration) {
        long now = System.currentTimeMillis();
        synchronized (localGenerationLedgers) {
            LocalGenerationLedger current = localGenerationLedgers.get(ledgerKey);
            if (current != null && now >= current.expiresAtMs) {
                localGenerationLedgers.remove(ledgerKey);
                current = null;
            }
            if (current != null) {
                if (current.succeeded) {
                    return now < current.responseExpiresAtMs
                            ? GenerationClaim.replay(localUsedForHeaders(quotaKey, limit),
                                    current.payload, current.quotaKey)
                            : GenerationClaim.expired();
                }
                if (ownerId.equals(current.ownerId)) {
                    return GenerationClaim.owner(
                            localUsedForHeaders(quotaKey, limit), current.quotaKey);
                }
                if (now < current.leaseUntilMs) {
                    return GenerationClaim.inProgress();
                }
                localGenerationLedgers.put(ledgerKey, new LocalGenerationLedger(
                        false, ownerId, now + leaseDuration.toMillis(),
                        current.expiresAtMs, 0, null, current.quotaKey));
                return GenerationClaim.owner(
                        localUsedForHeaders(quotaKey, limit), current.quotaKey);
            }

            CounterReservation quota = reserveLocal(
                    quotaKey, reservationId, limit, storageTtl);
            if (quota.status() == CounterStatus.LIMITED) {
                return GenerationClaim.limited();
            }
            if (quota.status() != CounterStatus.ALLOWED) {
                return GenerationClaim.unavailable();
            }
            localGenerationLedgers.put(ledgerKey, new LocalGenerationLedger(
                    false, ownerId, now + leaseDuration.toMillis(),
                    now + storageTtl.toMillis(), 0, null, quotaKey));
            return GenerationClaim.owner(quota.used(), quotaKey);
        }
    }

    private boolean completeGenerationLocal(String ledgerKey, String ownerId, String payload,
                                            Duration responseTtl) {
        synchronized (localGenerationLedgers) {
            LocalGenerationLedger current = localGenerationLedgers.get(ledgerKey);
            if (current == null || current.succeeded || !ownerId.equals(current.ownerId)) {
                return false;
            }
            localGenerationLedgers.put(ledgerKey, new LocalGenerationLedger(
                    true, ownerId, 0, current.expiresAtMs,
                    System.currentTimeMillis() + responseTtl.toMillis(), payload,
                    current.quotaKey));
            return true;
        }
    }

    private boolean failGenerationLocal(String quotaKey, String ledgerKey,
                                        String reservationId, String ownerId) {
        synchronized (localGenerationLedgers) {
            LocalGenerationLedger current = localGenerationLedgers.get(ledgerKey);
            if (current == null || current.succeeded || !ownerId.equals(current.ownerId)
                    || !quotaKey.equals(current.quotaKey)) {
                return false;
            }
            if (!releaseLocal(quotaKey, reservationId)) {
                return false;
            }
            localGenerationLedgers.remove(ledgerKey);
            return true;
        }
    }

    private int localUsedForHeaders(String quotaKey, int limit) {
        LocalCounter counter = localCounters.get(quotaKey);
        if (counter == null || System.currentTimeMillis() >= counter.expiresAtMs) {
            return 0;
        }
        return Math.min(limit, counter.count);
    }

    public enum CounterStatus {
        ALLOWED, LIMITED, UNAVAILABLE
    }

    public enum CacheLeaseStatus {
        ACQUIRED, HELD, UNAVAILABLE
    }

    public record CounterReservation(CounterStatus status, int used) {
        public static CounterReservation allowed(int used) {
            return new CounterReservation(CounterStatus.ALLOWED, used);
        }

        public static CounterReservation limited() {
            return new CounterReservation(CounterStatus.LIMITED, 0);
        }

        public static CounterReservation unavailable() {
            return new CounterReservation(CounterStatus.UNAVAILABLE, 0);
        }
    }

    public enum GenerationStatus {
        OWNER, REPLAY, IN_PROGRESS, EXPIRED, LIMITED, UNAVAILABLE
    }

    public record GenerationClaim(GenerationStatus status, int used, String payload,
                                  String quotaKey) {
        public static GenerationClaim owner(int used) {
            return owner(used, null);
        }

        public static GenerationClaim owner(int used, String quotaKey) {
            return new GenerationClaim(GenerationStatus.OWNER, used, null, quotaKey);
        }

        public static GenerationClaim replay(int used, String payload) {
            return replay(used, payload, null);
        }

        public static GenerationClaim replay(int used, String payload, String quotaKey) {
            return new GenerationClaim(GenerationStatus.REPLAY, used, payload, quotaKey);
        }

        public static GenerationClaim inProgress() {
            return new GenerationClaim(GenerationStatus.IN_PROGRESS, 0, null, null);
        }

        public static GenerationClaim expired() {
            return new GenerationClaim(GenerationStatus.EXPIRED, 0, null, null);
        }

        public static GenerationClaim limited() {
            return new GenerationClaim(GenerationStatus.LIMITED, 0, null, null);
        }

        public static GenerationClaim unavailable() {
            return new GenerationClaim(GenerationStatus.UNAVAILABLE, 0, null, null);
        }
    }

    private record LocalCounter(int count, long expiresAtMs, Set<String> reservationIds) {
    }

    private record LocalGenerationLedger(boolean succeeded, String ownerId,
                                         long leaseUntilMs, long expiresAtMs,
                                         long responseExpiresAtMs, String payload,
                                         String quotaKey) {
    }
}
