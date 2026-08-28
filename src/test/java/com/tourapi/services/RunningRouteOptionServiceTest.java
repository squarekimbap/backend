package com.tourapi.services;

import com.tourapi.lib.TmapClient;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.Course;
import com.tourapi.model.RouteOptionsResponse;
import com.tourapi.model.WaypointDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunningRouteOptionServiceTest {

    @Test
    void 관광지우선과거리우선옵션을분리한다() {
        RunningRouteService routes = mock(RunningRouteService.class);
        StoryService stories = mock(StoryService.class);
        RunningRouteOptionService service = new RunningRouteOptionService();
        service.routeService = routes;
        service.storyService = stories;

        double[] start = {37.0, 127.0};
        WaypointDto a = new WaypointDto("A", 37.001, 127.0);
        WaypointDto b = new WaypointDto("B", 37.002, 127.0);
        WaypointDto c = new WaypointDto("C", 37.0135, 127.0);

        when(routes.plan(anyString(), any(), anyList(), anyBoolean(), anyLong())).thenAnswer(invocation -> {
            List<WaypointDto> order = invocation.getArgument(2);
            int distance = order.stream().anyMatch(point -> "C".equals(point.name())) ? 3000 : 3400;
            return plan(invocation.getArgument(0), order, distance);
        });
        when(routes.toCourse(any(), anyLong())).thenAnswer(invocation -> {
            RunningRouteService.RoutePlan plan = invocation.getArgument(0);
            List<String> names = plan.order().stream().map(WaypointDto::name).toList();
            return new Course(plan.label(), names, plan.route().distanceM(), 1000,
                    0, 0, "하", plan.route().path());
        });
        when(routes.segments(any(), any(), anyBoolean())).thenReturn(List.of());
        when(stories.storiesForCourses(anyList(), anyLong())).thenAnswer(invocation -> {
            List<Course> courses = invocation.getArgument(0);
            List<List<com.tourapi.model.StorySpot>> out = new ArrayList<>();
            courses.forEach(course -> out.add(List.of()));
            return out;
        });

        RouteOptionsResponse response = service.options(
                start, List.of(a, b), List.of(a, b, c), "loop", 3.0);

        assertEquals(2, response.count());
        assertEquals("waypoint_priority", response.options().get(0).strategy());
        assertEquals("distance_priority", response.options().get(1).strategy());
        assertTrue(response.options().get(1).withinTolerance());
        verify(routes, atMost(RunningRouteOptionService.MAX_TMAP_CALLS))
                .plan(anyString(), any(), anyList(), anyBoolean(), anyLong());
        service.closePool();
    }

    @Test
    void 거리탐색은가까운순접두사외의조합도찾는다() {
        double[] start = {37.0, 127.0};
        WaypointDto near = new WaypointDto("가까움", 37.001, 127.0);
        WaypointDto far = new WaypointDto("목표반경", 37.0135, 127.0);

        List<List<WaypointDto>> orders = RunningRouteOptionService.beamSearchOrders(
                start, List.of(near, far), true, 3.0);

        assertFalse(orders.isEmpty());
        assertTrue(orders.get(0).contains(far));
    }

    @Test
    void 전체마감시간이지나면느린경로를취소한다() {
        RunningRouteService routes = mock(RunningRouteService.class);
        StoryService stories = mock(StoryService.class);
        RunningRouteOptionService service = new RunningRouteOptionService();
        service.routeService = routes;
        service.storyService = stories;
        service.deadlineMs = 50;
        when(routes.plan(anyString(), any(), anyList(), anyBoolean(), anyLong())).thenAnswer(invocation -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException("취소됨", e);
            }
            return plan("느림", invocation.getArgument(2), 3000);
        });

        WaypointDto a = new WaypointDto("A", 37.001, 127.0);
        long started = System.nanoTime();
        assertThrows(UpstreamException.class,
                () -> service.options(new double[]{37.0, 127.0}, List.of(a), List.of(a), "loop", 3));
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMs < 400);
        service.closePool();
    }

    @Test
    void 일부Tmap후보가실패해도성공한코스를반환한다() {
        RunningRouteService routes = mock(RunningRouteService.class);
        StoryService stories = mock(StoryService.class);
        RunningRouteOptionService service = new RunningRouteOptionService();
        service.routeService = routes;
        service.storyService = stories;
        WaypointDto a = new WaypointDto("A", 37.001, 127.0);
        WaypointDto b = new WaypointDto("B", 37.002, 127.0);
        when(routes.plan(anyString(), any(), anyList(), anyBoolean(), anyLong())).thenAnswer(invocation -> {
            List<WaypointDto> order = invocation.getArgument(2);
            if (order.size() == 2 && "B".equals(order.get(0).name())) {
                throw new UpstreamException("일부 TMAP 실패");
            }
            return plan(invocation.getArgument(0), order, 3000);
        });
        when(routes.toCourse(any(), anyLong())).thenAnswer(invocation -> {
            RunningRouteService.RoutePlan plan = invocation.getArgument(0);
            return new Course(plan.label(), plan.order().stream().map(WaypointDto::name).toList(),
                    plan.route().distanceM(), 1000, 0, 0, "하", plan.route().path());
        });
        when(routes.segments(any(), any(), anyBoolean())).thenReturn(List.of());
        when(stories.storiesForCourses(anyList(), anyLong())).thenAnswer(invocation -> {
            List<Course> courses = invocation.getArgument(0);
            List<List<com.tourapi.model.StorySpot>> out = new ArrayList<>();
            courses.forEach(course -> out.add(List.of()));
            return out;
        });

        RouteOptionsResponse response = service.options(
                new double[]{37.0, 127.0}, List.of(a, b), List.of(a, b), "loop", 3);

        assertTrue(response.count() >= 1);
        service.closePool();
    }

    @Test
    void 구간거리합은Tmap총거리와같다() {
        RunningRouteService service = new RunningRouteService();
        WaypointDto a = new WaypointDto("A", 37.001, 127.0);
        WaypointDto b = new WaypointDto("B", 37.002, 127.0);
        List<double[]> path = List.of(
                new double[]{37.0, 127.0},
                new double[]{37.001, 127.0},
                new double[]{37.002, 127.0});
        RunningRouteService.RoutePlan plan = new RunningRouteService.RoutePlan(
                "선택순", List.of(a, b), new TmapClient.TmapRoute(path, 250, 100));

        var segments = service.segments(plan, new double[]{37.0, 127.0}, false);

        assertEquals(2, segments.size());
        assertEquals(250, segments.stream().mapToInt(segment -> segment.distanceM()).sum());
    }

    private static RunningRouteService.RoutePlan plan(String label, List<WaypointDto> order, int distance) {
        List<double[]> path = List.of(
                new double[]{37.0, 127.0},
                new double[]{37.001, 127.0});
        return new RunningRouteService.RoutePlan(
                label, order, new TmapClient.TmapRoute(path, distance, 1000));
    }
}
