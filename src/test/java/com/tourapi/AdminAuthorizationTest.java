package com.tourapi;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import com.tourapi.lib.CognitoAuth;
import com.tourapi.lib.UserStore;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리 API의 접근 통제. 여기가 뚫리면 아무 가입자나 전체 명단을 보고 계정을 지울 수 있다.
 * 허용목록에 든 '확인된' 이메일만 통과해야 한다.
 */
@QuarkusTest
@TestProfile(AdminAuthorizationTest.Allowlist.class)
public class AdminAuthorizationTest {

    /** 허용목록이 설정된 상태. 공백과 대소문자가 섞여도 동작해야 한다. */
    public static class Allowlist implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("admin.emails", " Boss@Example.com , second@example.com ");
        }
    }

    @InjectMock
    UserStore userStore;

    @InjectMock
    CognitoAuth cognito;

    // 전체 검수 목록은 응답이 커서 HTTP로 못 부른다(MockEventServer read timeout — 코스 목록과
    // 같은 함정). 계산 자체는 CourseReviewTest가 서비스 레벨에서 본다.

    @Test
    public void 토큰_없으면_401() {
        RestAssured.when().get("/v1/admin/users").then().statusCode(401);
        RestAssured.when().get("/v1/admin/courses/gangneung-gyeongpo").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "u-1", roles = {})
    @JwtSecurity(claims = {
            @Claim(key = "sub", value = "u-1"),
            @Claim(key = "email", value = "nobody@example.com"),
            @Claim(key = "email_verified", value = "true")})
    public void 허용목록에_없는_이메일은_403() {
        RestAssured.when().get("/v1/admin/users").then().statusCode(403)
                .body("error", equalTo("forbidden"));
    }

    @Test
    @TestSecurity(user = "u-1", roles = {})
    @JwtSecurity(claims = {
            @Claim(key = "sub", value = "u-1"),
            @Claim(key = "email", value = "boss@example.com"),
            @Claim(key = "email_verified", value = "false")})
    public void 이메일_미확인이면_허용목록에_있어도_403() { // 주소 주인이 아닐 수 있다
        RestAssured.when().get("/v1/admin/users").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "u-1", roles = {})
    @JwtSecurity(claims = {
            @Claim(key = "sub", value = "u-1"),
            @Claim(key = "email_verified", value = "true")})
    public void 이메일_클레임이_없으면_403() {
        RestAssured.when().get("/v1/admin/users").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "u-1", roles = {})
    @JwtSecurity(claims = {
            @Claim(key = "sub", value = "u-1"),
            @Claim(key = "email", value = "BOSS@example.com"),   // 대소문자 무관
            @Claim(key = "email_verified", value = "true")})
    public void 허용된_이메일은_통과() {
        when(userStore.listAll()).thenReturn(List.of(Map.of("userId", "x", "provider", "email")));
        RestAssured.when().get("/v1/admin/users").then().statusCode(200)
                .body("users[0].provider", equalTo("email"));
        // 코스 응답은 본문·경로가 커서 HTTP로 못 받는다(위 주석의 함정). 같은 denyUnlessAdmin을
        // 지나므로 /users 통과로 관문 검증은 충분하다.
    }

    @Test
    @TestSecurity(user = "u-9", roles = {})
    @JwtSecurity(claims = {
            @Claim(key = "sub", value = "u-9"),
            @Claim(key = "email", value = "boss@example.com"),
            @Claim(key = "email_verified", value = "true")})
    public void 본인_계정은_관리_화면에서_못_지운다() { // Apple 폐기를 건너뛰게 되므로 앱 탈퇴로 보낸다
        given().delete("/v1/admin/users/u-9").then().statusCode(400)
                .body("error", equalTo("self_delete"));
        verify(userStore, never()).delete(any());
    }

    @Test
    @TestSecurity(user = "u-9", roles = {})
    @JwtSecurity(claims = {
            @Claim(key = "sub", value = "u-9"),
            @Claim(key = "email", value = "boss@example.com"),
            @Claim(key = "email_verified", value = "true")})
    public void 삭제는_프로필_먼저_계정_나중() { // 서버 탈퇴와 같은 재시도 안전 순서
        given().delete("/v1/admin/users/other-user").then().statusCode(204);
        var o = org.mockito.Mockito.inOrder(userStore, cognito);
        o.verify(userStore).delete("other-user");
        o.verify(cognito).deleteUser("other-user");
    }

}
