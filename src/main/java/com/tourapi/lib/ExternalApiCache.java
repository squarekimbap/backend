package com.tourapi.lib;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 유료 외부 API 결과 캐시와 single-flight 조정자.
 * 같은 Lambda 안에서는 Future를 공유하고, 실행 환경이 다르면 DynamoDB의 짧은 임대로 중복 호출을 막는다.
 */
@ApplicationScoped
public class ExternalApiCache {

    private static final long POLL_MILLIS = 150;
    private static final long MIN_REMAINING_MILLIS = 250;
    private static final long STORE_GUARD_MILLIS = 2_500;

    @Inject
    RankingCache cache;

    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight =
            new ConcurrentHashMap<>();

    public <T> T getOrLoad(String key,
                           Class<T> type,
                           Duration ttl,
                           Duration leaseDuration,
                           Duration budget,
                           TimedLoader<T> loader) {
        validate(key, type, ttl, leaseDuration, budget, loader);
        long deadline = deadlineAfter(budget);

        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) {
            return awaitLocal(existing, type, remaining(deadline));
        }

        try {
            T value = loadDistributed(key, type, ttl, leaseDuration, deadline, loader);
            mine.complete(value);
            return value;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private <T> T loadDistributed(String key,
                                  Class<T> type,
                                  Duration ttl,
                                  Duration leaseDuration,
                                  long deadline,
                                  TimedLoader<T> loader) {
        String leaseKey = "external-cache-lease#" + key;
        String ownerId = UUID.randomUUID().toString();

        while (true) {
            requireBudget(deadline);
            T cached = cache.getRoute(key, type, false);
            requireBudget(deadline);
            if (cached != null) {
                return cached;
            }

            RankingCache.CacheLeaseStatus lease = cache.tryAcquireCacheLease(
                    leaseKey, ownerId, leaseDuration);
            if (lease == RankingCache.CacheLeaseStatus.UNAVAILABLE) {
                return loadAndStoreWithoutLease(key, ttl, deadline, loader);
            }
            if (lease == RankingCache.CacheLeaseStatus.ACQUIRED) {
                boolean keepLeaseUntilExpiry = false;
                try {
                    T latest = cache.getRoute(key, type, true);
                    requireBudget(deadline);
                    if (latest != null) {
                        return latest;
                    }
                    T value = Objects.requireNonNull(loader.load(remaining(deadline)),
                            "외부 API 캐시 loader가 null을 반환함");
                    requireBudget(deadline);
                    if (hasStoreBudget(deadline)) {
                        keepLeaseUntilExpiry = cache.putRoute(key, value, ttl);
                        requireBudget(deadline);
                    }
                    return value;
                } finally {
                    // 저장 성공 시 짧은 임대를 그대로 두면 eventual-read 지연 중 재계산도 막는다.
                    if (!keepLeaseUntilExpiry) {
                        cache.releaseCacheLease(leaseKey, ownerId);
                    }
                }
            }

            sleepUntilNextPoll(deadline);
        }
    }

    private <T> T loadAndStoreWithoutLease(String key,
                                           Duration ttl,
                                           long deadline,
                                           TimedLoader<T> loader) {
        T value = Objects.requireNonNull(loader.load(remaining(deadline)),
                "외부 API 캐시 loader가 null을 반환함");
        requireBudget(deadline);
        if (hasStoreBudget(deadline)) {
            cache.putRoute(key, value, ttl);
            requireBudget(deadline);
        }
        return value;
    }

    private static boolean hasStoreBudget(long deadline) {
        return deadline - System.nanoTime()
                > TimeUnit.MILLISECONDS.toNanos(STORE_GUARD_MILLIS);
    }

    private static <T> T awaitLocal(CompletableFuture<Object> future,
                                    Class<T> type,
                                    Duration budget) {
        try {
            return type.cast(future.get(budget.toNanos(), TimeUnit.NANOSECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("외부 API 캐시 대기 중 중단됨", e);
        } catch (TimeoutException e) {
            throw new UpstreamException("외부 API 캐시 대기시간 초과", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new UpstreamException("외부 API 캐시 계산 실패", cause);
        }
    }

    private static void sleepUntilNextPoll(long deadline) {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remainingMillis <= MIN_REMAINING_MILLIS) {
            throw new UpstreamException("외부 API 캐시 대기시간 초과");
        }
        try {
            Thread.sleep(Math.min(POLL_MILLIS, remainingMillis - MIN_REMAINING_MILLIS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("외부 API 캐시 대기 중 중단됨", e);
        }
    }

    private static void requireBudget(long deadline) {
        if (deadline - System.nanoTime() <= TimeUnit.MILLISECONDS.toNanos(MIN_REMAINING_MILLIS)) {
            throw new UpstreamException("외부 API 캐시 처리 가용 시간 없음");
        }
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new UpstreamException("외부 API 호출 가용 시간 없음");
        }
        return Duration.ofNanos(nanos);
    }

    private static long deadlineAfter(Duration budget) {
        long now = System.nanoTime();
        long nanos = budget.toNanos();
        return Long.MAX_VALUE - now < nanos ? Long.MAX_VALUE : now + nanos;
    }

    private static void validate(String key,
                                 Class<?> type,
                                 Duration ttl,
                                 Duration leaseDuration,
                                 Duration budget,
                                 TimedLoader<?> loader) {
        if (key == null || key.isBlank() || type == null || loader == null
                || ttl == null || ttl.isZero() || ttl.isNegative()
                || leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()
                || budget == null || budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("올바르지 않은 외부 API 캐시 설정");
        }
    }

    @FunctionalInterface
    public interface TimedLoader<T> {
        T load(Duration remaining);
    }
}
