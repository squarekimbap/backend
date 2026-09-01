package com.tourapi.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourapi.lib.RankingCache;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunningGenerationRateLimiterTest {

    @Test
    void 사용자별KST하루3회를공통상태원장으로예약한다() {
        RankingCache cache = mock(RankingCache.class);
        RunningGenerationRateLimiter limiter = limiter(cache, "2026-09-01T14:30:00Z");
        when(cache.reserve(argThat(key -> key != null && key.startsWith("rate#")),
                anyString(), eq(6), eq(Duration.ofMinutes(2))))
                .thenReturn(RankingCache.CounterReservation.allowed(1));
        when(cache.claimGeneration(eq("quota#running-generation#user-1#20260901"),
                anyString(), anyString(), anyString(), eq(3),
                any(Duration.class), eq(Duration.ofSeconds(30))))
                .thenReturn(RankingCache.GenerationClaim.owner(1),
                        RankingCache.GenerationClaim.owner(2),
                        RankingCache.GenerationClaim.owner(3),
                        RankingCache.GenerationClaim.limited());
        when(cache.failGeneration(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        var first = limiter.acquire("user-1", "request-1", "body-1");
        var second = limiter.acquire("user-1", "request-2", "body-2");
        var third = limiter.acquire("user-1", "request-3", "body-3");
        var fourth = limiter.acquire("user-1", "request-4", "body-4");

        assertEquals(2, first.remaining());
        assertEquals(1, second.remaining());
        assertEquals(0, third.remaining());
        assertFalse(fourth.allowed());
        assertEquals(RunningGenerationRateLimiter.Scope.DAILY, fourth.scope());
        assertEquals(1_800, fourth.retryAfterSeconds());
        assertEquals(Instant.parse("2026-09-01T15:00:00Z").getEpochSecond(),
                fourth.resetEpochSeconds());
        assertTrue(limiter.refund(third));
        verify(cache).failGeneration(third.dailyKey(), third.ledgerKey(),
                third.reservationId(), third.ownerId());
    }

    @Test
    void 예약은성공하고사용량조회만실패하면_잔여량을미확정으로전달한다() {
        RankingCache cache = mock(RankingCache.class);
        RunningGenerationRateLimiter limiter = limiter(cache, "2026-09-01T00:00:00Z");
        when(cache.reserve(anyString(), anyString(), eq(6), eq(Duration.ofMinutes(2))))
                .thenReturn(RankingCache.CounterReservation.allowed(1));
        when(cache.claimGeneration(anyString(), anyString(), anyString(), anyString(), eq(3),
                any(Duration.class), eq(Duration.ofSeconds(30))))
                .thenReturn(RankingCache.GenerationClaim.owner(RankingCache.UNKNOWN_USED));

        var reservation = limiter.acquire("user-unknown", "request-key", "same-body");

        assertTrue(reservation.allowed());
        assertFalse(reservation.remainingKnown());
        assertEquals(-1, reservation.remaining());
    }

    @Test
    void 같은멱등키와요청의중복실행은_진행중으로막는다() {
        RankingCache cache = mock(RankingCache.class);
        RunningGenerationRateLimiter limiter = limiter(cache, "2026-09-01T00:00:00Z");
        when(cache.reserve(anyString(), anyString(), eq(6), eq(Duration.ofMinutes(2))))
                .thenReturn(RankingCache.CounterReservation.allowed(1));
        when(cache.claimGeneration(anyString(), anyString(), anyString(), anyString(), eq(3),
                any(Duration.class), eq(Duration.ofSeconds(30))))
                .thenReturn(RankingCache.GenerationClaim.owner(1),
                        RankingCache.GenerationClaim.inProgress());

        var first = limiter.acquire("user-idempotent", "request-key", "same-body");
        var duplicate = limiter.acquire("user-idempotent", "request-key", "same-body");

        assertTrue(first.ownsExecution());
        assertFalse(duplicate.allowed());
        assertEquals(RunningGenerationRateLimiter.Scope.IDEMPOTENCY, duplicate.scope());
        assertEquals(8, duplicate.retryAfterSeconds());
        ArgumentCaptor<String> minuteReservationIds = ArgumentCaptor.forClass(String.class);
        verify(cache, times(2)).reserve(anyString(), minuteReservationIds.capture(),
                eq(6), eq(Duration.ofMinutes(2)));
        assertNotEquals(minuteReservationIds.getAllValues().get(0),
                minuteReservationIds.getAllValues().get(1));
        ArgumentCaptor<String> reservationIds = ArgumentCaptor.forClass(String.class);
        verify(cache, times(2)).claimGeneration(anyString(), anyString(),
                reservationIds.capture(), anyString(), eq(3),
                any(Duration.class), eq(Duration.ofSeconds(30)));
        assertEquals(reservationIds.getAllValues().get(0), reservationIds.getAllValues().get(1));
    }

    @Test
    void 완료된같은요청은_저장응답을재생한다() {
        RankingCache cache = mock(RankingCache.class);
        RunningGenerationRateLimiter limiter = limiter(cache, "2026-09-01T00:00:00Z");
        when(cache.reserve(anyString(), anyString(), eq(6), eq(Duration.ofMinutes(2))))
                .thenReturn(RankingCache.CounterReservation.allowed(1));
        when(cache.claimGeneration(anyString(), anyString(), anyString(), anyString(), eq(3),
                any(Duration.class), eq(Duration.ofSeconds(30))))
                .thenReturn(RankingCache.GenerationClaim.replay(1, "{\"shape\":\"loop\"}"));

        var replay = limiter.acquire("user-replay", "request-key", "same-body");

        assertTrue(replay.replayed());
        assertEquals("{\"shape\":\"loop\"}", replay.replayPayload());
        assertFalse(replay.ownsExecution());
    }

    @Test
    void 성공응답보관기간이지난같은키는_만료로종료한다() {
        RankingCache cache = mock(RankingCache.class);
        RunningGenerationRateLimiter limiter = limiter(cache, "2026-09-01T00:00:00Z");
        when(cache.reserve(anyString(), anyString(), eq(6), eq(Duration.ofMinutes(2))))
                .thenReturn(RankingCache.CounterReservation.allowed(1));
        when(cache.claimGeneration(anyString(), anyString(), anyString(), anyString(), eq(3),
                any(Duration.class), eq(Duration.ofSeconds(30))))
                .thenReturn(RankingCache.GenerationClaim.expired());

        var expired = limiter.acquire("user-expired", "request-key", "same-body");

        assertFalse(expired.allowed());
        assertEquals(RunningGenerationRateLimiter.Scope.IDEMPOTENCY_EXPIRED, expired.scope());
    }

    @Test
    void 같은멱등키라도_요청내용이다르면_별도예약ID를쓴다() {
        RankingCache cache = mock(RankingCache.class);
        RunningGenerationRateLimiter limiter = limiter(cache, "2026-09-01T00:00:00Z");
        when(cache.reserve(anyString(), anyString(), eq(6), eq(Duration.ofMinutes(2))))
                .thenReturn(RankingCache.CounterReservation.allowed(1));
        when(cache.claimGeneration(anyString(), anyString(), anyString(), anyString(), eq(3),
                any(Duration.class), eq(Duration.ofSeconds(30))))
                .thenReturn(RankingCache.GenerationClaim.owner(1));

        var first = limiter.acquire("user-idempotent", "request-key", "body-a");
        var changed = limiter.acquire("user-idempotent", "request-key", "body-b");

        assertNotEquals(first.reservationId(), changed.reservationId());
    }

    @Test
    void 실행소유자만성공확정과환불을할수있다() {
        RankingCache cache = mock(RankingCache.class);
        RunningGenerationRateLimiter limiter = limiter(cache, "2026-09-01T00:00:00Z");
        var owner = RunningGenerationRateLimiter.Reservation.owned(
                3, 2, 1_800_000_000L, "quota", "ledger", "reservation", "owner");
        var replay = RunningGenerationRateLimiter.Reservation.replayed(
                3, 2, 1_800_000_000L, "quota", "ledger", "reservation", "{}");
        when(cache.completeGeneration(eq("ledger"), eq("owner"), anyString(),
                eq(Duration.ofMinutes(5)))).thenReturn(true);
        when(cache.failGeneration("quota", "ledger", "reservation", "owner")).thenReturn(true);

        assertTrue(limiter.complete(owner, new TestResponse("ok")));
        assertTrue(limiter.refund(owner));
        assertFalse(limiter.complete(replay, new TestResponse("ignored")));
        assertTrue(limiter.refund(replay));

        verify(cache).completeGeneration(eq("ledger"), eq("owner"), anyString(),
                eq(Duration.ofMinutes(5)));
        verify(cache).failGeneration("quota", "ledger", "reservation", "owner");
    }

    @Test
    void 실패환불후같은멱등요청을재실행해도_분당횟수는새로차감한다() {
        RankingCache cache = mock(RankingCache.class);
        RunningGenerationRateLimiter limiter = limiter(cache, "2026-09-01T00:00:00Z");
        when(cache.reserve(anyString(), anyString(), eq(6), eq(Duration.ofMinutes(2))))
                .thenReturn(RankingCache.CounterReservation.allowed(1),
                        RankingCache.CounterReservation.allowed(2));
        when(cache.claimGeneration(anyString(), anyString(), anyString(), anyString(), eq(3),
                any(Duration.class), eq(Duration.ofSeconds(30))))
                .thenReturn(RankingCache.GenerationClaim.owner(1),
                        RankingCache.GenerationClaim.owner(1));
        when(cache.failGeneration(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        var failedOwner = limiter.acquire("retry-user", "request-key", "same-body");
        assertTrue(limiter.refund(failedOwner));
        var retriedOwner = limiter.acquire("retry-user", "request-key", "same-body");

        assertTrue(retriedOwner.ownsExecution());
        ArgumentCaptor<String> minuteAttemptIds = ArgumentCaptor.forClass(String.class);
        verify(cache, times(2)).reserve(anyString(), minuteAttemptIds.capture(),
                eq(6), eq(Duration.ofMinutes(2)));
        assertNotEquals(minuteAttemptIds.getAllValues().get(0),
                minuteAttemptIds.getAllValues().get(1));
    }

    @Test
    void 즉시중복후8초재조회면_30초임대인계까지분당6회안에들어온다() {
        RankingCache cache = mock(RankingCache.class);
        RunningGenerationRateLimiter limiter = limiter(cache, "2026-09-01T00:00:00Z");
        when(cache.reserve(anyString(), anyString(), eq(6), eq(Duration.ofMinutes(2))))
                .thenReturn(RankingCache.CounterReservation.allowed(1),
                        RankingCache.CounterReservation.allowed(2),
                        RankingCache.CounterReservation.allowed(3),
                        RankingCache.CounterReservation.allowed(4),
                        RankingCache.CounterReservation.allowed(5),
                        RankingCache.CounterReservation.allowed(6));
        when(cache.claimGeneration(anyString(), anyString(), anyString(), anyString(), eq(3),
                any(Duration.class), eq(Duration.ofSeconds(30))))
                .thenReturn(RankingCache.GenerationClaim.owner(1),
                        RankingCache.GenerationClaim.inProgress(),
                        RankingCache.GenerationClaim.inProgress(),
                        RankingCache.GenerationClaim.inProgress(),
                        RankingCache.GenerationClaim.inProgress(),
                        RankingCache.GenerationClaim.owner(1));

        assertTrue(limiter.acquire("timeline-user", "request-key", "same-body").ownsExecution());
        assertEquals(RunningGenerationRateLimiter.Scope.IDEMPOTENCY,
                limiter.acquire("timeline-user", "request-key", "same-body").scope());
        for (int seconds : new int[]{8, 16, 24}) {
            limiter.clock = Clock.fixed(
                    Instant.parse("2026-09-01T00:00:00Z").plusSeconds(seconds), ZoneOffset.UTC);
            assertEquals(RunningGenerationRateLimiter.Scope.IDEMPOTENCY,
                    limiter.acquire("timeline-user", "request-key", "same-body").scope());
        }
        limiter.clock = Clock.fixed(
                Instant.parse("2026-09-01T00:00:32Z"), ZoneOffset.UTC);
        assertTrue(limiter.acquire("timeline-user", "request-key", "same-body").ownsExecution());

        verify(cache, times(6)).reserve(anyString(), anyString(),
                eq(6), eq(Duration.ofMinutes(2)));
    }

    @Test
    void 분당한도를먼저적용하고상태원장은만들지않는다() {
        RankingCache cache = mock(RankingCache.class);
        RunningGenerationRateLimiter limiter = limiter(cache, "2026-09-01T00:00:30Z");
        when(cache.reserve(argThat(key -> key != null && key.startsWith("rate#")),
                anyString(), eq(6), eq(Duration.ofMinutes(2))))
                .thenReturn(RankingCache.CounterReservation.limited());

        var reservation = limiter.acquire("user-2", "request-key", "request-body");

        assertEquals(RunningGenerationRateLimiter.Scope.MINUTE, reservation.scope());
        assertEquals(30, reservation.retryAfterSeconds());
        verify(cache, never()).claimGeneration(anyString(), anyString(), anyString(),
                anyString(), anyInt(), any(Duration.class), any(Duration.class));
    }

    @Test
    void 저장소장애는한도초과와구분한다() {
        RankingCache cache = mock(RankingCache.class);
        RunningGenerationRateLimiter limiter = limiter(cache, "2026-09-01T00:00:00Z");
        when(cache.reserve(anyString(), anyString(), eq(6), eq(Duration.ofMinutes(2))))
                .thenReturn(RankingCache.CounterReservation.allowed(1));
        when(cache.claimGeneration(anyString(), anyString(), anyString(), anyString(), eq(3),
                any(Duration.class), eq(Duration.ofSeconds(30))))
                .thenReturn(RankingCache.GenerationClaim.unavailable());

        var reservation = limiter.acquire("daily-store-user", "request-key", "request-body");

        assertEquals(RunningGenerationRateLimiter.Scope.BACKEND, reservation.scope());
    }

    @Test
    void 사용자식별자가없으면차단한다() {
        RunningGenerationRateLimiter limiter = limiter(
                mock(RankingCache.class), "2026-09-01T00:00:00Z");
        assertFalse(limiter.acquire("", "request-key", "request-body").allowed());
    }

    @Test
    void KST자정을넘겨도_같은멱등요청은원래쿼터와같은원장을쓴다() {
        RankingCache cache = mock(RankingCache.class);
        RunningGenerationRateLimiter limiter = limiter(cache, "2026-09-01T14:59:59Z");
        when(cache.reserve(anyString(), anyString(), anyInt(), any(Duration.class)))
                .thenReturn(RankingCache.CounterReservation.allowed(1));
        when(cache.claimGeneration(anyString(), anyString(), anyString(), anyString(), eq(3),
                any(Duration.class), eq(Duration.ofSeconds(30))))
                .thenReturn(
                        RankingCache.GenerationClaim.owner(
                                1, "quota#running-generation#midnight-user#20260901"),
                        RankingCache.GenerationClaim.replay(
                                0, "{\"ok\":true}",
                                "quota#running-generation#midnight-user#20260901"));

        var before = limiter.acquire("midnight-user", "request-key", "same-body");
        limiter.clock = Clock.fixed(Instant.parse("2026-09-01T15:00:00Z"), ZoneOffset.UTC);
        var at = limiter.acquire("midnight-user", "request-key", "same-body");

        assertEquals(Instant.parse("2026-09-01T15:00:00Z").getEpochSecond(),
                before.resetEpochSeconds());
        assertEquals(Instant.parse("2026-09-02T15:00:00Z").getEpochSecond(),
                at.resetEpochSeconds());
        assertTrue(at.replayed());
        assertEquals(3, at.remaining());
        assertEquals("quota#running-generation#midnight-user#20260901", at.dailyKey());

        ArgumentCaptor<String> quotaKeys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ledgerKeys = ArgumentCaptor.forClass(String.class);
        verify(cache, times(2)).claimGeneration(quotaKeys.capture(), ledgerKeys.capture(),
                anyString(), anyString(), eq(3),
                any(Duration.class), eq(Duration.ofSeconds(30)));
        assertNotEquals(quotaKeys.getAllValues().get(0), quotaKeys.getAllValues().get(1));
        assertEquals(ledgerKeys.getAllValues().get(0), ledgerKeys.getAllValues().get(1));

        ArgumentCaptor<String> minuteReservationIds = ArgumentCaptor.forClass(String.class);
        verify(cache, times(2)).reserve(anyString(), minuteReservationIds.capture(),
                eq(6), eq(Duration.ofMinutes(2)));
        assertNotEquals(minuteReservationIds.getAllValues().get(0),
                minuteReservationIds.getAllValues().get(1));
    }

    private static RunningGenerationRateLimiter limiter(RankingCache cache, String instant) {
        RunningGenerationRateLimiter limiter = new RunningGenerationRateLimiter();
        limiter.cache = cache;
        limiter.mapper = new ObjectMapper();
        limiter.limitPerMinute = 6;
        limiter.dailyLimit = 3;
        limiter.idempotencyLeaseSeconds = 30;
        limiter.idempotencyResponseTtlMinutes = 5;
        limiter.clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return limiter;
    }

    private record TestResponse(String value) {
    }
}
