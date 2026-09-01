package com.tourapi.services;

import com.tourapi.lib.RankingCache;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.CandidatesResponse;
import com.tourapi.model.CourseSummaryResponse;
import com.tourapi.model.RouteOption;
import com.tourapi.model.RouteOptionsResponse;
import com.tourapi.model.WaypointDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 러닝 생성 API 파사드. 후보 탐색, 경로 옵션, 총정리 책임은 각각의 서비스로 분리한다. */
@ApplicationScoped
public class RunningService {

    private static final Duration ROUTE_CACHE_TTL = Duration.ofMinutes(5);
    private static final long CACHE_IO_GUARD_NANOS = TimeUnit.MILLISECONDS.toNanos(2_200);
    private static final long MIN_GENERATION_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long DEFAULT_DEADLINE_MS = 22_000;

    @Inject
    RunningCandidateService candidateService;

    @Inject
    RunningRouteOptionService routeOptionService;

    @Inject
    CourseSummaryService summaryService;

    @Inject
    RankingCache cache;

    public CandidatesResponse candidates(double lat, double lng, double distanceKm, String shape, int count) {
        return candidateService.candidates(lat, lng, distanceKm, shape, count);
    }

    public RouteOptionsResponse routeOptions(double[] start,
                                             List<WaypointDto> selected,
                                             List<WaypointDto> candidates,
                                             String shape,
                                             double targetKm) {
        return routeOptions(start, selected, candidates, shape, targetKm,
                deadlineAfter(DEFAULT_DEADLINE_MS));
    }

    public RouteOptionsResponse routeOptions(double[] start,
                                             List<WaypointDto> selected,
                                             List<WaypointDto> candidates,
                                             String shape,
                                             double targetKm,
                                             long deadlineNanos) {
        String key = routeOptionsCacheKey(start, selected, candidates, shape, targetKm);
        RouteOptionsResponse cached = cacheGet(key, RouteOptionsResponse.class, deadlineNanos);
        if (cached != null) {
            return cached;
        }
        requireGenerationBudget(deadlineNanos);
        RouteOptionsResponse response = routeOptionService.options(
                start, selected, candidates, shape, targetKm, deadlineNanos);
        cachePut(key, response, deadlineNanos);
        return response;
    }

    public CourseSummaryResponse summary(RouteOption option, int nearbyRadiusM) {
        return summaryService.summary(option, nearbyRadiusM);
    }

    static String routeOptionsCacheKey(double[] start,
                                       List<WaypointDto> selected,
                                       List<WaypointDto> candidates,
                                       String shape,
                                       double targetKm) {
        StringBuilder raw = new StringBuilder("route-options-v3|")
                .append(start[0]).append('|').append(start[1]).append('|')
                .append(shape).append('|').append(targetKm).append('|');
        appendWaypoints(raw, selected);
        raw.append('|');
        appendWaypoints(raw, candidates);
        return "running#" + sha256(raw.toString());
    }

    private <T> T cacheGet(String key, Class<T> type, long deadlineNanos) {
        if (remainingNanos(deadlineNanos) <= CACHE_IO_GUARD_NANOS) {
            return null;
        }
        return cache.get(key, type);
    }

    private void cachePut(String key, Object value, long deadlineNanos) {
        if (remainingNanos(deadlineNanos) <= CACHE_IO_GUARD_NANOS) {
            return;
        }
        cache.put(key, value, ROUTE_CACHE_TTL);
    }

    private static void appendWaypoints(StringBuilder out, List<WaypointDto> waypoints) {
        if (waypoints == null) {
            return;
        }
        for (WaypointDto waypoint : waypoints) {
            String name = waypoint.name() == null ? "" : waypoint.name();
            out.append(name.length()).append(':').append(name).append('@')
                    .append(waypoint.lat()).append(',').append(waypoint.lng()).append(';');
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    private static long deadlineAfter(long milliseconds) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
    }

    private static long remainingNanos(long deadlineNanos) {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }

    private static void requireGenerationBudget(long deadlineNanos) {
        if (remainingNanos(deadlineNanos) < MIN_GENERATION_BUDGET_NANOS) {
            throw new UpstreamException("코스 생성 가용 시간이 부족함 — 다시 시도");
        }
    }
}
