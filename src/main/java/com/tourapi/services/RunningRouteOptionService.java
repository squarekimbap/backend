package com.tourapi.services;

import com.tourapi.lib.Geo;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.Course;
import com.tourapi.model.RouteOption;
import com.tourapi.model.RouteOptionsResponse;
import com.tourapi.model.StorySpot;
import com.tourapi.model.WaypointDto;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 화면 3의 '고른 곳 우선'과 '거리 우선' 코스 옵션을 비용·시간 상한 안에서 조립한다. */
@ApplicationScoped
public class RunningRouteOptionService {

    private static final Logger LOG = Logger.getLogger(RunningRouteOptionService.class);
    static final int MAX_TMAP_CALLS = 5;
    private static final int SEARCH_POOL_LIMIT = 12;
    private static final int BEAM_WIDTH = 32;
    private static final int MAX_WAYPOINTS = 5;

    @Inject
    RunningRouteService routeService;

    @Inject
    StoryService storyService;

    @ConfigProperty(name = "running.generation.deadline-ms", defaultValue = "22000")
    long deadlineMs = 22_000;

    private final ExecutorService upstreamPool = Executors.newFixedThreadPool(
            MAX_TMAP_CALLS, daemonThreads("route-option-upstream-"));

    public RouteOptionsResponse options(double[] start,
                                        List<WaypointDto> selected,
                                        List<WaypointDto> candidates,
                                        String shape,
                                        double targetKm) {
        return options(start, selected, candidates, shape, targetKm,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs));
    }

    public RouteOptionsResponse options(double[] start,
                                        List<WaypointDto> selected,
                                        List<WaypointDto> candidates,
                                        String shape,
                                        double targetKm,
                                        long deadline) {
        boolean loop = !"oneway".equals(shape);
        List<WaypointDto> selectedSafe = dedupe(selected);
        List<WaypointDto> pool = dedupe(join(selectedSafe, candidates));
        List<WaypointDto> required = selectedSafe.isEmpty()
                ? autoSelect(start, pool, targetKm) : selectedSafe;
        if (required.isEmpty()) {
            throw new UpstreamException("경로를 만들 관광지 후보가 없음");
        }

        List<RouteRequest> requiredRequests = standardRequests(start, required);
        List<RouteRequest> requests = boundedRequests(
                start, pool, requiredRequests, loop, targetKm);
        List<RunningRouteService.RoutePlan> plans = evaluateRoutes(
                start, requests, loop, deadline);
        if (plans.isEmpty()) {
            throw new UpstreamException("제한 시간 안에 계산된 경로 후보가 없음");
        }

        Set<String> requiredSignatures = new LinkedHashSet<>();
        requiredRequests.forEach(request -> requiredSignatures.add(
                RunningRouteService.signature(request.order())));
        RunningRouteService.RoutePlan waypointPlan = closest(
                plans.stream().filter(plan -> requiredSignatures.contains(
                        RunningRouteService.signature(plan.order()))).toList(), targetKm);
        RunningRouteService.RoutePlan distancePlan = closest(
                plans.stream().filter(plan -> !requiredSignatures.contains(
                        RunningRouteService.signature(plan.order()))).toList(), targetKm);
        if (distancePlan == null) {
            distancePlan = closestDistinct(plans, waypointPlan, targetKm);
        }

        List<RunningRouteService.RoutePlan> chosenPlans = new ArrayList<>();
        if (waypointPlan != null) {
            chosenPlans.add(waypointPlan);
        }
        if (distancePlan != null) {
            chosenPlans.add(distancePlan);
        }
        if (chosenPlans.isEmpty()) {
            chosenPlans.add(closest(plans, targetKm));
        }

        List<EnrichedPlan> enriched = enrichCourses(chosenPlans, deadline);
        if (enriched.isEmpty()) {
            throw new UpstreamException("제한 시간 안에 코스 고도 계산을 완료하지 못함");
        }
        List<Course> courses = enriched.stream().map(EnrichedPlan::course).toList();
        List<List<StorySpot>> stories = storiesWithinDeadline(courses, deadline);

        List<RouteOption> options = new ArrayList<>();
        for (int i = 0; i < enriched.size(); i++) {
            RunningRouteService.RoutePlan plan = enriched.get(i).plan();
            Course course = enriched.get(i).course();
            boolean waypointOption = waypointPlan != null
                    && RunningRouteService.signature(plan.order()).equals(
                    RunningRouteService.signature(waypointPlan.order()));
            String strategy = waypointOption ? "waypoint_priority" : "distance_priority";
            String title;
            if (waypointOption) {
                title = selectedSafe.isEmpty() ? "추천 장소를 지나요" : "고른 곳을 지나요";
            } else {
                int error = distanceError(course.distanceM(), targetKm);
                title = withinTolerance(error, targetKm) ? "거리가 딱 맞아요" : "거리에 가장 가까워요";
            }
            options.add(toOption(strategy, title, plan, course, selectedSafe,
                    start, loop, targetKm, stories.get(i)));
        }
        return new RouteOptionsResponse(loop ? "loop" : "oneway", targetKm,
                options.size(), List.copyOf(options));
    }

    private List<RouteRequest> boundedRequests(double[] start,
                                               List<WaypointDto> pool,
                                               List<RouteRequest> required,
                                               boolean loop,
                                               double targetKm) {
        LinkedHashMap<String, RouteRequest> out = new LinkedHashMap<>();
        for (RouteRequest request : required) {
            addRequest(out, request);
        }
        for (List<WaypointDto> order : beamSearchOrders(start, pool, loop, targetKm)) {
            if (out.size() >= MAX_TMAP_CALLS) {
                break;
            }
            addRequest(out, new RouteRequest("거리우선", order));
        }
        List<RouteRequest> values = List.copyOf(out.values());
        return values.subList(0, Math.min(MAX_TMAP_CALLS, values.size()));
    }

    private List<RunningRouteService.RoutePlan> evaluateRoutes(double[] start,
                                                               List<RouteRequest> requests,
                                                               boolean loop,
                                                               long deadline) {
        List<CompletableFuture<RunningRouteService.RoutePlan>> futures = new ArrayList<>();
        for (RouteRequest request : requests) {
            if (!hasRemaining(deadline)) {
                break;
            }
            futures.add(CompletableFuture.supplyAsync(
                    () -> routeService.plan(
                            request.label(), start, request.order(), loop, deadline),
                    upstreamPool));
        }
        awaitUntil(futures, deadline, "TMAP 경로 후보");

        List<RunningRouteService.RoutePlan> plans = new ArrayList<>();
        for (CompletableFuture<RunningRouteService.RoutePlan> future : futures) {
            if (!future.isDone() || future.isCancelled() || future.isCompletedExceptionally()) {
                future.cancel(true);
                continue;
            }
            plans.add(future.join());
        }
        return plans;
    }

    private List<EnrichedPlan> enrichCourses(List<RunningRouteService.RoutePlan> plans,
                                             long deadline) {
        List<CompletableFuture<EnrichedPlan>> futures = new ArrayList<>();
        for (RunningRouteService.RoutePlan plan : plans) {
            if (!hasRemaining(deadline)) {
                break;
            }
            futures.add(CompletableFuture.supplyAsync(
                    () -> new EnrichedPlan(
                            plan, routeService.toCourse(plan, deadline)), upstreamPool));
        }
        awaitUntil(futures, deadline, "Elevation 코스");

        List<EnrichedPlan> out = new ArrayList<>();
        for (CompletableFuture<EnrichedPlan> future : futures) {
            if (!future.isDone() || future.isCancelled() || future.isCompletedExceptionally()) {
                future.cancel(true);
                continue;
            }
            out.add(future.join());
        }
        return out;
    }

    private List<List<StorySpot>> storiesWithinDeadline(List<Course> courses, long deadline) {
        if (remainingNanos(deadline) <= TimeUnit.MILLISECONDS.toNanos(250)) {
            return emptyStories(courses.size());
        }
        CompletableFuture<List<List<StorySpot>>> future = CompletableFuture.supplyAsync(
                () -> storyService.storiesForCourses(courses, deadline), upstreamPool);
        try {
            return future.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.warnf("Odii 이야기 조회를 제한 시간 때문에 생략: %s", rootMessage(e));
        }
        future.cancel(true);
        return emptyStories(courses.size());
    }

    private static <T> void awaitUntil(List<CompletableFuture<T>> futures,
                                       long deadline,
                                       String stage) {
        CompletableFuture<Void> all = CompletableFuture.allOf(
                futures.toArray(CompletableFuture[]::new));
        try {
            all.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOG.warnf("%s 일부 실패: %s", stage, rootMessage(e));
        } catch (TimeoutException e) {
            LOG.warnf("%s 전체 제한 시간 도달", stage);
        }
    }

    private RouteOption toOption(String strategy,
                                 String title,
                                 RunningRouteService.RoutePlan plan,
                                 Course course,
                                 List<WaypointDto> selected,
                                 double[] start,
                                 boolean loop,
                                 double targetKm,
                                 List<StorySpot> stories) {
        List<WaypointDto> excluded = new ArrayList<>();
        Set<String> includedSignatures = new LinkedHashSet<>();
        for (WaypointDto waypoint : plan.order()) {
            includedSignatures.add(pointSignature(waypoint));
        }
        for (WaypointDto waypoint : selected) {
            if (!includedSignatures.contains(pointSignature(waypoint))) {
                excluded.add(waypoint);
            }
        }
        int error = distanceError(course.distanceM(), targetKm);
        return new RouteOption(strategy, title, course, plan.order(), List.copyOf(excluded),
                error, withinTolerance(error, targetKm), stories.size(), stories,
                routeService.segments(plan, start, loop));
    }

    private static List<RouteRequest> standardRequests(double[] start, List<WaypointDto> waypoints) {
        LinkedHashMap<String, RouteRequest> out = new LinkedHashMap<>();
        addRequest(out, new RouteRequest("선택순", waypoints));
        List<WaypointDto> reversed = new ArrayList<>(waypoints);
        java.util.Collections.reverse(reversed);
        addRequest(out, new RouteRequest("역순", reversed));
        addRequest(out, new RouteRequest("근접순", RunningRouteService.nearestOrder(start, waypoints)));
        return List.copyOf(out.values());
    }

    /** Haversine 근사치로 조합/순서를 탐색하고 실제 TMAP에 보낼 상위 후보만 남긴다. */
    static List<List<WaypointDto>> beamSearchOrders(double[] start,
                                                    List<WaypointDto> pool,
                                                    boolean loop,
                                                    double targetKm) {
        if (pool.isEmpty()) {
            return List.of();
        }
        List<WaypointDto> nearby = RunningRouteService.nearestOrder(start, pool);
        if (nearby.size() > SEARCH_POOL_LIMIT) {
            nearby = List.copyOf(nearby.subList(0, SEARCH_POOL_LIMIT));
        }

        List<ScoredOrder> frontier = List.of(new ScoredOrder(List.of(), Double.MAX_VALUE));
        LinkedHashMap<String, ScoredOrder> all = new LinkedHashMap<>();
        int maxDepth = Math.min(MAX_WAYPOINTS, nearby.size());
        for (int depth = 1; depth <= maxDepth; depth++) {
            LinkedHashMap<String, ScoredOrder> expanded = new LinkedHashMap<>();
            for (ScoredOrder state : frontier) {
                Set<String> used = new LinkedHashSet<>();
                state.order().forEach(point -> used.add(pointSignature(point)));
                for (WaypointDto point : nearby) {
                    if (used.contains(pointSignature(point))) {
                        continue;
                    }
                    List<WaypointDto> order = new ArrayList<>(state.order());
                    order.add(point);
                    double score = Math.abs(estimatedDistance(start, order, loop) - targetKm * 1000);
                    ScoredOrder candidate = new ScoredOrder(List.copyOf(order), score);
                    expanded.putIfAbsent(RunningRouteService.signature(order), candidate);
                }
            }
            frontier = expanded.values().stream()
                    .sorted(scoredOrderComparator())
                    .limit(BEAM_WIDTH)
                    .toList();
            frontier.forEach(candidate -> all.putIfAbsent(
                    RunningRouteService.signature(candidate.order()), candidate));
        }
        return all.values().stream()
                .sorted(scoredOrderComparator())
                .map(ScoredOrder::order)
                .toList();
    }

    private static Comparator<ScoredOrder> scoredOrderComparator() {
        return Comparator.comparingDouble(ScoredOrder::errorM)
                .thenComparing(candidate -> RunningRouteService.signature(candidate.order()));
    }

    private static double estimatedDistance(double[] start, List<WaypointDto> order, boolean loop) {
        double total = 0;
        double lat = start[0];
        double lng = start[1];
        for (WaypointDto waypoint : order) {
            total += Geo.haversineMeters(lat, lng, waypoint.lat(), waypoint.lng());
            lat = waypoint.lat();
            lng = waypoint.lng();
        }
        if (loop) {
            total += Geo.haversineMeters(lat, lng, start[0], start[1]);
        }
        return total;
    }

    private static void addRequest(Map<String, RouteRequest> out, RouteRequest request) {
        if (request.order() == null || request.order().isEmpty() || out.size() >= MAX_TMAP_CALLS) {
            return;
        }
        List<WaypointDto> capped = request.order().size() > MAX_WAYPOINTS
                ? List.copyOf(request.order().subList(0, MAX_WAYPOINTS))
                : List.copyOf(request.order());
        out.putIfAbsent(RunningRouteService.signature(capped),
                new RouteRequest(request.label(), capped));
    }

    private static RunningRouteService.RoutePlan closest(List<RunningRouteService.RoutePlan> plans,
                                                          double targetKm) {
        if (plans == null || plans.isEmpty()) {
            return null;
        }
        return plans.stream().min(Comparator.comparingInt(
                        plan -> distanceError(plan.route().distanceM(), targetKm)))
                .orElse(null);
    }

    private static RunningRouteService.RoutePlan closestDistinct(
            List<RunningRouteService.RoutePlan> plans,
            RunningRouteService.RoutePlan base,
            double targetKm) {
        String baseSignature = base == null ? null : RunningRouteService.signature(base.order());
        return plans.stream()
                .filter(plan -> baseSignature == null
                        || !RunningRouteService.signature(plan.order()).equals(baseSignature))
                .min(Comparator.comparingInt(plan -> distanceError(plan.route().distanceM(), targetKm)))
                .orElse(null);
    }

    private static int distanceError(int distanceM, double targetKm) {
        return (int) Math.round(Math.abs(distanceM - targetKm * 1000));
    }

    private static boolean withinTolerance(int errorM, double targetKm) {
        return errorM <= targetKm * 1000 * 0.10;
    }

    private static List<WaypointDto> autoSelect(double[] start, List<WaypointDto> pool, double targetKm) {
        if (pool.isEmpty()) {
            return List.of();
        }
        int desired = targetKm <= 3 ? 2 : targetKm <= 5 ? 3 : 5;
        List<WaypointDto> nearest = RunningRouteService.nearestOrder(start, pool);
        return List.copyOf(nearest.subList(0, Math.min(desired, nearest.size())));
    }

    private static List<WaypointDto> join(List<WaypointDto> first, List<WaypointDto> second) {
        List<WaypointDto> out = new ArrayList<>();
        if (first != null) {
            out.addAll(first);
        }
        if (second != null) {
            out.addAll(second);
        }
        return out;
    }

    private static List<WaypointDto> dedupe(List<WaypointDto> waypoints) {
        if (waypoints == null) {
            return List.of();
        }
        LinkedHashMap<String, WaypointDto> byPoint = new LinkedHashMap<>();
        for (WaypointDto waypoint : waypoints) {
            if (waypoint != null && waypoint.lat() != null && waypoint.lng() != null) {
                byPoint.putIfAbsent(pointSignature(waypoint), waypoint);
            }
        }
        return List.copyOf(byPoint.values());
    }

    private static List<List<StorySpot>> emptyStories(int size) {
        List<List<StorySpot>> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            out.add(List.of());
        }
        return out;
    }

    private static long remainingNanos(long deadline) {
        return Math.max(1, deadline - System.nanoTime());
    }

    private static boolean hasRemaining(long deadline) {
        return deadline - System.nanoTime() > TimeUnit.MILLISECONDS.toNanos(1);
    }

    private static String pointSignature(WaypointDto waypoint) {
        return waypoint.lat() + "," + waypoint.lng();
    }

    private static String rootMessage(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static ThreadFactory daemonThreads(String prefix) {
        return new ThreadFactory() {
            private int sequence;

            @Override
            public synchronized Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + (++sequence));
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    @PreDestroy
    void closePool() {
        upstreamPool.shutdownNow();
    }

    private record RouteRequest(String label, List<WaypointDto> order) {
    }

    private record ScoredOrder(List<WaypointDto> order, double errorM) {
    }

    private record EnrichedPlan(RunningRouteService.RoutePlan plan, Course course) {
    }
}
