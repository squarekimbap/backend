package com.tourapi.services;

import com.tourapi.lib.Geo;
import com.tourapi.lib.NaverSearchClient;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.NearbyPlace;
import com.tourapi.model.Place;
import com.tourapi.model.PlacesResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 주변 맛집/카페 = 1차 TourAPI(좌표·거리 정확) + 2차 네이버 지역검색(최신 트렌드) 교차검증.
 *
 * <p>trust 값의 의미:
 * <ul>
 *   <li>{@code verified} — 관광공사·네이버 양쪽에 있음(가장 신뢰, 최상단)</li>
 *   <li>{@code trending} — 네이버 리뷰 상위인데 관광공사엔 없음(최근 뜬 곳)</li>
 *   <li>{@code tour} — 관광공사에만 있음</li>
 * </ul>
 * 네이버 키가 없거나 호출이 실패하면 전부 {@code tour}로 내려간다(기능 자체는 계속 동작).
 */
@ApplicationScoped
public class NearbyPlaceService {

    private static final Logger LOG = Logger.getLogger(NearbyPlaceService.class);
    private static final int FOOD_CONTENT_TYPE = 39;
    /** "서울특별시 서초구 …"에서 시군구를 뽑아 검색어로 쓴다. */
    private static final Pattern SIGUNGU = Pattern.compile("([가-힣]+(?:시|군|구))");
    private static final Pattern CAFE_NAME =
            Pattern.compile(".*(카페|커피|coffee|cafe|베이커리|디저트|다방|찻집|티룸|로스터).*");

    @Inject
    TourService tourService;

    @Inject
    NaverSearchClient naver;

    @ConfigProperty(name = "nearby.trending-radius-factor", defaultValue = "1.5")
    double trendingRadiusFactor;

    /**
     * 좌표 주변 맛집/카페.
     *
     * @param hint 네이버 검색어 앞자리(장소·동네 이름). null이면 TourAPI 주소에서 시군구를 뽑아 쓴다
     */
    public List<NearbyPlace> around(double lat, double lng, int radiusM, String hint, int max) {
        List<Place> tourPlaces = tourPlaces(lat, lng, radiusM);
        String query = hint != null && !hint.isBlank() ? hint.strip() : regionOf(tourPlaces);

        Map<String, NaverSearchClient.LocalPlace> naverByName = new LinkedHashMap<>();
        if (query != null && naver.enabled()) {
            for (String suffix : new String[]{" 맛집", " 카페"}) {
                for (NaverSearchClient.LocalPlace p : naver.local(query + suffix, 5, "comment")) {
                    naverByName.putIfAbsent(normalize(p.name()), p);
                }
            }
        }

        List<NearbyPlace> out = new ArrayList<>();
        // ① TourAPI 결과 — 네이버에도 있으면 verified로 승격하고 링크·업종을 보강
        for (Place p : tourPlaces) {
            NaverSearchClient.LocalPlace hit = match(naverByName, p.title());
            String kind = kind(p.title(), hit == null ? null : hit.category());
            out.add(new NearbyPlace(
                    p.contentId(), kind, p.title(), p.addr(), p.lat(), p.lng(), p.distanceM(),
                    p.thumbnail() != null ? p.thumbnail() : p.image(),
                    p.tel() != null ? p.tel() : (hit == null ? null : hit.tel()),
                    hit == null ? "한국관광공사 TourAPI" : "한국관광공사 TourAPI · 네이버",
                    hit == null ? "tour" : "verified",
                    hit == null ? null : hit.category(),
                    hit == null ? null : hit.link()));
            if (hit != null) {
                naverByName.remove(normalize(hit.name()));
            }
        }
        // ② 네이버에만 있는 최신 인기 — 좌표가 확인되고 반경 안일 때만
        int trendingLimit = (int) Math.round(radiusM * trendingRadiusFactor);
        for (NaverSearchClient.LocalPlace p : naverByName.values()) {
            if (p.lat() == null || p.lng() == null) {
                continue;
            }
            int distance = (int) Math.round(Geo.haversineMeters(lat, lng, p.lat(), p.lng()));
            if (distance > trendingLimit) {
                continue;
            }
            out.add(new NearbyPlace(
                    null, kind(p.name(), p.category()), p.name(), p.addr(),
                    p.lat(), p.lng(), distance, null, p.tel(),
                    "네이버 지역검색", "trending", p.category(), p.link()));
        }

        return topBalanced(out, max);
    }

    private List<Place> tourPlaces(double lat, double lng, int radiusM) {
        try {
            PlacesResponse response = tourService.nearbyPlaces(
                    lat, lng, radiusM, FOOD_CONTENT_TYPE, 1, 40);
            return response.items();
        } catch (UpstreamException e) {
            // 네이버 결과만으로도 화면은 채울 수 있으므로 여기서 끊지 않는다
            LOG.warnf("주변 맛집 TourAPI 조회 실패(네이버만 사용): %s", safeMessage(e));
            return List.of();
        }
    }

    /**
     * 주소 다수결로 시군구 하나를 고른다. 검색어 힌트가 없을 때만 쓴다.
     * 주소 한 건에서는 가장 구체적인 단위를 취한다 — "서울특별시 중구"는 중구,
     * "경기도 성남시 분당구"는 분당구. 시도 단위("서울특별시")로 검색하면 엉뚱한 결과가 나온다.
     */
    static String regionOf(List<Place> places) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Place p : places) {
            if (p.addr() == null) {
                continue;
            }
            String specific = null;
            Matcher m = SIGUNGU.matcher(p.addr());
            while (m.find()) {
                String token = m.group(1);
                if (token.endsWith("특별시") || token.endsWith("광역시") || token.endsWith("특별자치시")) {
                    continue;
                }
                specific = token;
            }
            if (specific != null) {
                counts.merge(specific, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** 이름 정규화 후 완전일치 → 포함관계(4자 이상). 지점명 표기 차이를 흡수한다. */
    static NaverSearchClient.LocalPlace match(
            Map<String, NaverSearchClient.LocalPlace> byName, String title) {
        String key = normalize(title);
        if (key.isEmpty() || byName.isEmpty()) {
            return null;
        }
        NaverSearchClient.LocalPlace exact = byName.get(key);
        if (exact != null) {
            return exact;
        }
        if (key.length() >= 4) {
            for (Map.Entry<String, NaverSearchClient.LocalPlace> e : byName.entrySet()) {
                String k = e.getKey();
                if (k.length() >= 4 && (k.contains(key) || key.contains(k))) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    static String normalize(String s) {
        return s == null ? "" : s.replaceAll("[\\s()\\[\\]·,._\\-]", "").toLowerCase(Locale.ROOT);
    }

    /** 네이버 업종이 있으면 그것으로, 없으면 이름 패턴으로 카페/식당을 가른다. */
    static String kind(String name, String category) {
        if (category != null && category.matches(".*(카페|디저트|베이커리|제과|커피).*")) {
            return "cafe";
        }
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return CAFE_NAME.matcher(value).matches() ? "cafe" : "restaurant";
    }

    /**
     * verified → trending → tour 순, 그룹 안에서는 가까운 순.
     * 식당만 또는 카페만 나오지 않도록 절반씩 채운 뒤 남는 자리를 순위대로 메운다.
     */
    static List<NearbyPlace> topBalanced(List<NearbyPlace> all, int max) {
        List<NearbyPlace> ranked = new ArrayList<>(all);
        ranked.sort((a, b) -> {
            int byTrust = Integer.compare(trustRank(a.trust()), trustRank(b.trust()));
            return byTrust != 0 ? byTrust : Integer.compare(distanceOf(a), distanceOf(b));
        });

        List<NearbyPlace> out = new ArrayList<>();
        int half = Math.max(1, max / 2);
        for (String kind : new String[]{"restaurant", "cafe"}) {
            ranked.stream().filter(p -> kind.equals(p.kind())).limit(half).forEach(out::add);
        }
        for (NearbyPlace p : ranked) {
            if (out.size() >= max) {
                break;
            }
            if (!out.contains(p)) {
                out.add(p);
            }
        }
        out.sort((a, b) -> {
            int byTrust = Integer.compare(trustRank(a.trust()), trustRank(b.trust()));
            return byTrust != 0 ? byTrust : Integer.compare(distanceOf(a), distanceOf(b));
        });
        return List.copyOf(out.size() > max ? out.subList(0, max) : out);
    }

    private static int trustRank(String trust) {
        return switch (trust == null ? "" : trust) {
            case "verified" -> 0;
            case "trending" -> 1;
            default -> 2;
        };
    }

    private static int distanceOf(NearbyPlace p) {
        return p.distanceM() == null ? Integer.MAX_VALUE : p.distanceM();
    }

    private static String safeMessage(UpstreamException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
