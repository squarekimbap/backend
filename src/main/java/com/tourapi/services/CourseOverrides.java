package com.tourapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tourapi.lib.AdminStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 관리자가 고친 코스 원고. 번들 JSON은 그대로 두고 <b>바뀐 필드만</b> 따로 저장해 읽을 때 덮는다
 * — 코스를 DB로 옮기지 않고도 화면에서 글을 고칠 수 있게 하는 가장 작은 방법이다.
 *
 * <p>고칠 수 있는 것은 <b>앱이 보여주는 글과 사진뿐</b>이다. 경로·경유지·도슨트 좌표는 건드리지
 * 못한다: 배포 게이트(validate_courses.py)가 검증하고, 앱의 100m 도슨트 진입 판정이 그 좌표에
 * 걸려 있어서 텍스트 입력으로 흔들면 안 된다.
 *
 * <p>다른 Lambda 실행 환경에는 최대 {@link #MEMO} 뒤에 퍼진다. 저장 직후 앱에 잠깐 옛 글이
 * 보일 수 있다는 뜻이고, 원고 수정이라 그 정도 지연은 감수할 만하다.
 */
@ApplicationScoped
public class CourseOverrides {

    private static final Logger LOG = Logger.getLogger(CourseOverrides.class);
    private static final Duration MEMO = Duration.ofSeconds(60);
    static final String PREFIX = "course/";

    /** 문자열로 고치는 필드. */
    public static final Set<String> TEXT_FIELDS =
            Set.of("n", "headline", "subhead", "photo", "photoTitle", "photoLicense");
    /** 문단 배열로 고치는 필드(화면에서는 한 줄 = 한 문단). */
    public static final Set<String> PARAGRAPH_FIELDS = Set.of("body", "deep", "ops");

    @Inject
    AdminStore store;

    private volatile Map<String, JsonNode> memo = Map.of();
    private volatile long memoUntil;

    /** 저장된 수정이 하나도 없으면 true — 이때 호출측은 원본을 그대로 쓰면 된다. */
    public boolean isEmpty() {
        return all().isEmpty();
    }

    /** 이 코스에 저장된 수정. 없으면 null. */
    public JsonNode forCourse(String id) {
        return all().get(id);
    }

    /** 원본 위에 수정을 덮은 사본. 수정이 없으면 원본을 그대로 돌려준다(복사 안 함). */
    public JsonNode apply(JsonNode course) {
        JsonNode patch = forCourse(course.path("id").asText());
        if (patch == null || patch.isEmpty()) {
            return course;
        }
        ObjectNode merged = course.deepCopy();
        patch.propertyStream()
                .filter(e -> editable(e.getKey()))
                .forEach(e -> merged.set(e.getKey(), e.getValue()));
        return merged;
    }

    public static boolean editable(String field) {
        return TEXT_FIELDS.contains(field) || PARAGRAPH_FIELDS.contains(field);
    }

    /**
     * 수정 저장. 편집 불가 필드가 오면 거절한다 — 화면 실수로 좌표가 덮이면 안 된다.
     * 값이 비어 있는 필드는 지워서 원본으로 되돌린다.
     */
    public void save(String courseId, JsonNode patch) {
        ObjectNode clean = ((ObjectNode) patch).objectNode();
        patch.propertyStream().forEach(e -> {
            String field = e.getKey();
            if (!editable(field)) {
                throw new IllegalArgumentException("고칠 수 없는 필드다: " + field);
            }
            JsonNode v = e.getValue();
            if (PARAGRAPH_FIELDS.contains(field)) {
                if (!v.isArray()) {
                    throw new IllegalArgumentException(field + " 는 문단 배열이어야 한다");
                }
                if (!v.isEmpty()) {
                    clean.set(field, v);
                }
            } else {
                if (!v.isTextual()) {
                    throw new IllegalArgumentException(field + " 는 문자열이어야 한다");
                }
                if (!v.asText().isBlank()) {
                    clean.set(field, v);
                }
            }
        });
        if (clean.isEmpty()) {
            store.remove(PREFIX + courseId);
        } else {
            store.put(PREFIX + courseId, clean);
        }
        invalidate();
        LOG.infof("코스 원고 수정: %s (%d개 필드)", courseId, clean.size());
    }

    /** 이 코스의 수정을 전부 버리고 번들 원본으로 되돌린다. */
    public void reset(String courseId) {
        store.remove(PREFIX + courseId);
        invalidate();
        LOG.infof("코스 원고 되돌림: %s", courseId);
    }

    /** 기억한 값을 버리고 다음 조회에서 다시 읽는다. 저장 직후와 테스트에서 쓴다. */
    void invalidate() {
        memo = Map.of();
        memoUntil = 0;
    }

    private Map<String, JsonNode> all() {
        long now = System.currentTimeMillis();
        if (now < memoUntil) {
            return memo;
        }
        try {
            memo = store.getAll(PREFIX);
        } catch (Exception e) {
            // 원본을 보여주는 편이 요청을 실패시키는 것보다 낫다
            LOG.warnf("코스 수정 조회 실패 — 번들 원본을 쓴다: %s", e.toString());
            memo = Map.of();
        }
        memoUntil = now + MEMO.toMillis();
        return memo;
    }

    /** 화면에 "수정됨" 배지를 붙이기 위한 목록. */
    public List<String> editedCourseIds() {
        return all().keySet().stream().sorted().toList();
    }
}
