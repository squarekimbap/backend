package com.tourapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.tourapi.lib.Geo;
import com.tourapi.lib.PublicData;
import com.tourapi.lib.TourApiClient;
import com.tourapi.model.Course;
import com.tourapi.model.RunningCandidate;
import com.tourapi.model.StorySpot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 한국관광공사 Odii 도슨트 가용성 조회. 실패해도 코스 생성은 계속하는 부가 기능이다. */
@ApplicationScoped
public class StoryService {

    private static final Logger LOG = Logger.getLogger(StoryService.class);
    private static final int TRIGGER_RADIUS_M = 100;

    @Inject
    TourApiClient client;

    @ConfigProperty(name = "tour.audio.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "tour.audio.base-url", defaultValue = "https://apis.data.go.kr/B551011/Odii")
    String baseUrl;

    @ConfigProperty(name = "tour.api.mobile-app", defaultValue = "tour-api")
    String mobileApp;

    @ConfigProperty(name = "tour.audio.request-timeout-seconds", defaultValue = "3")
    int requestTimeoutSeconds;

    /** 후보 장소 중 Odii 관광지와 이름 또는 좌표가 맞는 contentId를 반환한다. */
    public Set<String> availableCandidateIds(double lat, double lng, int radius,
                                             List<RunningCandidate> candidates) {
        if (!enabled || candidates == null || candidates.isEmpty()) {
            return Set.of();
        }
        try {
            List<AudioTheme> themes = fetchThemes(lat, lng, radius);
            Set<String> out = new HashSet<>();
            for (RunningCandidate candidate : candidates) {
                for (AudioTheme theme : themes) {
                    if (samePlace(candidate, theme)) {
                        out.add(candidate.contentId());
                        break;
                    }
                }
            }
            return out;
        } catch (Exception e) {
            LOG.warnf("Odii 후보 매칭 실패(이야기 없이 진행): %s", safeMessage(e));
            return Set.of();
        }
    }

    /** 여러 코스 주변 이야기를 한 번 조회한 뒤 코스별 100m 이내 항목으로 나눈다. */
    public List<List<StorySpot>> storiesForCourses(List<Course> courses) {
        return storiesForCourses(courses,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(requestTimeoutSeconds));
    }

    /** 상위 코스 생성의 absolute deadline을 Odii HTTP timeout까지 전달한다. */
    public List<List<StorySpot>> storiesForCourses(List<Course> courses, long deadlineNanos) {
        List<List<StorySpot>> empty = new ArrayList<>();
        for (int i = 0; i < (courses == null ? 0 : courses.size()); i++) {
            empty.add(List.of());
        }
        if (!enabled || courses == null || courses.isEmpty()) {
            return empty;
        }

        double[] origin = firstPoint(courses);
        if (origin == null) {
            return empty;
        }
        int radius = routeRadius(origin, courses);
        try {
            List<AudioStory> stories = fetchStories(
                    origin[0], origin[1], radius, remaining(deadlineNanos));
            List<List<StorySpot>> out = new ArrayList<>();
            for (Course course : courses) {
                Map<String, StorySpot> matched = new LinkedHashMap<>();
                for (AudioStory story : stories) {
                    double d = Geo.distanceToPathMeters(story.lat(), story.lng(), course.path());
                    if (d <= TRIGGER_RADIUS_M) {
                        matched.putIfAbsent(story.id(), new StorySpot(
                                story.id(), story.lat(), story.lng(),
                                (int) Math.round(d), story.playTimeS()));
                    }
                }
                out.add(List.copyOf(matched.values()));
            }
            return out;
        } catch (Exception e) {
            LOG.warnf("Odii 경로 이야기 조회 실패(이야기 없이 진행): %s", safeMessage(e));
            return empty;
        }
    }

    private List<AudioTheme> fetchThemes(double lat, double lng, int radius) {
        JsonNode root = client.getFrom(baseUrl, "themeLocationBasedList",
                locationParams(lat, lng, radius), Duration.ofSeconds(requestTimeoutSeconds));
        PublicData.ensureOk(root);
        List<AudioTheme> out = new ArrayList<>();
        for (JsonNode it : PublicData.items(root)) {
            double y = number(it, "mapY");
            double x = number(it, "mapX");
            if (y != 0 && x != 0) {
                out.add(new AudioTheme(text(it, "title"), y, x));
            }
        }
        return out;
    }

    private List<AudioStory> fetchStories(double lat, double lng, int radius, Duration timeout) {
        JsonNode root = client.getFrom(baseUrl, "storyLocationBasedList",
                locationParams(lat, lng, radius), capped(timeout));
        PublicData.ensureOk(root);
        List<AudioStory> out = new ArrayList<>();
        for (JsonNode it : PublicData.items(root)) {
            String id = text(it, "stid");
            double y = number(it, "mapY");
            double x = number(it, "mapX");
            if (id.isEmpty() || y == 0 || x == 0) {
                continue;
            }
            int seconds = it.path("playTime").asInt(0);
            out.add(new AudioStory(id, y, x, seconds > 0 ? seconds : null));
        }
        return out;
    }

    private Map<String, String> locationParams(double lat, double lng, int radius) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("MobileOS", "ETC");
        p.put("MobileApp", mobileApp);
        p.put("_type", "json");
        p.put("numOfRows", "100");
        p.put("pageNo", "1");
        p.put("mapX", Double.toString(lng));
        p.put("mapY", Double.toString(lat));
        p.put("radius", Integer.toString(Math.max(100, Math.min(20000, radius))));
        p.put("langCode", "ko");
        return p;
    }

    private static boolean samePlace(RunningCandidate candidate, AudioTheme theme) {
        String a = normalize(candidate.name());
        String b = normalize(theme.title());
        boolean nameMatches = !a.isEmpty() && (a.equals(b)
                || (a.length() >= 4 && b.length() >= 4 && (a.contains(b) || b.contains(a))));
        return nameMatches || Geo.haversineMeters(candidate.lat(), candidate.lng(), theme.lat(), theme.lng()) <= 120;
    }

    private static double[] firstPoint(List<Course> courses) {
        for (Course course : courses) {
            if (course != null && course.path() != null && !course.path().isEmpty()) {
                return course.path().get(0);
            }
        }
        return null;
    }

    private static int routeRadius(double[] origin, List<Course> courses) {
        double max = 500;
        for (Course course : courses) {
            if (course == null || course.path() == null) {
                continue;
            }
            for (double[] p : course.path()) {
                max = Math.max(max, Geo.haversineMeters(origin[0], origin[1], p[0], p[1]));
            }
        }
        return (int) Math.ceil(Math.min(20000, max + TRIGGER_RADIUS_M));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("[\\s()\\[\\]·,._\\-]", "").toLowerCase();
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").strip();
    }

    private static double number(JsonNode node, String field) {
        return node.path(field).asDouble(0);
    }

    private static String safeMessage(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private Duration capped(Duration remaining) {
        Duration configured = Duration.ofSeconds(requestTimeoutSeconds);
        return remaining.compareTo(configured) < 0 ? remaining : configured;
    }

    private static Duration remaining(long deadlineNanos) {
        long nanos = deadlineNanos - System.nanoTime();
        if (nanos <= 0) {
            throw new IllegalStateException("Odii 호출 가용 시간 없음");
        }
        return Duration.ofNanos(Math.max(TimeUnit.MILLISECONDS.toNanos(1), nanos));
    }

    private record AudioTheme(String title, double lat, double lng) {
    }

    private record AudioStory(String id, double lat, double lng, Integer playTimeS) {
    }
}
