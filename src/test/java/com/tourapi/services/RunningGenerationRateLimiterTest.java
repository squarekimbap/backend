package com.tourapi.services;

import com.tourapi.lib.RankingCache;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunningGenerationRateLimiterTest {

    @Test
    void 사용자별분당상한을공통키로적용한다() {
        RankingCache cache = mock(RankingCache.class);
        RunningGenerationRateLimiter limiter = new RunningGenerationRateLimiter();
        limiter.cache = cache;
        limiter.limitPerMinute = 6;
        when(cache.allow(anyString(), eq(6), eq(Duration.ofMinutes(2))))
                .thenReturn(true, false);

        assertTrue(limiter.allow("user-1"));
        assertFalse(limiter.allow("user-1"));
        assertFalse(limiter.allow(""));
    }
}
