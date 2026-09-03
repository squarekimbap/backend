package com.tourapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.tourapi.lib.AdminStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 관리자가 화면에서 바꿀 수 있는 운영값. 저장된 값이 없으면 배포 설정값을 그대로 쓴다.
 *
 * <p>요청마다 DynamoDB를 읽지 않도록 짧게 기억한다. 다른 Lambda 실행 환경에는 최대
 * {@link #MEMO} 뒤에 퍼지므로, 저장 직후 잠깐은 컨테이너마다 값이 다를 수 있다.
 * 호출량 제한이라 그 정도 지연은 문제가 되지 않는다.
 *
 * <p>저장소가 죽으면 <b>배포 설정값으로 폴백</b>한다 — 제한을 못 읽었다고 코스 생성을
 * 통째로 막아버리면 장애가 커진다.
 */
@ApplicationScoped
public class AdminSettings {

    private static final Logger LOG = Logger.getLogger(AdminSettings.class);
    private static final Duration MEMO = Duration.ofSeconds(60);
    static final String KEY = "settings";
    /** 실수로 0이나 터무니없는 값을 넣어 서비스를 막거나 비용이 새지 않게 둔다. */
    static final int MAX_DAILY = 100;
    static final int MAX_PER_MINUTE = 60;

    @ConfigProperty(name = "running.generation.daily-limit", defaultValue = "3")
    int configuredDaily;

    @ConfigProperty(name = "running.generation.rate-limit-per-minute", defaultValue = "6")
    int configuredPerMinute;

    @Inject
    AdminStore store;

    private volatile JsonNode memo;
    private volatile long memoUntil;

    public int dailyLimit() {
        return read("dailyLimit", configuredDaily, MAX_DAILY);
    }

    public int perMinuteLimit() {
        return read("perMinuteLimit", configuredPerMinute, MAX_PER_MINUTE);
    }

    /** 화면에 뿌릴 현재값과 배포 기본값. 무엇이 바뀐 상태인지 사람이 알아야 한다. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dailyLimit", dailyLimit());
        m.put("perMinuteLimit", perMinuteLimit());
        m.put("defaultDailyLimit", configuredDaily);
        m.put("defaultPerMinuteLimit", configuredPerMinute);
        m.put("maxDailyLimit", MAX_DAILY);
        m.put("maxPerMinuteLimit", MAX_PER_MINUTE);
        return m;
    }

    /** null인 항목은 건드리지 않는다. 범위를 벗어나면 IllegalArgumentException. */
    public void update(Integer daily, Integer perMinute) {
        Map<String, Integer> next = new LinkedHashMap<>();
        next.put("dailyLimit", check(daily, dailyLimit(), MAX_DAILY, "일일 생성 횟수"));
        next.put("perMinuteLimit", check(perMinute, perMinuteLimit(), MAX_PER_MINUTE, "분당 호출"));
        store.put(KEY, next);
        invalidate();
        LOG.infof("운영값 변경: %s", next);
    }

    private static int check(Integer given, int current, int max, String label) {
        if (given == null) {
            return current;
        }
        if (given < 1 || given > max) {
            throw new IllegalArgumentException(label + "는 1 이상 " + max + " 이하여야 한다");
        }
        return given;
    }

    private int read(String field, int fallback, int max) {
        JsonNode saved = cached();
        if (saved == null || !saved.hasNonNull(field)) {
            return fallback;
        }
        int v = saved.path(field).asInt(fallback);
        // 저장된 값이 어떤 이유로든 범위를 벗어났으면 설정값으로 돌아간다
        return v >= 1 && v <= max ? v : fallback;
    }

    /** 기억한 값을 버리고 다음 조회에서 다시 읽는다. 저장 직후와 테스트에서 쓴다. */
    void invalidate() {
        memo = null;
        memoUntil = 0;
    }

    private JsonNode cached() {
        long now = System.currentTimeMillis();
        if (now < memoUntil) {
            return memo;
        }
        try {
            memo = store.get(KEY);
        } catch (Exception e) {
            LOG.warnf("운영값 조회 실패 — 배포 설정값을 쓴다: %s", e.toString());
            memo = null;
        }
        memoUntil = now + MEMO.toMillis();
        return memo;
    }
}
