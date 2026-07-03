package com.tourapi.routes;

import com.tourapi.lib.UpstreamException;
import com.tourapi.model.ApiError;
import com.tourapi.model.CandidatesResponse;
import com.tourapi.model.RoutesRequest;
import com.tourapi.model.RoutesResponse;
import com.tourapi.model.RunningCandidatesRequest;
import com.tourapi.model.WaypointDto;
import com.tourapi.services.RunningService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.List;

/** 러닝 추천 라우트. 얇게: 검증 → 서비스 위임 → 상태코드 매핑. TMAP/Google 키는 절대 노출 안 됨. */
@Path("/v1/running")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "running", description = "러닝 코스 추천 (Phase A 후보 → 사용자 선택 → Phase B 코스)")
public class RunningResource {

    private static final Logger LOG = Logger.getLogger(RunningResource.class);

    @Inject
    RunningService runningService;

    @POST
    @Path("/candidates")
    @Operation(summary = "경유지 후보 (Phase A)",
            description = "설문(출발좌표·희망거리·형태)에 맞는 주변 관광지를 집중률 순위와 매칭해 후보로 반환한다.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "경유지 후보(집중률 순위 우선)",
                    content = @Content(schema = @Schema(implementation = CandidatesResponse.class))),
            @APIResponse(responseCode = "400", description = "잘못된 요청",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "502", description = "업스트림 오류",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public Response candidates(RunningCandidatesRequest req) {
        double lat;
        double lng;
        double distanceKm;
        String shape;
        int count;
        try {
            if (req == null) {
                throw new BadParam("요청 본문 필요");
            }
            lat = require("lat", req.lat(), -90, 90);
            lng = require("lng", req.lng(), -180, 180);
            distanceKm = require("distanceKm", req.distanceKm(), 0.5, 50);
            shape = shapeOf(req.shape());
            count = req.count() == null ? 10 : Math.max(1, Math.min(30, req.count()));
        } catch (BadParam e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("bad_request", e.getMessage())).build();
        }

        try {
            return Response.ok(runningService.candidates(lat, lng, distanceKm, shape, count)).build();
        } catch (UpstreamException e) {
            LOG.warnf("candidates upstream 실패: %s", e.getMessage());
            return Response.status(502).entity(new ApiError("upstream_error", e.getMessage())).build();
        } catch (Exception e) {
            LOG.error("candidates 처리 중 예외", e);
            return Response.status(500).entity(new ApiError("internal_error", "일시적 오류")).build();
        }
    }

    @POST
    @Path("/routes")
    @Operation(summary = "코스 추천 (Phase B)",
            description = "선택한 경유지(1~5개)로 순서 후보(선택/역순/근접)를 만들어 TMAP 보행 경로 + 고도 난이도를 계산, 코스 최대 3개를 반환한다.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "코스 목록(최대 3)",
                    content = @Content(schema = @Schema(implementation = RoutesResponse.class))),
            @APIResponse(responseCode = "400", description = "잘못된 요청",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "502", description = "업스트림(TMAP/Elevation) 오류",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public Response routes(RoutesRequest req) {
        double[] start;
        List<WaypointDto> waypoints;
        String shape;
        Double targetKm;
        try {
            if (req == null || req.start() == null) {
                throw new BadParam("start 필요");
            }
            start = new double[]{
                    require("start.lat", req.start().lat(), -90, 90),
                    require("start.lng", req.start().lng(), -180, 180)};
            waypoints = req.waypoints();
            if (waypoints == null || waypoints.isEmpty()) {
                throw new BadParam("waypoints 1개 이상 필요");
            }
            if (waypoints.size() > 5) {
                throw new BadParam("waypoints 최대 5개 (TMAP 경유지 제한)");
            }
            for (int i = 0; i < waypoints.size(); i++) {
                require("waypoints[" + i + "].lat", waypoints.get(i).lat(), -90, 90);
                require("waypoints[" + i + "].lng", waypoints.get(i).lng(), -180, 180);
            }
            shape = shapeOf(req.shape());
            targetKm = req.targetDistanceKm();
            if (targetKm != null && (targetKm < 0.5 || targetKm > 60)) {
                throw new BadParam("targetDistanceKm 범위(0.5~60) 벗어남: " + targetKm);
            }
        } catch (BadParam e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("bad_request", e.getMessage())).build();
        }

        try {
            return Response.ok(runningService.routes(start, waypoints, shape, targetKm)).build();
        } catch (UpstreamException e) {
            LOG.warnf("routes upstream 실패: %s", e.getMessage());
            return Response.status(502).entity(new ApiError("upstream_error", e.getMessage())).build();
        } catch (Exception e) {
            LOG.error("routes 처리 중 예외", e);
            return Response.status(500).entity(new ApiError("internal_error", "일시적 오류")).build();
        }
    }

    // ── 검증 헬퍼 ───────────────────────────────────────────────

    private static double require(String name, Double v, double min, double max) {
        if (v == null) {
            throw new BadParam(name + " 필수");
        }
        if (v < min || v > max) {
            throw new BadParam(name + " 범위(" + min + "~" + max + ") 벗어남: " + v);
        }
        return v;
    }

    private static String shapeOf(String v) {
        if (v == null || v.isBlank()) {
            return "loop";
        }
        String s = v.trim().toLowerCase();
        if (!s.equals("loop") && !s.equals("oneway")) {
            throw new BadParam("shape는 loop | oneway: " + v);
        }
        return s;
    }

    private static final class BadParam extends RuntimeException {
        BadParam(String m) {
            super(m);
        }
    }
}
