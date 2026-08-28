package com.tourapi.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.tourapi.lib.RankingCache;
import com.tourapi.model.ApiError;
import com.tourapi.model.CourseNearbyResponse;
import com.tourapi.model.NearbyPlace;
import com.tourapi.services.CourseCatalog;
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
 * body[]·deep[]·ops[]·unsure[]·poi[{n,d,photo}]·photo·photoTitle·photoLicense·city·region.
 */
@Path("/v1/courses")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "courses", description = "편집 코스 카탈로그 (지역별 수집 원고)")
public class CourseResource {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Inject
    CourseCatalog catalog;

    @Inject
    NearbyPlaceService nearbyPlaceService;

    @Inject
    RankingCache cache;

    @GET
    @Operation(summary = "코스 목록(요약)",
            description = "카드 렌더링용 요약 필드만 내려준다. city(도시명 '서울' 또는 cityId 'seoul')로 필터 가능. 상세는 GET /v1/courses/{id}.")
    @APIResponse(responseCode = "200", description = "{count, items:[{id,n,city,km,min,lv,mood,tags,headline,subhead,photo,...}]}")
    public Response list(
            @Parameter(description = "도시 필터(도시명 또는 cityId). 없으면 전체", example = "서울")
            @QueryParam("city") String city) {
        ArrayNode items = catalog.list(city);
        return Response.ok(java.util.Map.of("count", items.size(), "items", items)).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "코스 상세",
            description = "id로 코스 전체 원고(본문·팁·경유지·사진)를 조회한다. 예: seoul-banpo-10k")
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

        // 기준점 = 좌표가 확인된 마지막 경유지(도착지에 가장 가깝다)
        JsonNode base = null;
        for (JsonNode poi : c.path("poi")) {
            if (poi.hasNonNull("lat") && poi.hasNonNull("lng")) {
                base = poi;
            }
        }
        if (base == null) {
            // 좌표를 못 찾은 코스(수집 원본에 좌표 미확보) — 빈 목록으로 조용히 내려간다
            return Response.ok(new CourseNearbyResponse(id, null, null, null, radiusM, 0, List.of()))
                    .build();
        }

        double lat = base.path("lat").asDouble();
        double lng = base.path("lng").asDouble();
        String basedOn = base.path("n").asText(null);
        String cacheKey = "nearby#" + id + "#" + radiusM + "#"
                + LocalDate.now(KST).format(DateTimeFormatter.BASIC_ISO_DATE);

        CourseNearbyResponse cached = cache.get(cacheKey, CourseNearbyResponse.class);
        if (cached != null) {
            return Response.ok(cached).build();
        }

        List<NearbyPlace> items = nearbyPlaceService.around(lat, lng, radiusM, basedOn, 8);
        CourseNearbyResponse body =
                new CourseNearbyResponse(id, basedOn, lat, lng, radiusM, items.size(), items);
        if (!items.isEmpty()) { // 실패로 빈 응답이 하루 동안 굳는 것을 막는다
            cache.put(cacheKey, body, Duration.ofHours(26));
        }
        return Response.ok(body).build();
    }
}
