package com.tourapi.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourapi.lib.TourApiClient;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.Course;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StoryServiceTest {

    @Test
    void 비활성상태에서는Odii를호출하지않는다() {
        TourApiClient client = mock(TourApiClient.class);
        StoryService service = service(client, false);

        var stories = service.storiesForCourses(List.of(course()));

        assertEquals(List.of(List.of()), stories);
        verifyNoInteractions(client);
    }

    @Test
    void Odii위치응답을코스100m안의트리거로변환한다() throws Exception {
        TourApiClient client = mock(TourApiClient.class);
        StoryService service = service(client, true);
        String json = "{\"response\":{\"header\":{\"resultCode\":\"0000\"},"
                + "\"body\":{\"items\":{\"item\":[{\"stid\":\"story-1\","
                + "\"mapY\":37.0001,\"mapX\":127.0,\"playTime\":90}]}}}}";
        when(client.getFrom(eq("https://odii.test"), eq("storyLocationBasedList"),
                anyMap(), any(Duration.class)))
                .thenReturn(new ObjectMapper().readTree(json));

        var stories = service.storiesForCourses(List.of(course()));

        assertEquals(1, stories.get(0).size());
        assertEquals("story-1", stories.get(0).get(0).storyId());
        assertEquals(90, stories.get(0).get(0).playTimeS());
    }

    @Test
    void Odii장애는빈이야기로폴백한다() {
        TourApiClient client = mock(TourApiClient.class);
        StoryService service = service(client, true);
        when(client.getFrom(eq("https://odii.test"), eq("storyLocationBasedList"),
                anyMap(), any(Duration.class)))
                .thenThrow(new UpstreamException("timeout"));

        assertEquals(List.of(List.of()), service.storiesForCourses(List.of(course())));
    }

    @Test
    void 상위Deadline이지났으면Odii를호출하지않는다() {
        TourApiClient client = mock(TourApiClient.class);
        StoryService service = service(client, true);

        assertEquals(List.of(List.of()),
                service.storiesForCourses(List.of(course()), System.nanoTime() - 1));
        verifyNoInteractions(client);
    }

    private static StoryService service(TourApiClient client, boolean enabled) {
        StoryService service = new StoryService();
        service.client = client;
        service.enabled = enabled;
        service.baseUrl = "https://odii.test";
        service.mobileApp = "test";
        service.requestTimeoutSeconds = 3;
        return service;
    }

    private static Course course() {
        return new Course("", List.of(), 1000, 600, 0, 0, "하",
                List.of(new double[]{37.0, 127.0}, new double[]{37.001, 127.0}));
    }
}
