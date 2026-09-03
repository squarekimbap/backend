package com.tourapi.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourapi.lib.RankingCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/** 로그인 사용자별 실시간 러닝 코스 생성 호출량을 분·일 단위로 통합 제한한다. */
@ApplicationScoped
public class RunningGenerationRateLimiter {

    private static final Logger LOG = Logger.getLogger(RunningGenerationRateLimiter.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.BASIC_ISO_DATE;

    @Inject
    RankingCache cache;

    @Inject
    ObjectMapper mapper;

    /** 분·일 제한은 관리 화면에서 바꿀 수 있다. 저장된 값이 없으면 배포 설정값을 쓴다. */
    @Inject
    AdminSettings settings;

    @ConfigProperty(name = "running.generation.idempotency-lease-seconds", defaultValue = "30")
    long idempotencyLeaseSeconds;

    @ConfigProperty(name = "running.generation.idempotency-response-ttl-minutes", defaultValue = "5")
    long idempotencyResponseTtlMinutes;

    Clock clock = Clock.systemUTC();

    /**
     * 먼저 짧은 시간의 재시도 폭주를 막고, 통과한 요청만 KST 날짜별 사용권을 예약한다.
     * 정상 응답이면 예약을 확정하고, 외부 API/서버 오류면 {@link #refund(Reservation)}로 돌려준다.
     */
    /**
     * 앱의 멱등 키와 요청 지문을 묶은 안정적인 예약 ID로 일일 사용권을 예약한다.
     * 같은 키라도 요청 내용이 다르면 별도 생성으로 계산한다.
     */
    public Reservation acquire(String userId, String idempotencyKey, String requestFingerprint) {
        int dailyLimit = settings.dailyLimit();
        int limitPerMinute = settings.perMinuteLimit();
        Instant now = clock.instant();
        if (userId == null || userId.isBlank()) {
            return Reservation.limited(Scope.MINUTE, limitPerMinute, 60,
                    now.plusSeconds(60).getEpochSecond());
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || requestFingerprint == null || requestFingerprint.isBlank()) {
            throw new IllegalArgumentException("멱등 키와 요청 지문 필요");
        }

        String reservationId = reservationId(userId, idempotencyKey, requestFingerprint);
        String ownerId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();
        long minute = now.getEpochSecond() / 60;
        // 분당 보호는 HTTP 시도별로 계산한다. 500/502 환불 뒤 같은 멱등 요청이 실제
        // 생성 서비스를 다시 실행하는 경우에도 매번 상한에 포함돼 유료 API를 보호한다.
        RankingCache.CounterReservation minuteResult = cache.reserve(
                "rate#running-generation#" + userId + "#" + minute,
                attemptId, limitPerMinute, Duration.ofMinutes(2));
        if (minuteResult.status() == RankingCache.CounterStatus.UNAVAILABLE) {
            return Reservation.unavailable(now.plusSeconds(60).getEpochSecond());
        }
        if (minuteResult.status() == RankingCache.CounterStatus.LIMITED) {
            long retryAfter = Math.max(1, 60 - now.getEpochSecond() % 60);
            return Reservation.limited(Scope.MINUTE, limitPerMinute, retryAfter,
                    now.plusSeconds(retryAfter).getEpochSecond());
        }

        ZonedDateTime kstNow = now.atZone(KST);
        ZonedDateTime nextMidnight = kstNow.toLocalDate().plusDays(1).atStartOfDay(KST);
        long retryAfter = Math.max(1, Duration.between(now, nextMidnight.toInstant()).toSeconds());
        // 날짜가 키에 포함돼 자정에 즉시 새 카운터로 넘어간다. TTL은 DynamoDB의 지연 삭제를
        // 고려해 하루 더 보관한 뒤 청소하는 용도일 뿐, 초기화 시각을 결정하지 않는다.
        Duration storageTtl = Duration.ofSeconds(retryAfter).plusDays(1);
        String dailyKey = "quota#running-generation#" + userId + "#"
                + DAY_KEY.format(kstNow.toLocalDate());
        // 같은 멱등 요청은 KST 자정을 넘어 재시도해도 같은 원장을 찾아야 한다.
        // 날짜는 사용량 쿼터에만 포함하고 실행 원장은 사용자+요청 지문으로 고정한다.
        String ledgerKey = "generation#" + userId + "#" + reservationId;
        RankingCache.GenerationClaim claim = cache.claimGeneration(
                dailyKey, ledgerKey, reservationId, ownerId, dailyLimit, storageTtl,
                Duration.ofSeconds(idempotencyLeaseSeconds));
        String claimedQuotaKey = claim.quotaKey() == null ? dailyKey : claim.quotaKey();
        return switch (claim.status()) {
            case OWNER -> Reservation.owned(dailyLimit, remaining(dailyLimit, claim.used()),
                    nextMidnight.toEpochSecond(), claimedQuotaKey, ledgerKey,
                    reservationId, ownerId);
            case REPLAY -> Reservation.replayed(dailyLimit, remaining(dailyLimit, claim.used()),
                    nextMidnight.toEpochSecond(), claimedQuotaKey, ledgerKey,
                    reservationId, claim.payload());
            case IN_PROGRESS -> Reservation.limited(Scope.IDEMPOTENCY, dailyLimit, 8,
                    now.plusSeconds(8).getEpochSecond());
            case EXPIRED -> Reservation.expired();
            case LIMITED -> Reservation.limited(Scope.DAILY, dailyLimit, retryAfter,
                    nextMidnight.toEpochSecond());
            case UNAVAILABLE -> Reservation.unavailable(now.plusSeconds(60).getEpochSecond());
        };
    }

    private static int remaining(int limit, int used) {
        return used == RankingCache.UNKNOWN_USED ? -1 : Math.max(0, limit - used);
    }

    /**
     * 소유 실행의 성공 응답을 원장에 저장한다. 저장된 뒤에만 200을 내려 중복 요청이
     * 생성 서비스를 다시 실행하지 않고 같은 JSON을 재생할 수 있게 한다.
     */
    public boolean complete(Reservation reservation, Object response) {
        if (reservation == null || !reservation.ownsExecution()) {
            return false;
        }
        try {
            String payload = mapper.writeValueAsString(response);
            if (cache.completeGeneration(reservation.ledgerKey(), reservation.ownerId(), payload,
                    Duration.ofMinutes(idempotencyResponseTtlMinutes))) {
                return true;
            }
            LOG.errorf("코스 생성 성공 응답 원장 확정 실패: ledger=%s",
                    reservation.ledgerKey());
            return false;
        } catch (JsonProcessingException e) {
            LOG.error("코스 생성 성공 응답 직렬화 실패", e);
            return false;
        }
    }

    /**
     * 생성이 500/502로 끝나면 현재 소유자의 원장과 일일 사용권을 한 번의 트랜잭션으로
     * 정리한다. 소유권이 바뀐 중복 실행은 기존 성공분을 환불할 수 없다.
     */
    public boolean refund(Reservation reservation) {
        if (reservation == null || !reservation.ownsExecution()) {
            return true;
        }
        boolean refunded = cache.failGeneration(reservation.dailyKey(), reservation.ledgerKey(),
                reservation.reservationId(), reservation.ownerId());
        if (!refunded) {
            LOG.errorf("코스 생성 실패 상태/사용권 정리 미확정: ledger=%s",
                    reservation.ledgerKey());
        }
        return refunded;
    }

    static String reservationId(String userId, String idempotencyKey, String requestFingerprint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((userId + "\n" + idempotencyKey + "\n"
                    + requestFingerprint).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    public enum Scope {
        MINUTE("minute"), DAILY("daily"), IDEMPOTENCY("idempotency"),
        IDEMPOTENCY_EXPIRED("idempotency_expired"), BACKEND("backend");

        private final String headerValue;

        Scope(String headerValue) {
            this.headerValue = headerValue;
        }

        public String headerValue() {
            return headerValue;
        }
    }

    /** 요청 1회의 제한 판정과 성공 응답에 노출할 일일 잔여량. */
    public record Reservation(
            boolean allowed,
            Scope scope,
            int limit,
            int remaining,
            long retryAfterSeconds,
            long resetEpochSeconds,
            String dailyKey,
            String ledgerKey,
            String reservationId,
            String ownerId,
            String replayPayload
    ) {
        public static Reservation allowed(int limit, int remaining, long resetEpochSeconds,
                                          String dailyKey, String reservationId) {
            return owned(limit, remaining, resetEpochSeconds, dailyKey,
                    "ledger#" + reservationId, reservationId, "owner#" + reservationId);
        }

        public static Reservation owned(int limit, int remaining, long resetEpochSeconds,
                                        String dailyKey, String ledgerKey,
                                        String reservationId, String ownerId) {
            return new Reservation(true, Scope.DAILY, limit, normalizeRemaining(remaining), 0,
                    resetEpochSeconds, dailyKey, ledgerKey, reservationId, ownerId, null);
        }

        public static Reservation replayed(int limit, int remaining, long resetEpochSeconds,
                                           String dailyKey, String ledgerKey,
                                           String reservationId, String replayPayload) {
            return new Reservation(true, Scope.DAILY, limit, normalizeRemaining(remaining), 0,
                    resetEpochSeconds, dailyKey, ledgerKey, reservationId, null, replayPayload);
        }

        public static Reservation limited(Scope scope, int limit, long retryAfterSeconds,
                                          long resetEpochSeconds) {
            return new Reservation(false, scope, limit, 0, Math.max(1, retryAfterSeconds),
                    resetEpochSeconds, null, null, null, null, null);
        }

        public static Reservation unavailable(long retryAtEpochSeconds) {
            return new Reservation(false, Scope.BACKEND, 0, 0, 60,
                    retryAtEpochSeconds, null, null, null, null, null);
        }

        public static Reservation expired() {
            return new Reservation(false, Scope.IDEMPOTENCY_EXPIRED, 0, 0, 0,
                    0, null, null, null, null, null);
        }

        public boolean ownsExecution() {
            return allowed && ownerId != null && replayPayload == null;
        }

        public boolean replayed() {
            return allowed && replayPayload != null;
        }

        public boolean remainingKnown() {
            return remaining >= 0;
        }

        private static int normalizeRemaining(int remaining) {
            return remaining < 0 ? -1 : remaining;
        }
    }
}
