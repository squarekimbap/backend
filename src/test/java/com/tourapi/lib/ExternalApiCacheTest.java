package com.tourapi.lib;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExternalApiCacheTest {

    @Test
    void 캐시적중이면임대나외부호출을하지않는다() {
        RankingCache cache = mock(RankingCache.class);
        ExternalApiCache apiCache = service(cache);
        CachedValue cached = new CachedValue("cached");
        when(cache.getRoute("key", CachedValue.class, false)).thenReturn(cached);
        AtomicInteger loads = new AtomicInteger();

        CachedValue result = apiCache.getOrLoad("key", CachedValue.class,
                Duration.ofHours(24), Duration.ofSeconds(10), Duration.ofSeconds(2),
                ignored -> {
                    loads.incrementAndGet();
                    return new CachedValue("new");
                });

        assertSame(cached, result);
        assertEquals(0, loads.get());
        verify(cache, never()).tryAcquireCacheLease(anyString(), anyString(), any());
    }

    @Test
    void 같은프로세스의동시키계산은한번만실행한다() throws Exception {
        RankingCache cache = mock(RankingCache.class);
        when(cache.tryAcquireCacheLease(anyString(), anyString(), any()))
                .thenReturn(RankingCache.CacheLeaseStatus.UNAVAILABLE);
        ExternalApiCache apiCache = service(cache);
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> apiCache.getOrLoad("same", CachedValue.class,
                    Duration.ofHours(24), Duration.ofSeconds(10), Duration.ofSeconds(3),
                    ignored -> {
                        loads.incrementAndGet();
                        loaderStarted.countDown();
                        await(releaseLoader);
                        return new CachedValue("one");
                    }));
            loaderStarted.await(1, TimeUnit.SECONDS);
            CountDownLatch secondStarted = new CountDownLatch(1);
            var second = pool.submit(() -> {
                secondStarted.countDown();
                return apiCache.getOrLoad("same", CachedValue.class,
                        Duration.ofHours(24), Duration.ofSeconds(10), Duration.ofSeconds(3),
                        ignored -> {
                            loads.incrementAndGet();
                            return new CachedValue("two");
                        });
            });

            secondStarted.await(1, TimeUnit.SECONDS);
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> second.get(100, TimeUnit.MILLISECONDS));
            releaseLoader.countDown();

            assertEquals("one", first.get(1, TimeUnit.SECONDS).value());
            assertEquals("one", second.get(1, TimeUnit.SECONDS).value());
            assertEquals(1, loads.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 다른실행이계산한값은임대인계후강한조회로재사용한다() {
        RankingCache cache = mock(RankingCache.class);
        ExternalApiCache apiCache = service(cache);
        CachedValue cached = new CachedValue("other-owner");
        when(cache.tryAcquireCacheLease(anyString(), anyString(), any()))
                .thenReturn(RankingCache.CacheLeaseStatus.HELD,
                        RankingCache.CacheLeaseStatus.ACQUIRED);
        when(cache.getRoute("shared", CachedValue.class, true)).thenReturn(cached);
        AtomicInteger loads = new AtomicInteger();

        CachedValue result = apiCache.getOrLoad("shared", CachedValue.class,
                Duration.ofHours(24), Duration.ofSeconds(10), Duration.ofSeconds(2),
                ignored -> {
                    loads.incrementAndGet();
                    return new CachedValue("duplicate");
                });

        assertSame(cached, result);
        assertEquals(0, loads.get());
        verify(cache).releaseCacheLease(anyString(), anyString());
    }

    @Test
    void 새결과는지정한24시간Ttl로저장한다() {
        RankingCache cache = mock(RankingCache.class);
        when(cache.tryAcquireCacheLease(anyString(), anyString(), any()))
                .thenReturn(RankingCache.CacheLeaseStatus.UNAVAILABLE);
        ExternalApiCache apiCache = service(cache);
        CachedValue created = new CachedValue("new");

        CachedValue result = apiCache.getOrLoad("new-key", CachedValue.class,
                Duration.ofHours(24), Duration.ofSeconds(10), Duration.ofSeconds(3),
                ignored -> created);

        assertSame(created, result);
        verify(cache).putRoute("new-key", created, Duration.ofHours(24));
    }

    @Test
    void 임대소유자가저장에성공하면임대를조기삭제하지않는다() {
        RankingCache cache = mock(RankingCache.class);
        when(cache.tryAcquireCacheLease(anyString(), anyString(), any()))
                .thenReturn(RankingCache.CacheLeaseStatus.ACQUIRED);
        when(cache.putRoute(eq("owned"), any(), eq(Duration.ofHours(24)))).thenReturn(true);
        ExternalApiCache apiCache = service(cache);

        CachedValue result = apiCache.getOrLoad("owned", CachedValue.class,
                Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofSeconds(3),
                ignored -> new CachedValue("stored"));

        assertEquals("stored", result.value());
        verify(cache).getRoute("owned", CachedValue.class, true);
        verify(cache, never()).releaseCacheLease(anyString(), anyString());
    }

    @Test
    void 저장예산이부족하면put을생략하고임대를해제한다() {
        RankingCache cache = mock(RankingCache.class);
        when(cache.tryAcquireCacheLease(anyString(), anyString(), any()))
                .thenReturn(RankingCache.CacheLeaseStatus.ACQUIRED);
        ExternalApiCache apiCache = service(cache);

        CachedValue result = apiCache.getOrLoad("short", CachedValue.class,
                Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofSeconds(2),
                ignored -> new CachedValue("uncached"));

        assertEquals("uncached", result.value());
        verify(cache, never()).putRoute(anyString(), any(), any());
        verify(cache).releaseCacheLease(anyString(), anyString());
    }

    @Test
    void 최초Dynamo조회시간도전체예산에포함한다() {
        RankingCache cache = mock(RankingCache.class);
        when(cache.getRoute("slow", CachedValue.class, false)).thenAnswer(ignored -> {
            Thread.sleep(100);
            return null;
        });
        ExternalApiCache apiCache = service(cache);

        assertThrows(UpstreamException.class, () -> apiCache.getOrLoad(
                "slow", CachedValue.class, Duration.ofHours(24), Duration.ofSeconds(30),
                Duration.ofMillis(300), ignored -> new CachedValue("late")));
        verify(cache, never()).tryAcquireCacheLease(anyString(), anyString(), any());
    }

    @Test
    void 외부Api캐시키에는버전과좌표순서가반영된다() {
        String nearA = TmapClient.routeCacheKey(
                new double[]{37.123441, 127.123441},
                List.of(new double[]{37.2, 127.2}), new double[]{37.3, 127.3});
        String nearB = TmapClient.routeCacheKey(
                new double[]{37.123449, 127.123449},
                List.of(new double[]{37.2, 127.2}), new double[]{37.3, 127.3});
        String reversed = TmapClient.routeCacheKey(
                new double[]{37.123449, 127.123449},
                List.of(new double[]{37.3, 127.3}), new double[]{37.2, 127.2});
        assertEquals(nearA, nearB);
        assertNotEquals(nearA, reversed);
        assertEquals(Duration.ofHours(23).plusMinutes(55), TmapClient.ROUTE_CACHE_TTL);
        assertEquals(Duration.ofSeconds(30), TmapClient.ROUTE_CACHE_LEASE);
        assertEquals(400, TmapClient.MAX_CACHED_PATH_POINTS);
    }

    private static ExternalApiCache service(RankingCache cache) {
        ExternalApiCache service = new ExternalApiCache();
        service.cache = cache;
        return service;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record CachedValue(String value) {
    }
}
