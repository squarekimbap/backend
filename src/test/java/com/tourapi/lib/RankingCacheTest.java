package com.tourapi.lib;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

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
    @SuppressWarnings("unchecked")
    void DynamoDb호출제한저장실패시요청을차단한다() {
        RankingCache cache = dynamoCache();
        when(cache.client.updateItem(any(Consumer.class)))
                .thenThrow(DynamoDbException.builder().message("timeout").build());

        assertFalse(cache.allow("user#minute", 2, Duration.ofMinutes(1)));
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
