package com.tourapi.routes;

import com.tourapi.model.ApiError;
import com.tourapi.model.UpdateProfileRequest;
import com.tourapi.model.UserProfile;
import com.tourapi.services.UserService;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.json.JsonString;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

/**
 * 사용자 라우트. 클래스 전체 JWT 필수(@Authenticated) — smallrye-jwt가 Cognito JWKS로
 * 서명·만료·issuer를 검증한 뒤에만 진입한다. userId = 토큰의 sub 클레임.
 */
@Path("/v1/users")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "users", description = "사용자 (JWT 필수)")
public class UserResource {

    private static final Logger LOG = Logger.getLogger(UserResource.class);
    private static final int NICKNAME_MAX = 20;
    private static final String PROFILE_NOT_FOUND = "프로필 없음 — 다시 로그인하면 생성됨";

    @Inject
    JsonWebToken jwt;

    @Inject
    UserService userService;

    @GET
    @Path("/me")
    @Operation(summary = "내 프로필 조회")
    public Response me() {
        UserProfile p = userService.find(jwt.getSubject());
        if (p == null) {
            return error(404, "profile_not_found", PROFILE_NOT_FOUND);
        }
        return Response.ok(p).build();
    }

    @PATCH
    @Path("/me")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "내 프로필 수정(닉네임)")
    public Response updateMe(UpdateProfileRequest req) {
        String nickname = req == null || req.nickname() == null ? "" : req.nickname().trim();
        if (nickname.isEmpty()) {
            return error(400, "bad_request", "nickname 필수");
        }
        if (nickname.length() > NICKNAME_MAX) {
            return error(400, "bad_request", "nickname은 " + NICKNAME_MAX + "자 이하");
        }
        try {
            if (!userService.updateNickname(cognitoUsername(), jwt.getSubject(), nickname)) {
                return error(404, "profile_not_found", PROFILE_NOT_FOUND);
            }
        } catch (UserNotFoundException e) {
            return error(404, "user_not_found", "계정 없음");
        } catch (SdkException e) {
            LOG.warnf("프로필 수정 실패: %s", e.toString());
            return error(502, "upstream_error", "프로필 저장 실패 — 잠시 후 다시 시도");
        }
        return Response.ok(userService.find(jwt.getSubject())).build();
    }

    @DELETE
    @Path("/me")
    @Operation(summary = "회원 탈퇴 — 프로필과 계정을 모두 삭제")
    public Response deleteMe() {
        try {
            userService.delete(cognitoUsername(), jwt.getSubject());
        } catch (UserNotFoundException e) {
            // 이미 지워진 계정 — DELETE는 멱등해야 하므로 성공으로 본다
        } catch (SdkException e) {
            LOG.warnf("탈퇴 실패: %s", e.toString());
            return error(502, "upstream_error", "탈퇴 처리 실패 — 잠시 후 다시 시도");
        }
        return Response.noContent().build();
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────

    /**
     * 관리자 API에 넘길 Cognito username. ID 토큰은 cognito:username, access 토큰은
     * username 클레임에 담는다. 둘 다 없으면 sub(관리자 API가 sub도 받는다).
     */
    private String cognitoUsername() {
        String u = claim("cognito:username");
        if (u == null) {
            u = claim("username");
        }
        return u == null ? jwt.getSubject() : u;
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
