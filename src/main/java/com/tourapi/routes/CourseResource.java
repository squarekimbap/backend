package com.tourapi.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.tourapi.lib.RankingCache;
import com.tourapi.model.ApiError;
import com.tourapi.model.CourseListResponse;
import com.tourapi.model.CourseNearbyResponse;
import com.tourapi.model.NearbyPlace;
import com.tourapi.services.CourseCatalog;
import com.tourapi.services.CourseGpxService;
import com.tourapi.services.NearbyPlaceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 편집 코스 카탈로그 라우트 (홈 피드·코스 상세 화면용).
 * 응답은 수집본 스키마 그대로 — 상세: id·n·km·min·lv·mood·tags·headline·subhead·
 * body[]·deep[]·ops[]·unsure[]·sources[{title,url,type,checkedAt}]·
 * poi[{n,d,photo}]·photo·photoTitle·photoLicense·city·region과
 * polyline[[lat,lng]]·guide[{lat,lng,text}]·checkpoints[{id,name,lat,lng,audioSeconds,description}].
 */
@Path("/v1/courses")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "courses", description = "편집 코스 카탈로그 (지역별 수집 원고)")
public class CourseResource {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Inject
    CourseCatalog catalog;

    @Inject
    CourseGpxService courseGpxService;

    @Inject
    NearbyPlaceService nearbyPlaceService;

    @Inject
    RankingCache cache;

    @GET
    @Operation(summary = "코스 목록(요약)",
            description = "카드 렌더링용 요약 필드만 내려준다. items[].waypoints는 경유지 이름 문자열 배열이다. "
                    + "좌표·설명·사진을 포함한 상세 경유지는 GET /v1/courses/{id}의 poi 배열을 사용한다. "
                    + "city(도시명 '서울' 또는 cityId 'seoul')로 필터 가능.")
    @APIResponse(responseCode = "200", description = "코스 목록과 홈 카드용 경유지 이름 배열",
            content = @Content(schema = @Schema(implementation = CourseListResponse.class)))
    public Response list(
            @Parameter(description = "도시 필터(도시명 또는 cityId). 없으면 전체", example = "서울")
            @QueryParam("city") String city) {
        ArrayNode items = catalog.list(city);
        return Response.ok(java.util.Map.of("count", items.size(), "items", items)).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "코스 상세",
            description = "id로 코스 전체 원고와 정적 보행 경로(polyline), 안내(guide), "
                    + "100m 진입 도슨트(checkpoints)를 조회한다. 상세 경유지는 poi 객체 배열이며 "
                    + "목록의 waypoints 문자열 배열과 구분한다. 지원하는 구 ID는 같은 코스의 신 ID로 정규화해 반환한다. "
                    + "예: seoul-banpo-10k")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "코스 전체 필드(JSON)"),
            @APIResponse(responseCode = "404", description = "없는 id")
    })
    public Response detail(
            @Parameter(description = "코스 id", required = true, example = "seoul-banpo-10k")
            @PathParam("id") String id) {
        JsonNode c = catalog.byId(id);
        if (c == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ApiError("not_found", "코스 없음: " + id)).build();
        }
        return Response.ok(c).build();
    }

    @GET
    @Path("/{id}/gpx")
    @Produces("application/gpx+xml")
    @Operation(summary = "Garmin 호환 GPX 코스 파일",
            description = "코스 polyline을 GPX 1.1 Track(trk/trkseg/trkpt)으로 내보낸다. "
                    + "Garmin Connect의 코스 가져오기에서 사용하며 poi는 표준 waypoint로 함께 넣는다.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "GPX 1.1 파일"),
            @APIResponse(responseCode = "404", description = "없는 id"),
            @APIResponse(responseCode = "409", description = "내보낼 경로 없음")
    })
    public Response gpx(
            @Parameter(description = "코스 id", required = true, example = "seoul-banpo-10k")
            @PathParam("id") String id) {
        JsonNode course = catalog.byId(id);
        if (course == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(new ApiError("not_found", "코스 없음: " + id)).build();
        }
        try {
            String canonicalId = course.path("id").asText(id);
            return Response.ok(courseGpxService.create(course))
                    .type("application/gpx+xml; charset=UTF-8")
                    .header("Content-Disposition", "attachment; filename=\"" + canonicalId + ".gpx\"")
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.CONFLICT)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(new ApiError("route_unavailable", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{id}/nearby")
    @Operation(summary = "코스 주변 맛집·카페",
            description = "코스의 마지막 경유지를 기준으로 1차 TourAPI + 2차 네이버 지역검색을 교차검증해 반환한다. "
                    + "코스 상세 화면에서 본문과 병렬로 호출하면 된다(느려도 상세는 먼저 뜬다). 하루 단위 캐시.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "맛집/카페 목록(교차검증 순)"),
            @APIResponse(responseCode = "404", description = "없는 id")
    })
    public Response nearby(
            @Parameter(description = "코스 id", required = true, example = "seoul-banpo-10k")
            @PathParam("id") String id,
            @Parameter(description = "검색 반경(m). 기본 1500, 300~5000", example = "1500")
            @QueryParam("radius") Integer radius) {
        JsonNode c = catalog.byId(id);
        if (c == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ApiError("not_found", "코스 없음: " + id)).build();
        }
        int radiusM = radius == null ? 1500 : Math.max(300, Math.min(5000, radius));
        String canonicalId = c.path("id").asText(id);

        // 기준점: 코스 대표 좌표가 있으면 그것부터.
        // poi 목록은 수집본에서 '경로상 경유지'가 아니라 그 도시의 관련 스팟 추천이 섞여 있어서
        // 마지막 poi를 그대로 쓰면 온천천 코스가 광안리 기준으로 잡히는 식의 사고가 난다.
        double lat;
        double lng;
        String basedOn;
        JsonNode base = null;
        if (c.hasNonNull("lat") && c.hasNonNull("lng")) {
            lat = c.path("lat").asDouble();
            lng = c.path("lng").asDouble();
            basedOn = c.path("n").asText(null);
        } else {
            for (JsonNode poi : c.path("poi")) {
                if (poi.hasNonNull("lat") && poi.hasNonNull("lng")) {
                    base = poi;
                }
            }
            lat = base == null ? 0 : base.path("lat").asDouble();
            lng = base == null ? 0 : base.path("lng").asDouble();
            basedOn = base == null ? null : base.path("n").asText(null);
        }
        if (base == null && !(c.hasNonNull("lat") && c.hasNonNull("lng"))) {
            // 좌표를 못 찾은 코스(수집 원본에 좌표 미확보) — 빈 목록으로 조용히 내려간다
            return Response.ok(new CourseNearbyResponse(canonicalId, null, null, null, radiusM, 0, List.of()))
                    .build();
        }

        String cacheKey = "nearby#" + canonicalId + "#" + radiusM + "#"
                + LocalDate.now(KST).format(DateTimeFormatter.BASIC_ISO_DATE);

        CourseNearbyResponse cached = cache.get(cacheKey, CourseNearbyResponse.class);
        if (cached != null) {
            return Response.ok(cached).build();
        }

        // 경유지 이름은 '동을 못 찾았을 때'의 폴백으로만 쓰인다 — 랜드마크명은
        // "잠수교 맛집"이 성수동 결과를 주는 식으로 네이버가 엉뚱하게 해석할 때가 있다.
        List<NearbyPlace> items = nearbyPlaceService.around(lat, lng, radiusM, basedOn, 8);
        CourseNearbyResponse body =
                new CourseNearbyResponse(canonicalId, basedOn, lat, lng, radiusM, items.size(), items);
        if (!items.isEmpty()) { // 실패로 빈 응답이 하루 동안 굳는 것을 막는다
            cache.put(cacheKey, body, Duration.ofHours(26));
        }
        return Response.ok(body).build();
    }
}
