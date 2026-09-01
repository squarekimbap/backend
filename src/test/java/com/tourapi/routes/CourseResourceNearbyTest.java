package com.tourapi.routes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CourseResourceNearbyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 주변장소_기준점은_대표좌표가아니라_polyline_마지막점이다() throws Exception {
        var course = mapper.readTree("""
                {
                  "n": "테스트 코스",
                  "lat": 36.0,
                  "lng": 126.0,
                  "polyline": [[37.0,127.0],[37.2,127.2]],
                  "poi": [
                    {"n":"출발점","lat":37.0,"lng":127.0},
                    {"n":"완주점","lat":37.2,"lng":127.2}
                  ]
                }
                """);

        CourseResource.NearbyAnchor anchor = CourseResource.nearbyAnchor(course);

        assertEquals(37.2, anchor.lat());
        assertEquals(127.2, anchor.lng());
        assertEquals("완주점", anchor.basedOn());
    }

    @Test
    void 경로가없으면_마지막poi로_폴백한다() throws Exception {
        var course = mapper.readTree("""
                {
                  "n": "테스트 코스",
                  "lat": 36.0,
                  "lng": 126.0,
                  "poi": [
                    {"n":"출발점","lat":37.0,"lng":127.0},
                    {"n":"완주점","lat":37.2,"lng":127.2}
                  ]
                }
                """);

        CourseResource.NearbyAnchor anchor = CourseResource.nearbyAnchor(course);

        assertEquals(37.2, anchor.lat());
        assertEquals(127.2, anchor.lng());
        assertEquals("완주점", anchor.basedOn());
    }

    @Test
    void 좌표가전혀없으면_기준점도없다() throws Exception {
        var course = mapper.readTree("{\"n\":\"테스트 코스\"}");

        assertNull(CourseResource.nearbyAnchor(course));
    }
}
