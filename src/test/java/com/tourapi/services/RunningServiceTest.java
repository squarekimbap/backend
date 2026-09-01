package com.tourapi.services;

import com.tourapi.lib.RankingCache;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.RouteOptionsResponse;
import com.tourapi.model.WaypointDto;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunningServiceTest {

    @Test
    void 같은코스요청은5분캐시를사용한다() {
        RankingCache cache = mock(RankingCache.class);
        RunningRouteOptionService generator = mock(RunningRouteOptionService.class);
        RunningService service = new RunningService();
        service.cache = cache;
        service.routeOptionService = generator;
        RouteOptionsResponse cached = new RouteOptionsResponse("loop", 3, 0, List.of());
        when(cache.get(anyString(), any())).thenReturn(cached);

        RouteOptionsResponse response = service.routeOptions(
                new double[]{37.0, 127.0}, List.of(),
                List.of(new WaypointDto("A", 37.01, 127.0)), "loop", 3);

        assertSame(cached, response);
        verifyNoInteractions(generator);
    }

    @Test
    void 마감직전이면캐시조회와저장을생략한다() {
        RankingCache cache = mock(RankingCache.class);
        RunningRouteOptionService generator = mock(RunningRouteOptionService.class);
        RunningService service = new RunningService();
        service.cache = cache;
        service.routeOptionService = generator;

        assertThrows(UpstreamException.class, () -> service.routeOptions(
                new double[]{37.0, 127.0}, List.of(),
                List.of(new WaypointDto("A", 37.01, 127.0)), "loop", 3,
                System.nanoTime() + 1_000_000));

        verifyNoInteractions(cache);
        verifyNoInteractions(generator);
    }

    @Test
    void 새코스는정확히5분Ttl로저장한다() {
        RankingCache cache = mock(RankingCache.class);
        RunningRouteOptionService generator = mock(RunningRouteOptionService.class);
        RunningService service = new RunningService();
        service.cache = cache;
        service.routeOptionService = generator;
        RouteOptionsResponse created = new RouteOptionsResponse("loop", 3, 0, List.of());
        when(generator.options(any(), anyList(), anyList(), anyString(), anyDouble(), anyLong()))
                .thenReturn(created);

        RouteOptionsResponse response = service.routeOptions(
                new double[]{37.0, 127.0}, List.of(),
                List.of(new WaypointDto("A", 37.01, 127.0)), "loop", 3,
                System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10));

        assertSame(created, response);
        verify(cache).put(anyString(), eq(created), eq(Duration.ofMinutes(5)));
    }
}
