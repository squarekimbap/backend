package com.tourapi.services;

import com.tourapi.lib.RankingCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;

/** 로그인 사용자별 실시간 러닝 코스 생성 호출량을 통합 제한한다. */
@ApplicationScoped
public class RunningGenerationRateLimiter {

    @Inject
    RankingCache cache;

    @ConfigProperty(name = "running.generation.rate-limit-per-minute", defaultValue = "6")
    int limitPerMinute;

    public boolean allow(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        long minute = Instant.now().getEpochSecond() / 60;
        return cache.allow("rate#running-generation#" + userId + "#" + minute,
                limitPerMinute, Duration.ofMinutes(2));
    }
}
