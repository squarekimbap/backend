package com.tourapi.services;

import com.tourapi.lib.ElevationClient;
import com.tourapi.lib.TmapClient;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.Course;
import com.tourapi.model.WaypointDto;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RunningRouteServiceTest {

    @Test
    void 루프는모든경유지를거쳐출발지로돌아온다() {
        TmapClient tmap = mock(TmapClient.class);
        RunningRouteService service = service(tmap, mock(ElevationClient.class));
        AtomicReference<double[]> actualStart = new AtomicReference<>();
        AtomicReference<List<double[]>> actualVia = new AtomicReference<>();
        AtomicReference<double[]> actualEnd = new AtomicReference<>();
        TmapClient.TmapRoute route = route();
        when(tmap.pedestrian(any(), anyList(), any(), any())).thenAnswer(invocation -> {
            actualStart.set(invocation.getArgument(0));
            actualVia.set(invocation.getArgument(1));
            actualEnd.set(invocation.getArgument(2));
            return route;
        });

        double[] start = {37.0, 127.0};
        List<WaypointDto> order = List.of(
                new WaypointDto("A", 37.001, 127.001),
                new WaypointDto("B", 37.002, 127.002));
        RunningRouteService.RoutePlan plan = service.plan(
                "루프", start, order, true, futureDeadline());

        assertSame(route, plan.route());
        assertSame(start, actualStart.get());
        assertArrayEquals(new double[]{37.001, 127.001}, actualVia.get().get(0));
        assertArrayEquals(new double[]{37.002, 127.002}, actualVia.get().get(1));
        assertSame(start, actualEnd.get());
    }

    @Test
    void 편도는마지막경유지를도착지로분리한다() {
        TmapClient tmap = mock(TmapClient.class);
        RunningRouteService service = service(tmap, mock(ElevationClient.class));
        AtomicReference<List<double[]>> actualVia = new AtomicReference<>();
        AtomicReference<double[]> actualEnd = new AtomicReference<>();
        when(tmap.pedestrian(any(), anyList(), any(), any())).thenAnswer(invocation -> {
            actualVia.set(invocation.getArgument(1));
            actualEnd.set(invocation.getArgument(2));
            return route();
        });

        service.plan("편도", new double[]{37.0, 127.0}, List.of(
                new WaypointDto("A", 37.001, 127.001),
                new WaypointDto("B", 37.002, 127.002)), false, futureDeadline());

        assertEquals(1, actualVia.get().size());
        assertArrayEquals(new double[]{37.001, 127.001}, actualVia.get().get(0));
        assertArrayEquals(new double[]{37.002, 127.002}, actualEnd.get());
    }

    @Test
    void 경로를고도와난이도가포함된코스로변환한다() {
        ElevationClient elevation = mock(ElevationClient.class);
        RunningRouteService service = service(mock(TmapClient.class), elevation);
        when(elevation.elevations(anyList(), any(Duration.class)))
                .thenReturn(new double[]{0, 30, 20, 80});
        List<WaypointDto> order = List.of(
                new WaypointDto("A", 37.001, 127.001),
                new WaypointDto("", 37.002, 127.002));
        RunningRouteService.RoutePlan plan = new RunningRouteService.RoutePlan("근접순", order, route());

        Course course = service.toCourse(plan, futureDeadline());

        assertEquals("근접순", course.label());
        assertEquals(List.of("A", "경유지2"), course.waypointOrder());
        assertEquals(3000, course.distanceM());
        assertEquals(1200, course.walkDurationS());
        assertEquals(90.0, course.ascentM());
        assertEquals(30.0, course.ascentPerKm());
        assertEquals("상", course.difficulty());
        assertEquals(route().path().size(), course.path().size());
    }

    @Test
    void 마감시간이지나면외부호출을시작하지않는다() {
        TmapClient tmap = mock(TmapClient.class);
        ElevationClient elevation = mock(ElevationClient.class);
        RunningRouteService service = service(tmap, elevation);
        long expired = System.nanoTime() - 1;

        assertThrows(UpstreamException.class, () -> service.plan(
                "루프", new double[]{37.0, 127.0},
                List.of(new WaypointDto("A", 37.001, 127.001)), true, expired));
        assertThrows(UpstreamException.class, () -> service.toCourse(
                new RunningRouteService.RoutePlan("루프", List.of(
                        new WaypointDto("A", 37.001, 127.001)), route()), expired));

        verifyNoInteractions(tmap, elevation);
    }

    private static RunningRouteService service(TmapClient tmap, ElevationClient elevation) {
        RunningRouteService service = new RunningRouteService();
        service.tmap = tmap;
        service.elevationClient = elevation;
        return service;
    }

    private static long futureDeadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    }

    private static TmapClient.TmapRoute route() {
        return new TmapClient.TmapRoute(List.of(
                new double[]{37.0, 127.0},
                new double[]{37.001, 127.001},
                new double[]{37.002, 127.002},
                new double[]{37.003, 127.003}), 3000, 1200);
    }
}
