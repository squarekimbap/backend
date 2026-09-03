package com.tourapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tourapi.lib.Geo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 코스 검수 계산. 사람이 눈으로 봐야 할 이유(경로 이탈·사진 중복·저작권·대체 사진)를
 * 데이터에서 뽑아내고, 카드에 그릴 경로 축소본을 만든다.
 *
 * <p>라우트가 아니라 서비스에 두는 이유: 전체 목록 응답이 커서 HTTP로는 테스트할 수 없다
 * (MockEventServer가 큰 청크 응답을 닫지 않아 read timeout — 코스 목록과 같은 함정).
 */
@ApplicationScoped
public class CourseReview {

    /** 경유지가 경로에서 이만큼 넘게 떨어지면 계약 위반(validate_courses.py와 같은 기준). */
    static final int OFF_ROUTE_M = 100;
    /** 카드에 그리는 경로 축소본의 점 개수. 형태만 보면 되므로 원본 200점은 과하다. */
    static final int SIGIL_POINTS = 48;

    private static final String[] SUMMARY_FIELDS = {
            "id", "city", "headline", "km", "shape", "difficulty",
            "photo", "photoTitle", "photoLicense"};

    @Inject
    ObjectMapper mapper;

    @Inject
    CourseCatalog catalog;

    @Inject
    CourseOverrides overrides;

    /** 검수 목록. 코스마다 flags가 비어 있으면 볼 것이 없다는 뜻이다. */
    public ArrayNode summaries() {
        // 같은 사진을 여러 코스가 쓰면 서로를 가리켜 준다
        Map<String, List<String>> photoUsers = new HashMap<>();
        for (JsonNode c : catalog.all()) {
            photoUsers.computeIfAbsent(c.path("photo").asText(""), k -> new ArrayList<>())
                    .add(c.path("id").asText());
        }
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode c : catalog.all()) {
            out.add(summarize(c, photoUsers));
        }
        return out;
    }

    /** 검수 상세. 원본에 각 지점의 경로 이탈 거리를 재서 붙인다. 없는 코스면 null. */
    public ObjectNode detail(String id) {
        JsonNode c = catalog.byId(id);
        if (c == null) {
            return null;
        }
        List<double[]> path = path(c);
        ObjectNode d = c.deepCopy();
        d.set("poi", measured(c.path("poi"), path, "n", true));
        d.set("checkpoints", measured(c.path("checkpoints"), path, "name", false));
        return d;
    }

    private ObjectNode summarize(JsonNode c, Map<String, List<String>> photoUsers) {
        List<double[]> path = path(c);
        ObjectNode s = mapper.createObjectNode();
        for (String f : SUMMARY_FIELDS) {
            if (c.has(f)) {
                s.set(f, c.get(f));
            }
        }
        s.put("poiCount", c.path("poi").size());
        s.put("edited", overrides.forCourse(c.path("id").asText()) != null);

        ArrayNode sigil = mapper.createArrayNode();
        for (double[] p : Geo.downsample(path, SIGIL_POINTS)) {
            sigil.add(mapper.createArrayNode().add(p[0]).add(p[1]));
        }
        s.set("sigil", sigil);
        s.set("flags", flags(c, path, photoUsers));
        return s;
    }

    private ArrayNode flags(JsonNode c, List<double[]> path, Map<String, List<String>> photoUsers) {
        ArrayNode flags = mapper.createArrayNode();
        long off = count(c.path("poi"), path, "lat", "lng");
        if (off > 0) {
            flags.add(flag("off-route", "경유지 " + off + "곳이 경로 밖"));
        }
        long far = count(c.path("poi"), path, "placeLat", "placeLng");
        if (far > 0) {
            flags.add(flag("placelat", "원 좌표 " + far + "곳이 경로 밖 — 핀에 쓰면 어긋난다"));
        }
        List<String> shared = photoUsers.getOrDefault(c.path("photo").asText(""), List.of());
        if (shared.size() > 1) {
            flags.add(flag("dup-photo", "사진 공유: " + shared.stream()
                    .filter(i -> !i.equals(c.path("id").asText()))
                    .collect(Collectors.joining(", "))));
        }
        if (c.path("photoLicense").asText("").contains("제3유형")) {
            flags.add(flag("license", "공공누리 제3유형 — 변경(크롭) 금지"));
        }
        // photoTitle의 괄호는 "다른 장소 사진으로 대신함" 같은 자진 신고다
        if (c.path("photoTitle").asText("").contains("(")) {
            flags.add(flag("substitute", "다른 장소 사진으로 대체됨"));
        }
        return flags;
    }

    /** 해당 좌표 쌍이 있는 지점 중 경로에서 100m 넘게 떨어진 개수. */
    private static long count(JsonNode points, List<double[]> path, String latField, String lngField) {
        return points.valueStream()
                .filter(p -> p.hasNonNull(latField))
                .filter(p -> distance(p.path(latField).asDouble(), p.path(lngField).asDouble(), path)
                        > OFF_ROUTE_M)
                .count();
    }

    /** 각 지점에 경로까지의 거리를 붙인다. 원 좌표(placeLat)가 있으면 그것도 함께 잰다. */
    private ArrayNode measured(JsonNode points, List<double[]> path, String nameField,
                               boolean withPlace) {
        ArrayNode out = mapper.createArrayNode();
        int no = 1;
        for (JsonNode p : points) {
            double lat = p.path("lat").asDouble(), lng = p.path("lng").asDouble();
            ObjectNode n = mapper.createObjectNode();
            n.put("no", no++);
            n.put("name", p.path(nameField).asText(""));
            n.put("lat", lat);
            n.put("lng", lng);
            n.put("offRouteM", Math.round(distance(lat, lng, path)));
            if (withPlace && p.hasNonNull("placeLat")) {
                double plat = p.path("placeLat").asDouble(), plng = p.path("placeLng").asDouble();
                ObjectNode place = mapper.createObjectNode();
                place.put("lat", plat);
                place.put("lng", plng);
                place.put("offRouteM", Math.round(distance(plat, plng, path)));
                n.set("place", place);
            }
            out.add(n);
        }
        return out;
    }

    private static List<double[]> path(JsonNode c) {
        List<double[]> out = new ArrayList<>();
        for (JsonNode p : c.path("polyline")) {
            out.add(new double[]{p.path(0).asDouble(), p.path(1).asDouble()});
        }
        return out;
    }

    private static double distance(double lat, double lng, List<double[]> path) {
        return path.isEmpty() ? 0 : Geo.distanceToPathMeters(lat, lng, path);
    }

    private ObjectNode flag(String kind, String label) {
        ObjectNode f = mapper.createObjectNode();
        f.put("kind", kind);
        f.put("label", label);
        return f;
    }
}
