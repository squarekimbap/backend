package com.tourapi.routes;

import com.tourapi.model.ApiError;
import com.tourapi.model.UserProfile;
import com.tourapi.services.UserService;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * 사용자 라우트. 클래스 전체 JWT 필수(@Authenticated) — smallrye-jwt가 Cognito JWKS로
 * 서명·만료·issuer를 검증한 뒤에만 진입한다. userId = 토큰의 sub 클레임.
 */
@Path("/v1/users")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "users", description = "사용자 (JWT 필수)")
public class UserResource {

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
            return Response.status(404)
                    .entity(new ApiError("profile_not_found", "프로필 없음 — 다시 로그인하면 생성됨")).build();
        }
        return Response.ok(p).build();
    }
}
