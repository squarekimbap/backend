package com.tourapi;

import com.tourapi.lib.UpstreamException;
import com.tourapi.model.RouteOptionsResponse;
import com.tourapi.services.RunningGenerationRateLimiter;
import com.tourapi.services.RunningService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@QuarkusTest
class RouteOptionsAuthorizationTest {

    private static final String IDEMPOTENCY_KEY = "135a2e12-6189-4e76-ae3c-cb0dac7d11b2";

    @InjectMock
    RunningService runningService;

    @InjectMock
    RunningGenerationRateLimiter rateLimiter;

    @Test
    @TestSecurity(user = "limited-user", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "limited-user")})
    void 분당한도를넘으면_429() {
        when(rateLimiter.acquire(eq("limited-user"), eq(IDEMPOTENCY_KEY), anyString())).thenReturn(
                RunningGenerationRateLimiter.Reservation.limited(
                        RunningGenerationRateLimiter.Scope.MINUTE, 6, 60, 1_788_000_060L));

        RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[{\"lat\":37.56,\"lng\":126.97}],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(429)
                .header("Retry-After", "60")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-RateLimit-Scope", "minute")
                .body("error", equalTo("rate_limited"));
    }

    @Test
    @TestSecurity(user = "daily-limited-user", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "daily-limited-user")})
    void 일일3회를넘으면_KST자정까지_429() {
        when(rateLimiter.acquire(eq("daily-limited-user"), eq(IDEMPOTENCY_KEY), anyString())).thenReturn(
                RunningGenerationRateLimiter.Reservation.limited(
                        RunningGenerationRateLimiter.Scope.DAILY, 3, 3_600, 1_788_003_600L));

        RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[{\"lat\":37.56,\"lng\":126.97}],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(429)
                .header("Retry-After", "3600")
                .header("X-RateLimit-Scope", "daily")
                .header("X-RateLimit-Limit", "3")
                .header("X-RateLimit-Remaining", "0")
                .header("X-RateLimit-Reset", "1788003600")
                .body("error", equalTo("rate_limited"));
    }

    @Test
    @TestSecurity(user = "quota-success", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "quota-success")})
    void 성공응답에_일일잔여횟수를_내려준다() {
        var reservation = RunningGenerationRateLimiter.Reservation.allowed(
                3, 2, 1_788_003_600L, "quota#running-generation#quota-success#20260901",
                "f162d9c4-a817-48c1-a9b8-4581885733c9");
        when(rateLimiter.acquire(eq("quota-success"), eq(IDEMPOTENCY_KEY), anyString()))
                .thenReturn(reservation);
        when(rateLimiter.complete(eq(reservation), any())).thenReturn(true);
        when(runningService.routeOptions(any(double[].class), anyList(), anyList(),
                eq("loop"), anyDouble(), anyLong()))
                .thenReturn(new RouteOptionsResponse("loop", 3, 0, List.of()));

        RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[{\"lat\":37.56,\"lng\":126.97}],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(200)
                .header("X-RateLimit-Scope", "daily")
                .header("X-RateLimit-Limit", "3")
                .header("X-RateLimit-Remaining", "2")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-RateLimit-Reset", "1788003600");
    }

    @Test
    @TestSecurity(user = "quota-remaining-unknown", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "quota-remaining-unknown")})
    void 성공했지만잔여량조회가불명확하면_remaining헤더를생략한다() {
        var reservation = RunningGenerationRateLimiter.Reservation.owned(
                3, -1, 1_788_003_600L,
                "quota#running-generation#quota-remaining-unknown#20260901",
                "ledger", "reservation", "owner");
        when(rateLimiter.acquire(eq("quota-remaining-unknown"), eq(IDEMPOTENCY_KEY), anyString()))
                .thenReturn(reservation);
        when(rateLimiter.complete(eq(reservation), any())).thenReturn(true);
        when(runningService.routeOptions(any(double[].class), anyList(), anyList(),
                eq("loop"), anyDouble(), anyLong()))
                .thenReturn(new RouteOptionsResponse("loop", 3, 0, List.of()));

        RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[{\"lat\":37.56,\"lng\":126.97}],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(200)
                .header("X-RateLimit-Scope", "daily")
                .header("X-RateLimit-Limit", "3")
                .header("X-RateLimit-Remaining", org.hamcrest.Matchers.nullValue())
                .header("X-RateLimit-Reset", "1788003600");
    }

    @Test
    @TestSecurity(user = "quota-refund", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "quota-refund")})
    void 업스트림실패면_일일사용권을_환불한다() {
        var reservation = RunningGenerationRateLimiter.Reservation.allowed(
                3, 1, 1_788_003_600L, "quota#running-generation#quota-refund#20260901",
                "7310d26c-7e1f-4805-8160-dd02d78e1c61");
        when(rateLimiter.acquire(eq("quota-refund"), eq(IDEMPOTENCY_KEY), anyString()))
                .thenReturn(reservation);
        when(runningService.routeOptions(any(double[].class), anyList(), anyList(),
                eq("loop"), anyDouble(), anyLong())).thenThrow(new UpstreamException("TMAP 실패"));

        RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[{\"lat\":37.56,\"lng\":126.97}],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(502)
                .header("Idempotency-Key", IDEMPOTENCY_KEY);

        verify(rateLimiter).refund(reservation);
    }

    @Test
    @TestSecurity(user = "quota-internal-error", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "quota-internal-error")})
    void 내부생성실패도_일일사용권을_환불한다() {
        var reservation = RunningGenerationRateLimiter.Reservation.allowed(
                3, 1, 1_788_003_600L,
                "quota#running-generation#quota-internal-error#20260901",
                "88af3b5d-9581-4574-9158-14891a38626d");
        when(rateLimiter.acquire(eq("quota-internal-error"), eq(IDEMPOTENCY_KEY), anyString()))
                .thenReturn(reservation);
        when(runningService.routeOptions(any(double[].class), anyList(), anyList(),
                eq("loop"), anyDouble(), anyLong())).thenThrow(new IllegalStateException("boom"));

        RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[{\"lat\":37.56,\"lng\":126.97}],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(500);

        verify(rateLimiter).refund(reservation);
    }

    @Test
    @TestSecurity(user = "bad-request-user", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "bad-request-user")})
    void 잘못된요청은_일일횟수를예약하지않는다() {
        RestAssured.given().contentType(ContentType.JSON)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[],\"selectedWaypoints\":[],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(400);

        verify(rateLimiter, never()).acquire(eq("bad-request-user"), anyString(), anyString());
    }

    @Test
    @TestSecurity(user = "quota-backend", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "quota-backend")})
    void 횟수저장소장애는_503으로구분한다() {
        when(rateLimiter.acquire(eq("quota-backend"), eq(IDEMPOTENCY_KEY), anyString())).thenReturn(
                RunningGenerationRateLimiter.Reservation.unavailable(1_788_000_060L));

        RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[{\"lat\":37.56,\"lng\":126.97}],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(503)
                .header("Retry-After", "60")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-RateLimit-Scope", "backend")
                .body("error", equalTo("quota_unavailable"));
    }

    @Test
    @TestSecurity(user = "idempotency-running", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "idempotency-running")})
    void 같은요청이진행중이면_서비스재실행없이409() {
        when(rateLimiter.acquire(eq("idempotency-running"), eq(IDEMPOTENCY_KEY), anyString()))
                .thenReturn(RunningGenerationRateLimiter.Reservation.limited(
                        RunningGenerationRateLimiter.Scope.IDEMPOTENCY, 3, 8, 1_788_000_008L));

        RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[{\"lat\":37.56,\"lng\":126.97}],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(409)
                .header("Retry-After", "8")
                .header("X-RateLimit-Scope", "idempotency")
                .body("error", equalTo("idempotency_in_progress"));

        verify(runningService, never()).routeOptions(any(double[].class), anyList(), anyList(),
                anyString(), anyDouble(), anyLong());
    }

    @Test
    @TestSecurity(user = "idempotency-replay", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "idempotency-replay")})
    void 완료된같은요청은_서비스재실행없이저장응답을반환한다() {
        var replay = RunningGenerationRateLimiter.Reservation.replayed(
                3, 2, 1_788_003_600L, "quota", "ledger", "reservation",
                "{\"shape\":\"loop\",\"optionCount\":0,\"storyCount\":0,\"options\":[]}");
        when(rateLimiter.acquire(eq("idempotency-replay"), eq(IDEMPOTENCY_KEY), anyString()))
                .thenReturn(replay);

        RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[{\"lat\":37.56,\"lng\":126.97}],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(200)
                .header("X-RateLimit-Remaining", "2")
                .body("shape", equalTo("loop"));

        verify(runningService, never()).routeOptions(any(double[].class), anyList(), anyList(),
                anyString(), anyDouble(), anyLong());
    }

    @Test
    @TestSecurity(user = "idempotency-expired", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "idempotency-expired")})
    void 저장된성공응답이만료된같은키는_서비스재실행없이410() {
        when(rateLimiter.acquire(eq("idempotency-expired"), eq(IDEMPOTENCY_KEY), anyString()))
                .thenReturn(RunningGenerationRateLimiter.Reservation.expired());

        RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[{\"lat\":37.56,\"lng\":126.97}],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(410)
                .header("X-RateLimit-Scope", "idempotency_expired")
                .body("error", equalTo("idempotency_result_expired"));

        verify(runningService, never()).routeOptions(any(double[].class), anyList(), anyList(),
                anyString(), anyDouble(), anyLong());
    }

    @Test
    @TestSecurity(user = "ledger-write-failure", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "ledger-write-failure")})
    void 생성성공응답을원장에확정하지못하면_503() {
        var reservation = RunningGenerationRateLimiter.Reservation.allowed(
                3, 2, 1_788_003_600L, "quota", "reservation");
        when(rateLimiter.acquire(eq("ledger-write-failure"), eq(IDEMPOTENCY_KEY), anyString()))
                .thenReturn(reservation);
        when(runningService.routeOptions(any(double[].class), anyList(), anyList(),
                eq("loop"), anyDouble(), anyLong()))
                .thenReturn(new RouteOptionsResponse("loop", 0, 0, List.of()));
        when(rateLimiter.complete(eq(reservation), any())).thenReturn(false);

        RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[{\"lat\":37.56,\"lng\":126.97}],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(503)
                .body("error", equalTo("idempotency_unavailable"));
    }

    @Test
    @TestSecurity(user = "invalid-idempotency-key", roles = {})
    @JwtSecurity(claims = {@Claim(key = "sub", value = "invalid-idempotency-key")})
    void 멱등키가UUID형식이아니면_차감없이400() {
        RestAssured.given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "same-key-forever")
                .body("{\"start\":{\"lat\":37.5665,\"lng\":126.978},"
                        + "\"candidateWaypoints\":[{\"lat\":37.56,\"lng\":126.97}],"
                        + "\"targetDistanceKm\":3}")
                .when().post("/v1/running/route-options")
                .then().statusCode(400)
                .body("error", equalTo("bad_request"));

        verify(rateLimiter, never()).acquire(eq("invalid-idempotency-key"),
                anyString(), anyString());
    }
}
