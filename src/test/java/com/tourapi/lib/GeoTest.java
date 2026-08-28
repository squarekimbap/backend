package com.tourapi.lib;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoTest {

    @Test
    void path위점은거리가거의0이다() {
        List<double[]> path = List.of(
                new double[]{37.0, 127.0},
                new double[]{37.0, 127.01});

        assertTrue(Geo.distanceToPathMeters(37.0, 127.005, path) < 1);
    }

    @Test
    void path에서북쪽으로약111m떨어진거리를계산한다() {
        List<double[]> path = List.of(
                new double[]{37.0, 127.0},
                new double[]{37.0, 127.01});

        double distance = Geo.distanceToPathMeters(37.001, 127.005, path);
        assertTrue(distance > 105 && distance < 118);
    }
}
