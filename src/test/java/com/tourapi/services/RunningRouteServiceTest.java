package com.tourapi.services;

import com.tourapi.lib.ElevationClient;
import com.tourapi.lib.TmapClient;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.RoutesResponse;
import com.tourapi.model.WaypointDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RunningRouteServiceTest {

    @Test
    void 기존Routes는선택순역순계약을유지한다() {
        TmapClient tmap = mock(TmapClient.class);
        ElevationClient elevation = mock(ElevationClient.class);
        RunningRouteService service = new RunningRouteService();
        service.tmap = tmap;
        service.elevationClient = elevation;
        when(tmap.pedestrian(any(), anyList(), any(), any())).thenReturn(route());
        when(elevation.elevations(anyList(), any())).thenReturn(new double[]{0, 0});

        RoutesResponse response = service.routes(new double[]{37.0, 127.0}, List.of(
                new WaypointDto("A", 37.001, 127.0),
                new WaypointDto("B", 37.002, 127.0)), "loop", 3.0);

        assertEquals("loop", response.shape());
        assertEquals(2, response.count());
        assertTrue(response.courses().stream().anyMatch(course -> "선택순".equals(course.label())));
        assertTrue(response.courses().stream().anyMatch(course -> "역순".equals(course.label())));
        service.closePool();
    }

    @Test
    void 일부Elevation실패시성공코스만유지한다() {
        TmapClient tmap = mock(TmapClient.class);
        ElevationClient elevation = mock(ElevationClient.class);
        RunningRouteService service = new RunningRouteService();
        service.tmap = tmap;
        service.elevationClient = elevation;
        when(tmap.pedestrian(any(), anyList(), any(), any())).thenReturn(route());
        when(elevation.elevations(anyList(), any()))
                .thenThrow(new UpstreamException("첫 고도 실패"))
                .thenReturn(new double[]{0, 0});

        RoutesResponse response = service.routes(new double[]{37.0, 127.0}, List.of(
                new WaypointDto("A", 37.001, 127.0),
                new WaypointDto("B", 37.002, 127.0)), "loop", 3.0);

        assertEquals(1, response.count());
        service.closePool();
    }

    @Test
    void 기존Routes도전달받은전체마감시간을지킨다() {
        TmapClient tmap = mock(TmapClient.class);
        ElevationClient elevation = mock(ElevationClient.class);
        RunningRouteService service = new RunningRouteService();
        service.tmap = tmap;
        service.elevationClient = elevation;
        CountDownLatch releaseTmap = new CountDownLatch(1);
        CountDownLatch tmapReturned = new CountDownLatch(1);
        CountDownLatch elevationCalled = new CountDownLatch(1);
        when(tmap.pedestrian(any(), anyList(), any(), any())).thenAnswer(invocation -> {
            try {
                if (!releaseTmap.await(1, TimeUnit.SECONDS)) {
                    throw new UpstreamException("테스트 TMAP 해제 timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException("취소됨", e);
            }
            tmapReturned.countDown();
            return route();
        });
        when(elevation.elevations(anyList(), any())).thenAnswer(invocation -> {
            elevationCalled.countDown();
            return new double[]{0, 0};
        });

        long started = System.nanoTime();
        assertThrows(UpstreamException.class, () -> service.routes(
                new double[]{37.0, 127.0}, List.of(
                        new WaypointDto("A", 37.001, 127.0),
                        new WaypointDto("B", 37.002, 127.0)), "loop", 3.0,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMs < 400);
        releaseTmap.countDown();
        try {
            assertTrue(tmapReturned.await(1, TimeUnit.SECONDS));
            assertFalse(elevationCalled.await(200, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("latch 대기 중 중단", e);
        }
        verifyNoInteractions(elevation);
        service.closePool();
    }

    private static TmapClient.TmapRoute route() {
        return new TmapClient.TmapRoute(List.of(
                new double[]{37.0, 127.0}, new double[]{37.001, 127.0}), 3000, 1200);
    }
}
