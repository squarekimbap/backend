package com.tourapi.services;

import com.tourapi.lib.ElevationClient;
import com.tourapi.lib.Geo;
import com.tourapi.lib.RegionResolver;
import com.tourapi.lib.TmapClient;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.CandidatesResponse;
import com.tourapi.model.Course;
import com.tourapi.model.Place;
import com.tourapi.model.PlacesResponse;
import com.tourapi.model.PopularPlace;
import com.tourapi.model.RankingSnapshot;
import com.tourapi.model.RoutesResponse;
import com.tourapi.model.RunningCandidate;
import com.tourapi.model.WaypointDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 러닝 코스 추천 (README 흐름).
 * Phase A: 설문 → 주변 관광지(12·14·28) + 집중률 순위 매칭 → 경유지 후보.
 * Phase B: 선택 경유지 → 순서 후보(선택/역순/근접) → TMAP 경로 + 고도 → 난이도 → 코스 최대 3개.
 */
@ApplicationScoped
public class RunningService {

    private static final Logger LOG = Logger.getLogger(RunningService.class);

    /** 경유지 후보로 삼는 콘텐츠 타입: 관광지·문화시설·레포츠 (숙박/쇼핑/음식점 제외). */
    private static final int[] CANDIDATE_TYPES = {12, 14, 28};

    /** 느린 data.go.kr 병렬 호출용(IO 대기 전용). commonPool은 Lambda 저코어에서 직렬화돼 못 쓴다. */
    private static final ExecutorService UPSTREAM_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "running-upstream");
        t.setDaemon(true);
        return t;
    });

    @Inject
    TourService tourService;

    @Inject
    RegionResolver regionResolver;

    @Inject
    TmapClient tmap;

    @Inject
    ElevationClient elevationClient;

    @ConfigProperty(name = "tour.api.max-radius", defaultValue = "20000")
    int maxRadius;

    @ConfigProperty(name = "tour.api.reverse-geocode-radius", defaultValue = "3000")
    int reverseGeocodeRadius;

    // ── Phase A: 경유지 후보 ─────────────────────────────────────

    public CandidatesResponse candidates(double lat, double lng, double distanceKm, String shape, int count) {
        // 희망 거리에서 탐색 반경 산출: loop면 왕복이므로 1/3, oneway면 2/3 지점까지
        int radius = (int) Math.round(distanceKm * 1000 / ("oneway".equals(shape) ? 1.5 : 3.0));
        radius = Math.max(500, Math.min(maxRadius, radius));

        // data.go.kr 4콜(타입3 + 지역)을 병렬로 — 순차면 6~12s 걸린다
        final int r = radius;
        CompletableFuture<RegionResolver.Region> regionFut = CompletableFuture.supplyAsync(
                () -> regionResolver.fromCoordinate(lat, lng, reverseGeocodeRadius), UPSTREAM_POOL);
        List<CompletableFuture<PlacesResponse>> placeFuts = new ArrayList<>();
        for (int type : CANDIDATE_TYPES) {
            final int t = type;
            placeFuts.add(CompletableFuture.supplyAsync(
                    () -> tourService.nearbyPlaces(lat, lng, r, t, 1, 50), UPSTREAM_POOL));
        }

        Map<String, Place> byId = new LinkedHashMap<>();
        int failedTypes = 0;
        for (CompletableFuture<PlacesResponse> f : placeFuts) {
            try {
                for (Place p : f.join().items()) {
                    byId.putIfAbsent(p.contentId(), p);
                }
            } catch (Exception e) {
                failedTypes++;
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                LOG.warnf("후보 타입 조회 실패(건너뜀): %s", cause.getMessage());
            }
        }
        if (failedTypes == CANDIDATE_TYPES.length) {
            throw new UpstreamException("주변 후보 조회 모두 실패");
        }

        // 집중률 순위 매칭(시군구 하루 캐시 공유). 실패해도 후보는 반환한다.
        String areaNm = "";
        String signguNm = "";
        Map<String, PopularPlace> rankByName = new HashMap<>();
        try {
            RegionResolver.Region region = regionFut.join();
            if (region != null) {
                RankingSnapshot snap = tourService.rankingForRegion(region);
                areaNm = snap.areaNm();
                signguNm = snap.signguNm();
                for (PopularPlace pp : snap.items()) {
                    rankByName.putIfAbsent(normalize(pp.name()), pp);
                }
            }
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOG.warnf("후보 집중률 매칭 실패(순위 없이 진행): %s", cause.getMessage());
        }

        List<RunningCandidate> cands = new ArrayList<>();
        for (Place p : byId.values()) {
            PopularPlace pp = match(rankByName, p.title());
            cands.add(new RunningCandidate(
                    p.title(), p.lat(), p.lng(), p.distanceM(), p.contentTypeId(), p.addr(),
                    p.thumbnail() != null ? p.thumbnail() : p.image(),
                    pp == null ? null : pp.rank(),
                    pp == null ? null : pp.avgConcentration()));
        }
        // 집중률 순위 있는 것 우선(순위 오름차순) → 나머지는 가까운 순
        cands.sort(Comparator
                .comparing((RunningCandidate c) -> c.popularityRank() == null)
                .thenComparing(c -> c.popularityRank() == null ? Integer.MAX_VALUE : c.popularityRank())
                .thenComparing(c -> c.distanceM() == null ? Integer.MAX_VALUE : c.distanceM()));

        List<RunningCandidate> top = cands.size() > count ? List.copyOf(cands.subList(0, count)) : cands;
        return new CandidatesResponse(lat, lng, shape, radius, areaNm, signguNm, top.size(), top);
    }

    /** 집중률 순위 이름 매칭: 정규화 후 완전일치 → 포함관계(4자 이상) 순으로 시도. */
    private static PopularPlace match(Map<String, PopularPlace> rankByName, String title) {
        String key = normalize(title);
        if (key.isEmpty()) {
            return null;
        }
        PopularPlace exact = rankByName.get(key);
        if (exact != null) {
            return exact;
        }
        if (key.length() >= 4) {
            for (Map.Entry<String, PopularPlace> e : rankByName.entrySet()) {
                String k = e.getKey();
                if (k.length() >= 4 && (k.contains(key) || key.contains(k))) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("[\\s()\\[\\]·,._\\-]", "").toLowerCase();
    }

    // ── Phase B: 코스 계산 ───────────────────────────────────────

    public RoutesResponse routes(double[] start, List<WaypointDto> waypoints, String shape, Double targetKm) {
        boolean loop = !"oneway".equals(shape);

        // 순서 후보: 선택순 → 역순 → 근접순 (동일 순서는 제거)
        LinkedHashMap<String, List<WaypointDto>> orderings = new LinkedHashMap<>();
        putOrder(orderings, "선택순", waypoints);
        List<WaypointDto> reversed = new ArrayList<>(waypoints);
        java.util.Collections.reverse(reversed);
        putOrder(orderings, "역순", reversed);
        putOrder(orderings, "근접순", nearestOrder(start, waypoints));

        List<Course> courses = new ArrayList<>();
        for (Map.Entry<String, List<WaypointDto>> e : orderings.entrySet()) {
            try {
                courses.add(buildCourse(e.getKey(), start, e.getValue(), loop));
            } catch (UpstreamException ex) {
                LOG.warnf("코스 계산 실패(%s): %s", e.getKey(), ex.getMessage());
            }
        }
        if (courses.isEmpty()) {
            throw new UpstreamException("모든 순서 후보의 경로 계산 실패");
        }

        if (targetKm != null) {
            double t = targetKm;
            courses.sort(Comparator.comparingDouble(c -> Math.abs(c.distanceM() / 1000.0 - t)));
        } else {
            courses.sort(Comparator.comparingInt(Course::distanceM));
        }
        List<Course> top = courses.size() > 3 ? List.copyOf(courses.subList(0, 3)) : courses;
        return new RoutesResponse(loop ? "loop" : "oneway", top.size(), top);
    }

    /** 경유 순서 하나를 실제 코스로: TMAP 경로 → 고도 샘플링 → 난이도. */
    private Course buildCourse(String label, double[] start, List<WaypointDto> order, boolean loop) {
        List<double[]> via = new ArrayList<>();
        double[] end;
        if (loop) {
            for (WaypointDto w : order) {
                via.add(new double[]{w.lat(), w.lng()});
            }
            end = start;
        } else if (order.size() == 1) {
            end = new double[]{order.get(0).lat(), order.get(0).lng()};
        } else {
            for (int i = 0; i < order.size() - 1; i++) {
                via.add(new double[]{order.get(i).lat(), order.get(i).lng()});
            }
            WaypointDto last = order.get(order.size() - 1);
            end = new double[]{last.lat(), last.lng()};
        }

        TmapClient.TmapRoute route = tmap.pedestrian(start, via, end);

        double[] elev = elevationClient.elevations(Geo.downsample(route.path(), 100));
        double ascent = Geo.ascentMeters(elev);
        double km = route.distanceM() / 1000.0;
        double perKm = km > 0 ? ascent / km : 0;
        // 난이도 기준(CLAUDE.md): km당 상승 10m↓=하 / 25m↓=중 / 초과=상
        String difficulty = perKm <= 10 ? "하" : perKm <= 25 ? "중" : "상";

        List<String> names = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            String n = order.get(i).name();
            names.add(n == null || n.isBlank() ? "경유지" + (i + 1) : n);
        }
        return new Course(label, names, route.distanceM(), route.durationS(),
                round1(ascent), round1(perKm), difficulty, Geo.downsample(route.path(), 200));
    }

    /** 출발점에서 가까운 곳부터 차례로 방문(그리디). */
    private static List<WaypointDto> nearestOrder(double[] start, List<WaypointDto> waypoints) {
        List<WaypointDto> remain = new ArrayList<>(waypoints);
        List<WaypointDto> out = new ArrayList<>(waypoints.size());
        double curLat = start[0];
        double curLng = start[1];
        while (!remain.isEmpty()) {
            WaypointDto best = remain.get(0);
            double bestD = Double.MAX_VALUE;
            for (WaypointDto w : remain) {
                double d = Geo.haversineMeters(curLat, curLng, w.lat(), w.lng());
                if (d < bestD) {
                    bestD = d;
                    best = w;
                }
            }
            remain.remove(best);
            out.add(best);
            curLat = best.lat();
            curLng = best.lng();
        }
        return out;
    }

    private static void putOrder(Map<String, List<WaypointDto>> orderings, String label, List<WaypointDto> order) {
        StringBuilder sig = new StringBuilder();
        for (WaypointDto w : order) {
            sig.append(w.lat()).append(',').append(w.lng()).append(';');
        }
        for (List<WaypointDto> existing : orderings.values()) {
            StringBuilder es = new StringBuilder();
            for (WaypointDto w : existing) {
                es.append(w.lat()).append(',').append(w.lng()).append(';');
            }
            if (es.toString().contentEquals(sig)) {
                return; // 동일 순서 이미 있음
            }
        }
        orderings.put(label, order);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
