package com.tourapi.routes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourapi.lib.UpstreamException;
import com.tourapi.model.ApiError;
import com.tourapi.model.CandidatesResponse;
import com.tourapi.model.CourseSummaryRequest;
import com.tourapi.model.CourseSummaryResponse;
import com.tourapi.model.RouteOptionsRequest;
import com.tourapi.model.RouteOptionsResponse;
import com.tourapi.model.RunningCandidatesRequest;
import com.tourapi.model.WaypointDto;
import com.tourapi.services.RunningGenerationRateLimiter;
import com.tourapi.services.RunningService;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 러닝 추천 라우트. 얇게: 검증 → 서비스 위임 → 상태코드 매핑. TMAP/Google 키는 절대 노출 안 됨. */
@Path("/v1/running")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "running", description = "러닝 코스 추천 (Phase A 후보 → 사용자 선택 → Phase B 코스)")
public class RunningResource {

    private static final Logger LOG = Logger.getLogger(RunningResource.class);

    @Inject
    RunningService runningService;

    @Inject
    RunningGenerationRateLimiter generationRateLimiter;

    @Inject
    JsonWebToken jwt;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "running.generation.deadline-ms", defaultValue = "22000")
    long generationDeadlineMs;

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
    @Path("/route-options")
    @Authenticated
    @Operation(summary = "화면 3 코스 선택지",
            description = "사용자가 고른 관광지를 모두 지나는 옵션과 희망 거리에 가까운 옵션을 만든다.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "관광지 우선/거리 우선 옵션",
                    content = @Content(schema = @Schema(implementation = RouteOptionsResponse.class))),
            @APIResponse(responseCode = "400", description = "잘못된 요청",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "401", description = "로그인 필요"),
            @APIResponse(responseCode = "409", description = "같은 멱등 키의 생성 요청 처리 중",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "410", description = "멱등 응답 보관 기간 만료",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "429", description = "사용자별 분당 또는 KST 일일 생성 한도 초과",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "503", description = "생성 횟수 저장소 일시 장애",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @APIResponse(responseCode = "502", description = "TMAP/Elevation 오류",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public Response routeOptions(RouteOptionsRequest req,
                                 @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER,
                                         description = "생성 시 만든 UUID. 같은 요청 재시도에는 같은 값 사용")
                                 @HeaderParam("Idempotency-Key") String suppliedIdempotencyKey) {
        long deadline = generationDeadline();
        double[] start;
        List<WaypointDto> selected;
        List<WaypointDto> candidates;
        String shape;
        double targetKm;
        String idempotencyKey;
        try {
            if (req == null || req.start() == null) {
                throw new BadParam("start 필요");
            }
            start = new double[]{
                    require("start.lat", req.start().lat(), -90, 90),
                    require("start.lng", req.start().lng(), -180, 180)};
            selected = req.selectedWaypoints() == null ? List.of() : req.selectedWaypoints();
            candidates = req.candidateWaypoints() == null ? List.of() : req.candidateWaypoints();
            if (selected.size() > 5) {
                throw new BadParam("selectedWaypoints 최대 5개");
            }
            if (candidates.size() > 30) {
                throw new BadParam("candidateWaypoints 최대 30개");
            }
            if (selected.isEmpty() && candidates.isEmpty()) {
                throw new BadParam("selectedWaypoints 또는 candidateWaypoints 필요");
            }
            validateWaypoints("selectedWaypoints", selected);
            validateWaypoints("candidateWaypoints", candidates);
            shape = shapeOf(req.shape());
            targetKm = require("targetDistanceKm", req.targetDistanceKm(), 0.5, 60);
            idempotencyKey = idempotencyKey(suppliedIdempotencyKey);
        } catch (BadParam e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("bad_request", e.getMessage())).build();
        }

        RunningGenerationRateLimiter.Reservation reservation = generationRateLimiter.acquire(
                jwt.getSubject(), idempotencyKey, requestFingerprint("route-options", req));
        Response limited = rateLimitResponse(reservation, idempotencyKey);
        if (limited != null) {
            return limited;
        }
        if (reservation.replayed()) {
            return generationHeaders(Response.ok(
                    reservation.replayPayload(), MediaType.APPLICATION_JSON_TYPE),
                    reservation, idempotencyKey).build();
        }

        try {
            RouteOptionsResponse response = runningService.routeOptions(
                    start, selected, candidates, shape, targetKm, deadline);
            if (!generationRateLimiter.complete(reservation, response)) {
                return generationStateUnavailable(idempotencyKey);
            }
            return generationHeaders(Response.ok(response), reservation, idempotencyKey).build();
        } catch (UpstreamException e) {
            generationRateLimiter.refund(reservation);
            LOG.warnf("route-options upstream 실패: %s", e.getMessage());
            return Response.status(502).header("Idempotency-Key", idempotencyKey)
                    .entity(new ApiError("upstream_error", e.getMessage())).build();
        } catch (Exception e) {
            generationRateLimiter.refund(reservation);
            LOG.error("route-options 처리 중 예외", e);
            return Response.status(500).header("Idempotency-Key", idempotencyKey)
                    .entity(new ApiError("internal_error", "일시적 오류")).build();
        }
    }

    @POST
    @Path("/summary")
    @Operation(summary = "화면 4 코스 총정리",
            description = "선택한 코스에 도착지 주변 맛집·카페를 최대 8곳 붙인다. "
                    + "부족하면 요청 반경에서 2.5km, 5km까지 자동 확장한다.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "최종 코스 총정리",
                    content = @Content(schema = @Schema(implementation = CourseSummaryResponse.class))),
            @APIResponse(responseCode = "400", description = "잘못된 요청",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public Response summary(CourseSummaryRequest req) {
        int radius;
        try {
            if (req == null || req.option() == null || req.option().course() == null
                    || req.option().course().path() == null || req.option().course().path().isEmpty()) {
                throw new BadParam("option.course.path 필요");
            }
            for (int i = 0; i < req.option().course().path().size(); i++) {
                double[] point = req.option().course().path().get(i);
                if (point == null || point.length < 2) {
                    throw new BadParam("option.course.path[" + i + "] 좌표 형식 오류");
                }
                require("option.course.path[" + i + "][0]", point[0], -90, 90);
                require("option.course.path[" + i + "][1]", point[1], -180, 180);
            }
            radius = req.nearbyRadiusM() == null ? 500 : req.nearbyRadiusM();
            if (radius < 100 || radius > 2000) {
                throw new BadParam("nearbyRadiusM 범위(100~2000) 벗어남: " + radius);
            }
        } catch (BadParam e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("bad_request", e.getMessage())).build();
        }

        try {
            return Response.ok(runningService.summary(req.option(), radius)).build();
        } catch (Exception e) {
            LOG.error("summary 처리 중 예외", e);
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

    private static void validateWaypoints(String field, List<WaypointDto> waypoints) {
        for (int i = 0; i < waypoints.size(); i++) {
            WaypointDto waypoint = waypoints.get(i);
            if (waypoint == null) {
                throw new BadParam(field + "[" + i + "] 값 필요");
            }
            require(field + "[" + i + "].lat", waypoint.lat(), -90, 90);
            require(field + "[" + i + "].lng", waypoint.lng(), -180, 180);
        }
    }

    private long generationDeadline() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(generationDeadlineMs);
    }

    private String requestFingerprint(String endpoint, Object request) {
        try {
            return endpoint + "\n" + mapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("코스 생성 요청 지문 생성 실패", e);
        }
    }

    private static String idempotencyKey(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String value = supplied.trim();
        try {
            if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("canonical UUID 필요");
            }
        } catch (IllegalArgumentException e) {
            throw new BadParam("Idempotency-Key는 UUID 형식이어야 합니다");
        }
        return value.toLowerCase();
    }

    private Response rateLimitResponse(RunningGenerationRateLimiter.Reservation reservation,
                                       String idempotencyKey) {
        if (reservation.allowed()) {
            return null;
        }
        if (reservation.scope() == RunningGenerationRateLimiter.Scope.IDEMPOTENCY_EXPIRED) {
            return Response.status(410)
                    .header("Idempotency-Key", idempotencyKey)
                    .header("X-RateLimit-Scope", reservation.scope().headerValue())
                    .entity(new ApiError("idempotency_result_expired",
                            "이 생성 요청의 저장 결과가 만료됐습니다. 새 요청으로 다시 생성해주세요"))
                    .build();
        }
        if (reservation.scope() == RunningGenerationRateLimiter.Scope.IDEMPOTENCY) {
            return Response.status(409)
                    .header("Idempotency-Key", idempotencyKey)
                    .header("X-RateLimit-Scope", reservation.scope().headerValue())
                    .header("Retry-After", Long.toString(reservation.retryAfterSeconds()))
                    .entity(new ApiError("idempotency_in_progress",
                            "같은 코스 생성 요청을 처리 중입니다. 잠시 후 다시 시도해주세요"))
                    .build();
        }
        if (reservation.scope() == RunningGenerationRateLimiter.Scope.BACKEND) {
            return generationHeaders(Response.status(503), reservation, idempotencyKey)
                    .header("Retry-After", Long.toString(reservation.retryAfterSeconds()))
                    .entity(new ApiError("quota_unavailable",
                            "코스 생성 횟수를 확인할 수 없습니다. 잠시 후 다시 시도해주세요"))
                    .build();
        }
        String message = reservation.scope() == RunningGenerationRateLimiter.Scope.DAILY
                ? "오늘 코스 생성 " + reservation.limit()
                    + "회를 모두 사용했습니다. 한국시간 자정 이후 다시 이용할 수 있습니다"
                : "실시간 코스 생성은 분당 " + reservation.limit() + "회까지 가능";
        return generationHeaders(Response.status(429), reservation, idempotencyKey)
                .header("Retry-After", Long.toString(reservation.retryAfterSeconds()))
                .entity(new ApiError("rate_limited", message)).build();
    }

    private Response generationStateUnavailable(String idempotencyKey) {
        var unavailable = RunningGenerationRateLimiter.Reservation.unavailable(
                System.currentTimeMillis() / 1000 + 60);
        return generationHeaders(Response.status(503), unavailable, idempotencyKey)
                .header("Retry-After", "60")
                .entity(new ApiError("idempotency_unavailable",
                        "생성 결과를 안전하게 저장하지 못했습니다. 같은 요청으로 다시 시도해주세요"))
                .build();
    }

    private static Response.ResponseBuilder generationHeaders(
            Response.ResponseBuilder response,
            RunningGenerationRateLimiter.Reservation reservation,
            String idempotencyKey) {
        Response.ResponseBuilder headers = response
                .header("Idempotency-Key", idempotencyKey)
                .header("X-RateLimit-Scope", reservation.scope().headerValue())
                .header("X-RateLimit-Limit", Integer.toString(reservation.limit()))
                .header("X-RateLimit-Reset", Long.toString(reservation.resetEpochSeconds()));
        if (reservation.remainingKnown()) {
            headers.header("X-RateLimit-Remaining", Integer.toString(reservation.remaining()));
        }
        return headers;
    }

    private static final class BadParam extends RuntimeException {
        BadParam(String m) {
            super(m);
        }
    }
}
