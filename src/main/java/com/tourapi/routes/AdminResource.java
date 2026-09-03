package com.tourapi.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.tourapi.lib.CognitoAuth;
import com.tourapi.lib.UserStore;
import com.tourapi.services.AdminSettings;
import com.tourapi.services.CourseCatalog;
import com.tourapi.services.CourseOverrides;
import com.tourapi.services.CourseReview;
import com.tourapi.model.ApiError;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.json.JsonString;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리 화면 백엔드. 일반 사용자 JWT를 그대로 쓰되 이메일이 허용목록에 있어야 통과한다
 * — 별도 관리자 계정 체계를 만들지 않으려고 이렇게 뒀다.
 *
 * <p><b>기본값은 잠김이다.</b> {@code admin.emails}가 비어 있으면 모든 요청이 403이므로,
 * 설정을 깜빡한 채 배포해도 관리 API가 열려 있지 않다.
 */
@Path("/v1/admin")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "admin", description = "관리 (허용된 이메일만)")
public class AdminResource {

    private static final Logger LOG = Logger.getLogger(AdminResource.class);
    @ConfigProperty(name = "admin.emails")
    Optional<String> adminEmails;

    @Inject
    JsonWebToken jwt;

    @Inject
    CourseReview courseReview;

    @Inject
    CourseCatalog catalog;

    @Inject
    CourseOverrides overrides;

    @Inject
    AdminSettings adminSettings;

    @Inject
    UserStore userStore;

    @Inject
    CognitoAuth cognito;

    // ── 코스 검수 ────────────────────────────────────────────────────

    @GET
    @Path("/courses/review")
    @Operation(summary = "코스 검수 목록 — 사진·경로·저작권에서 사람이 봐야 할 것만 표시")
    public Response review() {
        Response denied = denyUnlessAdmin();
        return denied != null ? denied : Response.ok(courseReview.summaries()).build();
    }

    @GET
    @Path("/courses/{id}")
    @Operation(summary = "코스 검수 상세 — 경유지·도슨트의 경로 이탈 거리를 계산해 붙인다")
    public Response courseDetail(@PathParam("id") String id) {
        Response denied = denyUnlessAdmin();
        if (denied != null) {
            return denied;
        }
        JsonNode d = courseReview.detail(id);
        return d == null ? error(404, "not_found", "그런 코스가 없다") : Response.ok(d).build();
    }

    @PUT
    @Path("/courses/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "코스 원고 수정 — 앱에 보이는 글과 사진만. 경로·좌표는 못 고친다")
    public Response saveCourse(@PathParam("id") String id, JsonNode patch) {
        Response denied = denyUnlessAdmin();
        if (denied != null) {
            return denied;
        }
        if (catalog.originalById(id) == null) {
            return error(404, "not_found", "그런 코스가 없다");
        }
        if (patch == null || !patch.isObject()) {
            return error(400, "bad_request", "고칠 필드를 담은 객체가 필요하다");
        }
        try {
            overrides.save(id, patch);
        } catch (IllegalArgumentException e) {
            return error(400, "bad_request", e.getMessage());
        } catch (IllegalStateException e) {
            LOG.warnf("코스 수정 저장 실패: %s", e.toString());
            return error(502, "upstream_error", "저장 실패 — 다시 시도해라");
        }
        LOG.infof("코스 원고 수정: %s (요청자 %s)", id, email());
        return Response.ok(courseReview.detail(id)).build();
    }

    @DELETE
    @Path("/courses/{id}/edits")
    @Operation(summary = "코스 원고를 번들 원본으로 되돌린다")
    public Response resetCourse(@PathParam("id") String id) {
        Response denied = denyUnlessAdmin();
        if (denied != null) {
            return denied;
        }
        try {
            overrides.reset(id);
        } catch (IllegalStateException e) {
            return error(502, "upstream_error", "되돌리기 실패 — 다시 시도해라");
        }
        LOG.infof("코스 원고 되돌림: %s (요청자 %s)", id, email());
        return Response.ok(courseReview.detail(id)).build();
    }

    // ── 운영값 ───────────────────────────────────────────────────────

    @GET
    @Path("/settings")
    @Operation(summary = "현재 운영값과 배포 기본값")
    public Response settings() {
        Response denied = denyUnlessAdmin();
        return denied != null ? denied : Response.ok(adminSettings.snapshot()).build();
    }

    @PUT
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "사용자별 일일 코스 생성 횟수와 분당 호출 상한을 바꾼다")
    public Response updateSettings(SettingsRequest req) {
        Response denied = denyUnlessAdmin();
        if (denied != null) {
            return denied;
        }
        try {
            adminSettings.update(req == null ? null : req.dailyLimit(),
                    req == null ? null : req.perMinuteLimit());
        } catch (IllegalArgumentException e) {
            return error(400, "bad_request", e.getMessage());
        } catch (IllegalStateException e) {
            LOG.warnf("운영값 저장 실패: %s", e.toString());
            return error(502, "upstream_error", "저장 실패 — 다시 시도해라");
        }
        LOG.infof("운영값 변경 (요청자 %s)", email());
        return Response.ok(adminSettings.snapshot()).build();
    }

    /** 바꾸지 않을 항목은 null로 둔다. */
    public record SettingsRequest(Integer dailyLimit, Integer perMinuteLimit) {
    }

    // ── 가입자 ───────────────────────────────────────────────────────

    @GET
    @Path("/users")
    @Operation(summary = "가입자 목록 (Apple 폐기 토큰 값은 내보내지 않는다)")
    public Response users() {
        Response denied = denyUnlessAdmin();
        if (denied != null) {
            return denied;
        }
        return Response.ok(Map.of("users", userStore.listAll())).build();
    }

    /**
     * 가입자 삭제. 서버의 탈퇴와 같은 순서(프로필 행 → Cognito)로 재시도 안전을 맞춘다.
     *
     * <p>⚠️ Apple 토큰 폐기는 하지 않는다. 사용자가 직접 하는 탈퇴가 아니라 관리자 조치이고,
     * 폐기는 앱의 {@code DELETE /v1/users/me} 경로에만 있다 — 심사 5.1.1(v)는 그쪽이 충족한다.
     */
    @DELETE
    @Path("/users/{userId}")
    @Operation(summary = "가입자 삭제 (Apple 토큰 폐기는 하지 않음)")
    public Response deleteUser(@PathParam("userId") String userId) {
        Response denied = denyUnlessAdmin();
        if (denied != null) {
            return denied;
        }
        if (userId.equals(jwt.getSubject())) {
            return error(400, "self_delete", "본인 계정은 여기서 지울 수 없다 — 앱의 탈퇴를 쓸 것");
        }
        try {
            userStore.delete(userId);
            cognito.deleteUser(userId);
        } catch (UserNotFoundException e) {
            // 이미 지워진 계정 — 멱등
        } catch (SdkException e) {
            LOG.warnf("관리자 삭제 실패: %s", e.toString());
            return error(502, "upstream_error", "삭제 실패 — 잠시 후 다시 시도");
        }
        LOG.infof("관리자 삭제: %s (요청자 %s)", userId, email());
        return Response.noContent().build();
    }

    // ── 내부 ─────────────────────────────────────────────────────────

    /** 허용목록에 없으면 403. 목록이 비어 있으면 아무도 통과하지 못한다(기본 잠김). */
    private Response denyUnlessAdmin() {
        Set<String> allowed = adminEmails.stream()
                .flatMap(s -> java.util.Arrays.stream(s.split(",")))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        String email = email();
        // 이메일 미확인 계정은 그 주소의 주인이 아닐 수 있다 — 확인된 주소만 신뢰한다.
        boolean verified = "true".equalsIgnoreCase(claim("email_verified"));
        if (allowed.isEmpty() || email == null || !verified || !allowed.contains(email.toLowerCase())) {
            return error(403, "forbidden", "관리 권한이 없다");
        }
        return null;
    }

    private String email() {
        return claim("email");
    }

    /** 문자열 클레임 읽기. JSON-P 타입으로 오는 경우 toString()이 따옴표를 붙이므로 분기한다. */
    private String claim(String name) {
        Object v = jwt.getClaim(name);
        if (v == null) {
            return null;
        }
        String s = v instanceof JsonString js ? js.getString() : v.toString();
        return s.isBlank() ? null : s;
    }

    private static Response error(int status, String code, String msg) {
        return Response.status(status).entity(new ApiError(code, msg)).build();
    }
}
