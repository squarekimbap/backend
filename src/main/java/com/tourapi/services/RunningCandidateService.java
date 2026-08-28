package com.tourapi.services;

import com.tourapi.lib.RegionResolver;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.CandidatesResponse;
import com.tourapi.model.Place;
import com.tourapi.model.PlacesResponse;
import com.tourapi.model.PopularPlace;
import com.tourapi.model.RankingSnapshot;
import com.tourapi.model.RunningCandidate;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 화면 1의 거리/형태를 화면 2의 관광지 후보로 변환한다. */
@ApplicationScoped
public class RunningCandidateService {

    private static final Logger LOG = Logger.getLogger(RunningCandidateService.class);
    private static final int[] CANDIDATE_TYPES = {12, 14, 28};
    private static final ExecutorService UPSTREAM_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "running-candidates-upstream");
        t.setDaemon(true);
        return t;
    });

    @Inject
    TourService tourService;

    @Inject
    RegionResolver regionResolver;

    @Inject
    StoryService storyService;

    @ConfigProperty(name = "tour.api.max-radius", defaultValue = "20000")
    int maxRadius;

    @ConfigProperty(name = "tour.api.reverse-geocode-radius", defaultValue = "3000")
    int reverseGeocodeRadius;

    public CandidatesResponse candidates(double lat, double lng, double distanceKm, String shape, int count) {
        int radius = (int) Math.round(distanceKm * 1000 / ("oneway".equals(shape) ? 1.5 : 3.0));
        radius = Math.max(500, Math.min(maxRadius, radius));

        final int searchRadius = radius;
        CompletableFuture<RegionResolver.Region> regionFuture = CompletableFuture.supplyAsync(
                () -> regionResolver.fromCoordinate(lat, lng, reverseGeocodeRadius), UPSTREAM_POOL);
        List<CompletableFuture<PlacesResponse>> placeFutures = new ArrayList<>();
        for (int type : CANDIDATE_TYPES) {
            placeFutures.add(CompletableFuture.supplyAsync(
                    () -> tourService.nearbyPlaces(lat, lng, searchRadius, type, 1, 50), UPSTREAM_POOL));
        }

        Map<String, Place> byId = new LinkedHashMap<>();
        int failedTypes = 0;
        for (CompletableFuture<PlacesResponse> future : placeFutures) {
            try {
                for (Place place : future.join().items()) {
                    byId.putIfAbsent(place.contentId(), place);
                }
            } catch (Exception e) {
                failedTypes++;
                LOG.warnf("후보 타입 조회 실패(건너뜀): %s", causeMessage(e));
            }
        }
        if (failedTypes == CANDIDATE_TYPES.length) {
            throw new UpstreamException("주변 후보 조회 모두 실패");
        }

        String areaNm = "";
        String signguNm = "";
        Map<String, PopularPlace> rankByName = new HashMap<>();
        try {
            RegionResolver.Region region = regionFuture.join();
            if (region != null) {
                RankingSnapshot snapshot = tourService.rankingForRegion(region);
                areaNm = snapshot.areaNm();
                signguNm = snapshot.signguNm();
                for (PopularPlace popular : snapshot.items()) {
                    rankByName.putIfAbsent(normalize(popular.name()), popular);
                }
            }
        } catch (Exception e) {
            LOG.warnf("후보 집중률 매칭 실패(순위 없이 진행): %s", causeMessage(e));
        }

        List<RunningCandidate> candidates = new ArrayList<>();
        for (Place place : byId.values()) {
            PopularPlace popular = match(rankByName, place.title());
            candidates.add(new RunningCandidate(
                    place.contentId(), place.title(), place.lat(), place.lng(), place.distanceM(),
                    place.contentTypeId(), place.addr(),
                    place.thumbnail() != null ? place.thumbnail() : place.image(),
                    popular == null ? null : popular.rank(),
                    popular == null ? null : popular.avgConcentration(), false));
        }
        candidates.sort(Comparator
                .comparing((RunningCandidate candidate) -> candidate.popularityRank() == null)
                .thenComparing(candidate -> candidate.popularityRank() == null
                        ? Integer.MAX_VALUE : candidate.popularityRank())
                .thenComparing(candidate -> candidate.distanceM() == null
                        ? Integer.MAX_VALUE : candidate.distanceM()));

        List<RunningCandidate> top = candidates.size() > count
                ? new ArrayList<>(candidates.subList(0, count)) : candidates;
        Set<String> storyIds = storyService.availableCandidateIds(lat, lng, radius, top);
        if (!storyIds.isEmpty()) {
            List<RunningCandidate> marked = new ArrayList<>(top.size());
            for (RunningCandidate candidate : top) {
                marked.add(new RunningCandidate(
                        candidate.contentId(), candidate.name(), candidate.lat(), candidate.lng(),
                        candidate.distanceM(), candidate.contentTypeId(), candidate.addr(), candidate.image(),
                        candidate.popularityRank(), candidate.popularityAvg(), storyIds.contains(candidate.contentId())));
            }
            top = marked;
        }
        int storyCount = (int) top.stream().filter(RunningCandidate::storyAvailable).count();
        return new CandidatesResponse(lat, lng, shape, radius, areaNm, signguNm,
                top.size(), storyCount, List.copyOf(top));
    }

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
            for (Map.Entry<String, PopularPlace> entry : rankByName.entrySet()) {
                String other = entry.getKey();
                if (other.length() >= 4 && (other.contains(key) || key.contains(other))) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s()\\[\\]·,._\\-]", "").toLowerCase();
    }

    private static String causeMessage(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
