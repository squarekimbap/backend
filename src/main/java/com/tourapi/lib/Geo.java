package com.tourapi.lib;

import java.util.ArrayList;
import java.util.List;

/** 좌표 유틸(거리·경로 축소·상승고도). 좌표는 [lat, lng] double 배열. */
public final class Geo {

    private static final double EARTH_R = 6371000.0;

    private Geo() {
    }

    /** 두 좌표 사이 거리(m, 하버사인). */
    public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 경로 점 목록을 maxN개 이하로 균등 축소(첫/끝 점 유지). */
    public static List<double[]> downsample(List<double[]> points, int maxN) {
        if (points.size() <= maxN) {
            return points;
        }
        List<double[]> out = new ArrayList<>(maxN);
        double step = (points.size() - 1) / (double) (maxN - 1);
        for (int i = 0; i < maxN; i++) {
            out.add(points.get((int) Math.round(i * step)));
        }
        return out;
    }

    /** 고도 배열의 누적 상승(양의 표고차 합, m). */
    public static double ascentMeters(double[] elevations) {
        double up = 0;
        for (int i = 1; i < elevations.length; i++) {
            double d = elevations[i] - elevations[i - 1];
            if (d > 0) {
                up += d;
            }
        }
        return up;
    }

    /** 한 좌표와 polyline 사이의 최단거리(m). 짧은 구간에서는 지역 평면으로 근사한다. */
    public static double distanceToPathMeters(double lat, double lng, List<double[]> path) {
        if (path == null || path.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        if (path.size() == 1) {
            return haversineMeters(lat, lng, path.get(0)[0], path.get(0)[1]);
        }

        double best = Double.POSITIVE_INFINITY;
        double cos = Math.cos(Math.toRadians(lat));
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            double ax = Math.toRadians(a[1] - lng) * EARTH_R * cos;
            double ay = Math.toRadians(a[0] - lat) * EARTH_R;
            double bx = Math.toRadians(b[1] - lng) * EARTH_R * cos;
            double by = Math.toRadians(b[0] - lat) * EARTH_R;
            double dx = bx - ax;
            double dy = by - ay;
            double denom = dx * dx + dy * dy;
            double t = denom == 0 ? 0 : -(ax * dx + ay * dy) / denom;
            t = Math.max(0, Math.min(1, t));
            double px = ax + t * dx;
            double py = ay + t * dy;
            best = Math.min(best, Math.hypot(px, py));
        }
        return best;
    }
}
