package com.tourapi.routes;

import com.tourapi.lib.UpstreamException;
import com.tourapi.model.ApiError;
import com.tourapi.model.PlacesResponse;
import com.tourapi.model.PopularResponse;
import com.tourapi.services.TourService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/** 관광 도메인 라우트. 얇게 유지한다: 파라미터 검증 → 서비스 위임 → 상태코드 매핑. */
@Path("/v1/tour")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "tour", description = "관광 정보 (공공데이터 TourAPI 프록시)")
public class TourResource {

    private static final Logger LOG = Logger.getLogger(TourResource.class);

    @Inject
    TourService tourService;

    @ConfigProperty(name = "tour.api.default-radius", defaultValue = "2000")
    int defaultRadius;
    @ConfigProperty(name = "tour.api.max-radius", defaultValue = "20000")
    int maxRadius;
    @ConfigProperty(name = "tour.api.default-size", defaultValue = "20")
    int defaultSize;
    @ConfigProperty(name = "tour.api.max-size", defaultValue = "100")
    int maxSize;

    /**
     * 좌표 주변 관광 정보.
     * 예: /v1/tour/places?lat=37.5665&lng=126.9780&radius=2000&type=12&page=1&size=20
     */
    @GET
    @Path("/places")
    @Operation(summary = "좌표 주변 관광 정보",
            description = "위치기반 관광정보(TourAPI locationBasedList2)를 거리순으로 반환한다.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "거리순 관광 목록",
                    content = @Content(schema = @Schema(implementation = PlacesResponse.class))),
            @APIResponse(responseCode = "400", description = "잘못된 파라미터",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "502", description = "업스트림(TourAPI) 오류",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public Response places(
            @Parameter(description = "위도(WGS84)", required = true, example = "37.5665")
            @QueryParam("lat") String latStr,
            @Parameter(description = "경도(WGS84)", required = true, example = "126.9780")
            @QueryParam("lng") String lngStr,
            @Parameter(description = "반경(m). 기본 2000, 최대 20000", example = "2000")
            @QueryParam("radius") String radiusStr,
            @Parameter(description = "콘텐츠 타입(contentTypeId): 12관광지·14문화시설·15축제·25여행코스·28레포츠·32숙박·38쇼핑·39음식점", example = "12")
            @QueryParam("type") String typeStr,
            @Parameter(description = "페이지 번호(1부터). 기본 1", example = "1")
            @QueryParam("page") String pageStr,
            @Parameter(description = "페이지 크기. 기본 20, 최대 100", example = "20")
            @QueryParam("size") String sizeStr) {
        double lat;
        double lng;
        int radius;
        int page;
        int size;
        Integer type;
        try {
            lat = requireDouble("lat", latStr, -90, 90);
            lng = requireDouble("lng", lngStr, -180, 180);
            radius = clamp("radius", radiusStr, defaultRadius, 1, maxRadius);
            size = clamp("size", sizeStr, defaultSize, 1, maxSize);
            page = clamp("page", pageStr, 1, 1, Integer.MAX_VALUE);
            type = optionalInt("type", typeStr);
        } catch (BadParam e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("bad_request", e.getMessage())).build();
        }

        try {
            PlacesResponse body = tourService.nearbyPlaces(lat, lng, radius, type, page, size);
            return Response.ok(body).build();
        } catch (UpstreamException e) {
            LOG.warnf("places upstream 실패: %s", e.getMessage());
            return Response.status(502).entity(new ApiError("upstream_error", e.getMessage())).build();
        } catch (Exception e) {
            LOG.error("places 처리 중 예외", e);
            return Response.status(500).entity(new ApiError("internal_error", "일시적 오류")).build();
        }
    }

    /**
     * 좌표 주변 인기 관광지 순위(집중률, 30일 평균).
     * 예: /v1/tour/popular?lat=37.5665&lng=126.9780&size=20
     */
    @GET
    @Path("/popular")
    @Operation(summary = "좌표 주변 인기 관광지 순위(집중률)",
            description = "좌표를 시군구로 변환(locationBasedList2 법정동코드) 후, 관광지 집중률(tatsCnctrRatedList)을 향후 30일 평균 기준으로 정렬해 반환한다.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "집중률 순위",
                    content = @Content(schema = @Schema(implementation = PopularResponse.class))),
            @APIResponse(responseCode = "400", description = "잘못된 파라미터",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "502", description = "업스트림(TourAPI) 오류 / 지역 특정 실패",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public Response popular(
            @Parameter(description = "위도(WGS84)", required = true, example = "37.5665")
            @QueryParam("lat") String latStr,
            @Parameter(description = "경도(WGS84)", required = true, example = "126.9780")
            @QueryParam("lng") String lngStr,
            @Parameter(description = "반환 순위 개수. 기본 20, 최대 100", example = "20")
            @QueryParam("size") String sizeStr) {
        double lat;
        double lng;
        int size;
        try {
            lat = requireDouble("lat", latStr, -90, 90);
            lng = requireDouble("lng", lngStr, -180, 180);
            size = clamp("size", sizeStr, defaultSize, 1, maxSize);
        } catch (BadParam e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("bad_request", e.getMessage())).build();
        }

        try {
            PopularResponse body = tourService.popular(lat, lng, size);
            return Response.ok(body).build();
        } catch (UpstreamException e) {
            LOG.warnf("popular upstream 실패: %s", e.getMessage());
            return Response.status(502).entity(new ApiError("upstream_error", e.getMessage())).build();
        } catch (Exception e) {
            LOG.error("popular 처리 중 예외", e);
            return Response.status(500).entity(new ApiError("internal_error", "일시적 오류")).build();
        }
    }

    // ── 파라미터 파싱 (routes 계층 책임) ──────────────────────────

    private static double requireDouble(String name, String v, double min, double max) {
        if (v == null || v.isBlank()) {
            throw new BadParam(name + " 필수");
        }
        double d;
        try {
            d = Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new BadParam(name + " 숫자 아님: " + v);
        }
        if (d < min || d > max) {
            throw new BadParam(name + " 범위(" + min + "~" + max + ") 벗어남: " + d);
        }
        return d;
    }

    /** 값이 없으면 기본값, 범위를 벗어나면 min/max로 보정. 정수 아니면 400. */
    private static int clamp(String name, String v, int dflt, int min, int max) {
        if (v == null || v.isBlank()) {
            return dflt;
        }
        int n;
        try {
            n = Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new BadParam(name + " 정수 아님: " + v);
        }
        return Math.max(min, Math.min(max, n));
    }

    private static Integer optionalInt(String name, String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new BadParam(name + " 정수 아님: " + v);
        }
    }

    private static final class BadParam extends RuntimeException {
        BadParam(String m) {
            super(m);
        }
    }
}
