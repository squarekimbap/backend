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
}
