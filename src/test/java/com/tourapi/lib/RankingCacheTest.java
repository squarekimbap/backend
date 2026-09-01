package com.tourapi.lib;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RankingCacheTest {

    @Test
    void 로컬에서도키별호출상한을지킨다() {
        RankingCache cache = new RankingCache();
        cache.tableOpt = Optional.empty();

        assertTrue(cache.allow("user#minute", 2, Duration.ofMinutes(1)));
        assertTrue(cache.allow("user#minute", 2, Duration.ofMinutes(1)));
        assertFalse(cache.allow("user#minute", 2, Duration.ofMinutes(1)));
        assertTrue(cache.allow("other#minute", 2, Duration.ofMinutes(1)));
    }

    @Test
    void 차단된시도는횟수를늘리지않고예약을환불할수있다() {
        RankingCache cache = new RankingCache();
        cache.tableOpt = Optional.empty();

        assertEquals(1, cache.reserve("user#day", "reservation-a", 2, Duration.ofDays(1)).used());
        assertEquals(1, cache.reserve("user#day", "reservation-a", 2, Duration.ofDays(1)).used());
        assertEquals(2, cache.reserve("user#day", "reservation-b", 2, Duration.ofDays(1)).used());
        assertEquals(RankingCache.CounterStatus.LIMITED,
                cache.reserve("user#day", "reservation-c", 2, Duration.ofDays(1)).status());

        assertTrue(cache.releaseReservation("user#day", "reservation-a"));
        assertTrue(cache.releaseReservation("user#day", "reservation-a"));
        assertEquals(2, cache.reserve("user#day", "reservation-c", 2, Duration.ofDays(1)).used());
    }

    @Test
    void 동시예약도설정한상한을넘지않는다() {
        RankingCache cache = new RankingCache();
        cache.tableOpt = Optional.empty();

        long allowed = IntStream.range(0, 50).parallel()
                .mapToObj(i -> cache.reserve("concurrent#day", "reservation-" + i,
                        3, Duration.ofDays(1)))
                .filter(result -> result.status() == RankingCache.CounterStatus.ALLOWED)
                .count();

        assertEquals(3, allowed);
    }

    @Test
    void 로컬상태원장은_중복실행을막고성공응답을재생한다() {
        RankingCache cache = new RankingCache();
        cache.tableOpt = Optional.empty();

        var owner = cache.claimGeneration("quota", "ledger", "reservation", "owner-a",
                3, Duration.ofDays(1), Duration.ofSeconds(30));
        var duplicate = cache.claimGeneration("quota", "ledger", "reservation", "owner-b",
                3, Duration.ofDays(1), Duration.ofSeconds(30));

        assertEquals(RankingCache.GenerationStatus.OWNER, owner.status());
        assertEquals(RankingCache.GenerationStatus.IN_PROGRESS, duplicate.status());
        assertTrue(cache.completeGeneration(
                "ledger", "owner-a", "{\"ok\":true}", Duration.ofMinutes(5)));

        var replay = cache.claimGeneration("quota", "ledger", "reservation", "owner-c",
                3, Duration.ofDays(1), Duration.ofSeconds(30));
        assertEquals(RankingCache.GenerationStatus.REPLAY, replay.status());
        assertEquals("{\"ok\":true}", replay.payload());
    }

    @Test
    void 자정을넘긴같은원장은_원래날짜쿼터를보존하고새날짜를차감하지않는다() {
        RankingCache cache = new RankingCache();
        cache.tableOpt = Optional.empty();

        var owner = cache.claimGeneration("quota#20260901", "stable-ledger",
                "reservation", "owner-a", 3, Duration.ofDays(1), Duration.ofSeconds(30));
        assertEquals("quota#20260901", owner.quotaKey());
        assertTrue(cache.completeGeneration(
                "stable-ledger", "owner-a", "{\"ok\":true}", Duration.ofMinutes(5)));

        var replay = cache.claimGeneration("quota#20260902", "stable-ledger",
                "reservation", "owner-b", 3, Duration.ofDays(1), Duration.ofSeconds(30));

        assertEquals(RankingCache.GenerationStatus.REPLAY, replay.status());
        assertEquals("quota#20260901", replay.quotaKey());
        assertEquals(0, replay.used());
        assertEquals(1, cache.reserve("quota#20260902", "new-request",
                3, Duration.ofDays(1)).used());
    }

    @Test
    void 로컬상태원장은_실행소유자만환불하고새재시도를허용한다() {
        RankingCache cache = new RankingCache();
        cache.tableOpt = Optional.empty();
        cache.claimGeneration("quota", "ledger", "reservation", "owner-a",
                1, Duration.ofDays(1), Duration.ofSeconds(30));

        assertFalse(cache.failGeneration("quota", "ledger", "reservation", "owner-b"));
        assertTrue(cache.failGeneration("quota", "ledger", "reservation", "owner-a"));

        var retried = cache.claimGeneration("quota", "ledger", "reservation", "owner-c",
                1, Duration.ofDays(1), Duration.ofSeconds(30));
        assertEquals(RankingCache.GenerationStatus.OWNER, retried.status());
    }

    @Test
    void 임대만료후소유권을넘기면_이전실행은성공확정이나환불을못한다() {
        RankingCache cache = new RankingCache();
        cache.tableOpt = Optional.empty();
        cache.claimGeneration("quota", "ledger", "reservation", "owner-a",
                1, Duration.ofDays(1), Duration.ofNanos(1));

        var takeover = cache.claimGeneration("quota", "ledger", "reservation", "owner-b",
                1, Duration.ofDays(1), Duration.ofSeconds(30));

        assertEquals(RankingCache.GenerationStatus.OWNER, takeover.status());
        assertFalse(cache.completeGeneration(
                "ledger", "owner-a", "{\"old\":true}", Duration.ofMinutes(5)));
        assertFalse(cache.failGeneration("quota", "ledger", "reservation", "owner-a"));
        assertTrue(cache.completeGeneration(
                "ledger", "owner-b", "{\"new\":true}", Duration.ofMinutes(5)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void DynamoDb상태원장은_쿼터와요청상태를한트랜잭션으로예약한다() {
        RankingCache cache = dynamoCache();
        AtomicReference<TransactWriteItemsRequest> captured = new AtomicReference<>();
        when(cache.client.transactWriteItems(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<TransactWriteItemsRequest.Builder> consumer = invocation.getArgument(0);
            TransactWriteItemsRequest.Builder builder = TransactWriteItemsRequest.builder();
            consumer.accept(builder);
            captured.set(builder.build());
            return TransactWriteItemsResponse.builder().build();
        });
        when(cache.client.getItem(any(Consumer.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of("hits", AttributeValue.fromN("1"))).build());

        var result = cache.claimGeneration("quota", "ledger", "reservation",
                "135a2e12-6189-4e76-ae3c-cb0dac7d11b2", 3,
                Duration.ofDays(1), Duration.ofSeconds(30));

        assertEquals(RankingCache.GenerationStatus.OWNER, result.status());
        assertEquals(2, captured.get().transactItems().size());
        assertEquals("ledger", captured.get().transactItems().get(0).put().item().get("pk").s());
        assertTrue(captured.get().transactItems().get(1).update()
                .conditionExpression().contains("#hits < :limit"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 예약후헤더용조회장애는_남은횟수를미확정으로표시한다() {
        RankingCache cache = dynamoCache();
        when(cache.client.transactWriteItems(any(Consumer.class)))
                .thenReturn(TransactWriteItemsResponse.builder().build());
        when(cache.client.getItem(any(Consumer.class)))
                .thenThrow(DynamoDbException.builder().message("read unavailable").build());

        var result = cache.claimGeneration("quota", "ledger", "reservation",
                "135a2e12-6189-4e76-ae3c-cb0dac7d11b2", 3,
                Duration.ofDays(1), Duration.ofSeconds(30));

        assertEquals(RankingCache.GenerationStatus.OWNER, result.status());
        assertEquals(RankingCache.UNKNOWN_USED, result.used());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 완료된DynamoDb원장은_저장된응답을재생한다() {
        RankingCache cache = dynamoCache();
        when(cache.client.transactWriteItems(any(Consumer.class)))
                .thenThrow(TransactionCanceledException.builder().message("exists").build());
        when(cache.client.getItem(any(Consumer.class))).thenReturn(
                GetItemResponse.builder().item(Map.of(
                        "state", AttributeValue.fromS("SUCCEEDED"),
                        "quotaKey", AttributeValue.fromS("quota#20260901"),
                        "responseKey", AttributeValue.fromS("ledger#response"))).build(),
                GetItemResponse.builder().item(Map.of(
                        "payload", AttributeValue.fromS("{\"ok\":true}"),
                        "ttl", AttributeValue.fromN(Long.toString(
                                System.currentTimeMillis() / 1000 + 60)))).build(),
                GetItemResponse.builder().build());

        var result = cache.claimGeneration("quota#20260902", "ledger", "reservation",
                "135a2e12-6189-4e76-ae3c-cb0dac7d11b2", 3,
                Duration.ofDays(1), Duration.ofSeconds(30));

        assertEquals(RankingCache.GenerationStatus.REPLAY, result.status());
        assertEquals("{\"ok\":true}", result.payload());
        assertEquals("quota#20260901", result.quotaKey());
        assertEquals(0, result.used());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 성공응답조회장애는_결과만료가아니라저장소장애로구분한다() {
        RankingCache cache = dynamoCache();
        when(cache.client.transactWriteItems(any(Consumer.class)))
                .thenThrow(TransactionCanceledException.builder().message("exists").build());
        when(cache.client.getItem(any(Consumer.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of(
                        "state", AttributeValue.fromS("SUCCEEDED"),
                        "quotaKey", AttributeValue.fromS("quota"),
                        "responseKey", AttributeValue.fromS("ledger#response"))).build())
                .thenThrow(DynamoDbException.builder().message("response unavailable").build());

        var result = cache.claimGeneration("quota", "ledger", "reservation",
                "135a2e12-6189-4e76-ae3c-cb0dac7d11b2", 3,
                Duration.ofDays(1), Duration.ofSeconds(30));

        assertEquals(RankingCache.GenerationStatus.UNAVAILABLE, result.status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 성공응답은압축저장하고_같은JSON으로복원한다() {
        RankingCache cache = dynamoCache();
        AtomicReference<TransactWriteItemsRequest> completion = new AtomicReference<>();
        when(cache.client.transactWriteItems(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<TransactWriteItemsRequest.Builder> consumer = invocation.getArgument(0);
            TransactWriteItemsRequest.Builder builder = TransactWriteItemsRequest.builder();
            consumer.accept(builder);
            completion.set(builder.build());
            return TransactWriteItemsResponse.builder().build();
        }).thenThrow(TransactionCanceledException.builder().message("exists").build());
        String payload = "{\"path\":[" + "[37.5,127.0],".repeat(200) + "[37.6,127.1]]}";

        assertTrue(cache.completeGeneration("ledger", "owner", payload, Duration.ofMinutes(5)));
        AttributeValue storedPayload = completion.get().transactItems().get(1)
                .put().item().get("payload");
        assertTrue(storedPayload.b().asByteArray().length
                < payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

        when(cache.client.getItem(any(Consumer.class))).thenReturn(
                GetItemResponse.builder().item(Map.of(
                        "state", AttributeValue.fromS("SUCCEEDED"),
                        "quotaKey", AttributeValue.fromS("quota"),
                        "responseKey", AttributeValue.fromS("ledger#response"))).build(),
                GetItemResponse.builder().item(Map.of(
                        "payload", storedPayload,
                        "ttl", AttributeValue.fromN(Long.toString(
                                System.currentTimeMillis() / 1000 + 60)))).build(),
                GetItemResponse.builder().item(Map.of("hits", AttributeValue.fromN("1"))).build());

        var replay = cache.claimGeneration("quota", "ledger", "reservation",
                "135a2e12-6189-4e76-ae3c-cb0dac7d11b2", 3,
                Duration.ofDays(1), Duration.ofSeconds(30));
        assertEquals(payload, replay.payload());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 실패정리는_소유권확인과쿼터환불을한트랜잭션으로구성한다() {
        RankingCache cache = dynamoCache();
        AtomicReference<TransactWriteItemsRequest> captured = new AtomicReference<>();
        when(cache.client.transactWriteItems(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<TransactWriteItemsRequest.Builder> consumer = invocation.getArgument(0);
            TransactWriteItemsRequest.Builder builder = TransactWriteItemsRequest.builder();
            consumer.accept(builder);
            captured.set(builder.build());
            return TransactWriteItemsResponse.builder().build();
        });

        assertTrue(cache.failGeneration("quota", "ledger", "reservation", "owner"));

        assertEquals(2, captured.get().transactItems().size());
        assertTrue(captured.get().transactItems().get(0).delete()
                .conditionExpression().contains("#owner = :owner"));
        assertTrue(captured.get().transactItems().get(0).delete()
                .conditionExpression().contains("#quotaKey = :quotaKey"));
        assertTrue(captured.get().transactItems().get(1).update()
                .updateExpression().contains("DELETE #reservations"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void DynamoDb조건부실패후예약ID가있으면_재시도성공으로복원한다() {
        RankingCache cache = dynamoCache();
        when(cache.client.updateItem(any(Consumer.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("limit").build());
        when(cache.client.getItem(any(Consumer.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of(
                        "hits", AttributeValue.fromN("1"),
                        "reservations", AttributeValue.builder().ss("reservation-a").build()))
                .build());

        var result = cache.reserve("user#day", "reservation-a", 3, Duration.ofDays(1));

        assertEquals(RankingCache.CounterStatus.ALLOWED, result.status());
        assertEquals(1, result.used());
    }

    @Test
    @SuppressWarnings("unchecked")
    void DynamoDb조건부실패에예약ID가없으면_실제한도초과다() {
        RankingCache cache = dynamoCache();
        when(cache.client.updateItem(any(Consumer.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("limit").build());
        when(cache.client.getItem(any(Consumer.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of(
                        "hits", AttributeValue.fromN("3"),
                        "reservations", AttributeValue.builder().ss("other").build()))
                .build());

        assertEquals(RankingCache.CounterStatus.LIMITED,
                cache.reserve("user#day", "reservation-a", 3, Duration.ofDays(1)).status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void DynamoDb호출제한저장실패는_한도초과와구분한다() {
        RankingCache cache = dynamoCache();
        when(cache.client.updateItem(any(Consumer.class)))
                .thenThrow(DynamoDbException.builder().message("timeout").build());
        when(cache.client.getItem(any(Consumer.class)))
                .thenThrow(DynamoDbException.builder().message("timeout").build());

        assertEquals(RankingCache.CounterStatus.UNAVAILABLE,
                cache.reserve("user#minute", "reservation-a", 2, Duration.ofMinutes(1)).status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void DynamoDb요청에_멱등예약조건을실제로구성한다() {
        RankingCache cache = dynamoCache();
        AtomicReference<UpdateItemRequest> captured = new AtomicReference<>();
        when(cache.client.updateItem(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<UpdateItemRequest.Builder> consumer = invocation.getArgument(0);
            UpdateItemRequest.Builder builder = UpdateItemRequest.builder();
            consumer.accept(builder);
            captured.set(builder.build());
            return UpdateItemResponse.builder()
                    .attributes(Map.of("hits", AttributeValue.fromN("1")))
                    .build();
        });

        var result = cache.reserve("user#day", "reservation-a", 3, Duration.ofDays(1));

        assertEquals(RankingCache.CounterStatus.ALLOWED, result.status());
        assertTrue(captured.get().conditionExpression().contains("contains(#reservations"));
        assertEquals("reservation-a",
                captured.get().expressionAttributeValues().get(":reservationId").s());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 저장결과를확인하지못해도_같은예약ID재시도는중복차감하지않는다() {
        RankingCache cache = dynamoCache();
        when(cache.client.updateItem(any(Consumer.class)))
                .thenThrow(DynamoDbException.builder().message("response lost").build())
                .thenThrow(ConditionalCheckFailedException.builder().message("already reserved").build());
        when(cache.client.getItem(any(Consumer.class)))
                .thenThrow(DynamoDbException.builder().message("read unavailable").build())
                .thenReturn(GetItemResponse.builder().item(Map.of(
                        "hits", AttributeValue.fromN("1"),
                        "reservations", AttributeValue.builder().ss("reservation-a").build()))
                        .build());

        assertEquals(RankingCache.CounterStatus.UNAVAILABLE,
                cache.reserve("user#day", "reservation-a", 3, Duration.ofDays(1)).status());
        var retried = cache.reserve("user#day", "reservation-a", 3, Duration.ofDays(1));

        assertEquals(RankingCache.CounterStatus.ALLOWED, retried.status());
        assertEquals(1, retried.used());
    }

    @Test
    @SuppressWarnings("unchecked")
    void DynamoDb환불요청도_예약ID별로한번만구성한다() {
        RankingCache cache = dynamoCache();
        AtomicReference<UpdateItemRequest> captured = new AtomicReference<>();
        when(cache.client.updateItem(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<UpdateItemRequest.Builder> consumer = invocation.getArgument(0);
            UpdateItemRequest.Builder builder = UpdateItemRequest.builder();
            consumer.accept(builder);
            captured.set(builder.build());
            return UpdateItemResponse.builder().build();
        });

        assertTrue(cache.releaseReservation("user#day", "reservation-a"));

        assertTrue(captured.get().conditionExpression().contains("contains(#reservations"));
        assertTrue(captured.get().updateExpression().contains("DELETE #reservations"));
        assertEquals("-1", captured.get().expressionAttributeValues().get(":minusOne").n());
        assertEquals("reservation-a",
                captured.get().expressionAttributeValues().get(":reservationId").s());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 환불응답을잃어도_예약ID가제거됐으면성공으로복원한다() {
        RankingCache cache = dynamoCache();
        when(cache.client.updateItem(any(Consumer.class)))
                .thenThrow(DynamoDbException.builder().message("response lost").build());
        when(cache.client.getItem(any(Consumer.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of("hits", AttributeValue.fromN("0")))
                .build());

        assertTrue(cache.releaseReservation("user#day", "reservation-a"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 환불저장과확인모두실패하면_실패를반환한다() {
        RankingCache cache = dynamoCache();
        when(cache.client.updateItem(any(Consumer.class)))
                .thenThrow(DynamoDbException.builder().message("write unavailable").build());
        when(cache.client.getItem(any(Consumer.class)))
                .thenThrow(DynamoDbException.builder().message("read unavailable").build());

        assertFalse(cache.releaseReservation("user#day", "reservation-a"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void DynamoDb가아직삭제하지않은만료캐시는직접거른다() throws Exception {
        RankingCache cache = dynamoCache();
        String payload = cache.mapper.writeValueAsString(new CachedValue("old"));
        when(cache.client.getItem(any(Consumer.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of(
                        "pk", AttributeValue.fromS("key"),
                        "payload", AttributeValue.fromS(payload),
                        "ttl", AttributeValue.fromN(Long.toString(System.currentTimeMillis() / 1000 - 1))))
                .build());

        assertNull(cache.get("key", CachedValue.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 만료되지않은DynamoDb캐시는역직렬화한다() throws Exception {
        RankingCache cache = dynamoCache();
        String payload = cache.mapper.writeValueAsString(new CachedValue("fresh"));
        when(cache.client.getItem(any(Consumer.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of(
                        "pk", AttributeValue.fromS("key"),
                        "payload", AttributeValue.fromS(payload),
                        "ttl", AttributeValue.fromN(Long.toString(System.currentTimeMillis() / 1000 + 60))))
                .build());

        assertEquals("fresh", cache.get("key", CachedValue.class).value());
    }

    private static RankingCache dynamoCache() {
        RankingCache cache = new RankingCache();
        cache.tableOpt = Optional.of("cache-table");
        cache.mapper = new ObjectMapper();
        cache.client = mock(DynamoDbClient.class);
        return cache;
    }

    private record CachedValue(String value) {
    }
}
