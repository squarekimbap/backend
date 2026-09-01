package com.tourapi.services;

import com.tourapi.lib.ElevationClient;
import com.tourapi.lib.Geo;
import com.tourapi.lib.TmapClient;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.Course;
import com.tourapi.model.RouteSegment;
import com.tourapi.model.WaypointDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** TMAP 경로·고도·난이도 계산. 후보 탐색이나 화면 조립 책임은 갖지 않는다. */
@ApplicationScoped
public class RunningRouteService {

    @Inject
    TmapClient tmap;

    @Inject
    ElevationClient elevationClient;

    /** 순서 하나의 TMAP 경로만 계산한다. 거리 후보 탐색에서는 고도 호출 전에 이 결과로 비교한다. */
    RoutePlan plan(String label,
                   double[] start,
                   List<WaypointDto> order,
                   boolean loop,
                   long deadlineNanos) {
        if (order == null || order.isEmpty()) {
            throw new UpstreamException("경유지 없는 경로는 계산할 수 없음");
        }
        List<double[]> via = new ArrayList<>();
        double[] end;
        if (loop) {
            for (WaypointDto waypoint : order) {
                via.add(point(waypoint));
            }
            end = start;
        } else if (order.size() == 1) {
            end = point(order.get(0));
        } else {
            for (int i = 0; i < order.size() - 1; i++) {
                via.add(point(order.get(i)));
            }
            end = point(order.get(order.size() - 1));
        }
        return new RoutePlan(label, List.copyOf(order),
                tmap.pedestrian(start, via, end, remaining(deadlineNanos, "TMAP")));
    }

    Course toCourse(RoutePlan plan, long deadlineNanos) {
        double[] elevations = elevationClient.elevations(
                Geo.downsample(plan.route().path(), 100), remaining(deadlineNanos, "Elevation"));
        double ascent = Geo.ascentMeters(elevations);
        double km = plan.route().distanceM() / 1000.0;
        double perKm = km > 0 ? ascent / km : 0;
        String difficulty = perKm <= 10 ? "하" : perKm <= 25 ? "중" : "상";
        return new Course(plan.label(), waypointNames(plan.order()),
                plan.route().distanceM(), plan.route().durationS(), round1(ascent), round1(perKm),
                difficulty, Geo.downsample(plan.route().path(), 200));
    }

    List<RouteSegment> segments(RoutePlan plan, double[] start, boolean loop) {
        List<double[]> controls = new ArrayList<>();
        List<String> names = new ArrayList<>();
        controls.add(start);
        names.add("출발지");
        for (int i = 0; i < plan.order().size(); i++) {
            WaypointDto waypoint = plan.order().get(i);
            controls.add(point(waypoint));
            names.add(nameOf(waypoint, i));
        }
        if (loop) {
            controls.add(start);
            names.add("출발지");
        }

        List<double[]> path = plan.route().path();
        List<Integer> indices = new ArrayList<>();
        indices.add(0);
        int from = 0;
        for (int i = 1; i < controls.size(); i++) {
            boolean finalEndpoint = i == controls.size() - 1;
            int index = finalEndpoint ? path.size() - 1 : nearestPathIndex(path, controls.get(i), from);
            indices.add(Math.max(from, index));
            from = Math.max(from, index);
        }

        List<Double> raw = new ArrayList<>();
        double rawTotal = 0;
        for (int i = 1; i < indices.size(); i++) {
            double distance = pathDistance(path, indices.get(i - 1), indices.get(i));
            raw.add(distance);
            rawTotal += distance;
        }
        double scale = rawTotal > 0 ? plan.route().distanceM() / rawTotal : 1;
        List<RouteSegment> out = new ArrayList<>();
        int assigned = 0;
        for (int i = 0; i < raw.size(); i++) {
            int distance = i == raw.size() - 1
                    ? Math.max(0, plan.route().distanceM() - assigned)
                    : (int) Math.round(raw.get(i) * scale);
            assigned += distance;
            out.add(new RouteSegment(names.get(i), names.get(i + 1), distance));
        }
        return out;
    }

    static List<WaypointDto> nearestOrder(double[] start, List<WaypointDto> waypoints) {
        List<WaypointDto> remain = new ArrayList<>(waypoints);
        List<WaypointDto> out = new ArrayList<>(waypoints.size());
        double curLat = start[0];
        double curLng = start[1];
        while (!remain.isEmpty()) {
            WaypointDto best = remain.get(0);
            double bestDistance = Double.MAX_VALUE;
            for (WaypointDto waypoint : remain) {
                double distance = Geo.haversineMeters(curLat, curLng, waypoint.lat(), waypoint.lng());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = waypoint;
                }
            }
            remain.remove(best);
            out.add(best);
            curLat = best.lat();
            curLng = best.lng();
        }
        return out;
    }

    static String signature(List<WaypointDto> order) {
        StringBuilder signature = new StringBuilder();
        for (WaypointDto waypoint : order) {
            signature.append(waypoint.lat()).append(',').append(waypoint.lng()).append(';');
        }
        return signature.toString();
    }

    private static List<String> waypointNames(List<WaypointDto> order) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            names.add(nameOf(order.get(i), i));
        }
        return names;
    }

    private static String nameOf(WaypointDto waypoint, int index) {
        return waypoint.name() == null || waypoint.name().isBlank() ? "경유지" + (index + 1) : waypoint.name();
    }

    private static double[] point(WaypointDto waypoint) {
        return new double[]{waypoint.lat(), waypoint.lng()};
    }

    private static int nearestPathIndex(List<double[]> path, double[] control, int from) {
        int bestIndex = from;
        double best = Double.MAX_VALUE;
        for (int i = from; i < path.size(); i++) {
            double[] point = path.get(i);
            double distance = Geo.haversineMeters(control[0], control[1], point[0], point[1]);
            if (distance < best) {
                best = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static double pathDistance(List<double[]> path, int from, int to) {
        double sum = 0;
        for (int i = Math.max(1, from + 1); i <= to && i < path.size(); i++) {
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            sum += Geo.haversineMeters(a[0], a[1], b[0], b[1]);
        }
        return sum;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static Duration remaining(long deadlineNanos, String stage) {
        long nanos = deadlineNanos - System.nanoTime();
        if (nanos <= 0) {
            throw new UpstreamException(stage + " 호출 가용 시간 없음");
        }
        return Duration.ofNanos(Math.max(TimeUnit.MILLISECONDS.toNanos(1), nanos));
    }

    record RoutePlan(String label, List<WaypointDto> order, TmapClient.TmapRoute route) {
    }
}
