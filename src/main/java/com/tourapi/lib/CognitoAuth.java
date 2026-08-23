package com.tourapi.lib;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Cognito User Pool 래퍼. USER_POOL_ID/USER_POOL_CLIENT_ID 미설정이면 비활성(로컬 dev/테스트 기본).
 * username 규약: 이메일 사용자 email_<sha256(정규화 이메일) 32자> / 카카오 kakao_<회원번호>.
 * 비밀번호·토큰은 절대 로그에 남기지 않는다.
 */
@ApplicationScoped
public class CognitoAuth {

    private static final SecureRandom RANDOM = new SecureRandom();
    // 회전용 비밀번호 문자집합: 풀 정책(대문자/소문자/숫자) 충족을 앞 3자로 보장
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGIT = "0123456789";
    private static final String ALL = LOWER + UPPER + DIGIT;
    private static final int ROTATION_PASSWORD_LENGTH = 64;

    @ConfigProperty(name = "user.pool.id")
    Optional<String> poolIdOpt;
    @ConfigProperty(name = "user.pool.client.id")
    Optional<String> clientIdOpt;

    private volatile CognitoIdentityProviderClient client;

    public boolean enabled() {
        return poolIdOpt.filter(s -> !s.isBlank()).isPresent()
                && clientIdOpt.filter(s -> !s.isBlank()).isPresent();
    }

    /** 같은 이메일(trim·소문자)은 항상 같은 username — Cognito username 충돌로 중복 가입이 막힌다. */
    public static String usernameForEmail(String email) {
        String normalized = email.trim().toLowerCase();
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return "email_" + HexFormat.of().formatHex(h).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    public void signUp(String email, String password, String nickname) {
        client().signUp(b -> b.clientId(clientId())
                .username(usernameForEmail(email))
                .password(password)
                .userAttributes(
                        AttributeType.builder().name("email").value(email.trim().toLowerCase()).build(),
                        AttributeType.builder().name("nickname").value(nickname).build()));
    }

    public void confirm(String email, String code) {
        client().confirmSignUp(b -> b.clientId(clientId())
                .username(usernameForEmail(email)).confirmationCode(code));
    }

    /** 가입 확인코드 재발송. 이미 확인된 계정이면 InvalidParameterException. */
    public void resendConfirmationCode(String email) {
        client().resendConfirmationCode(b -> b.clientId(clientId())
                .username(usernameForEmail(email)));
    }

    /**
     * 비밀번호 재설정 코드 발송. 확인된 이메일이 있어야 하며(미확인 계정은
     * InvalidParameterException), 카카오 사용자는 username 규약이 달라 애초에 조회되지 않는다.
     */
    public void forgotPassword(String email) {
        client().forgotPassword(b -> b.clientId(clientId())
                .username(usernameForEmail(email)));
    }

    /** 재설정 코드 검증 + 새 비밀번호 적용. */
    public void confirmForgotPassword(String email, String code, String newPassword) {
        client().confirmForgotPassword(b -> b.clientId(clientId())
                .username(usernameForEmail(email))
                .confirmationCode(code).password(newPassword));
    }

    public AuthenticationResultType loginWithPassword(String username, String password) {
        AdminInitiateAuthResponse res = client().adminInitiateAuth(b -> b
                .userPoolId(poolId()).clientId(clientId())
                .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", username, "PASSWORD", password)));
        return res.authenticationResult();
    }

    public AuthenticationResultType refresh(String refreshToken) {
        AdminInitiateAuthResponse res = client().adminInitiateAuth(b -> b
                .userPoolId(poolId()).clientId(clientId())
                .authFlow(AuthFlowType.REFRESH_TOKEN_AUTH)
                .authParameters(Map.of("REFRESH_TOKEN", refreshToken)));
        return res.authenticationResult();
    }

    /**
     * refresh 토큰 폐기(= 로그아웃). 이 토큰으로 발급된 access 토큰도 함께 무효화된다.
     * 클라이언트에 EnableTokenRevocation이 꺼져 있으면 UnsupportedTokenTypeException.
     */
    public void revokeRefreshToken(String refreshToken) {
        client().revokeToken(b -> b.clientId(clientId()).token(refreshToken));
    }

    /** 닉네임 속성 변경. users 테이블이 아니라 신원 저장소 쪽 값이다. */
    public void updateNickname(String username, String nickname) {
        client().adminUpdateUserAttributes(b -> b.userPoolId(poolId()).username(username)
                .userAttributes(AttributeType.builder().name("nickname").value(nickname).build()));
    }

    /** kakao_<id> 사용자 조회, 없으면 생성(이메일 발송 억제). username 반환. */
    public String ensureKakaoUser(long kakaoId, String nickname) {
        String username = "kakao_" + kakaoId;
        try {
            client().adminGetUser(b -> b.userPoolId(poolId()).username(username));
        } catch (UserNotFoundException e) {
            client().adminCreateUser(b -> b.userPoolId(poolId()).username(username)
                    .messageAction(MessageActionType.SUPPRESS)
                    .userAttributes(AttributeType.builder().name("nickname").value(nickname).build()));
        }
        return username;
    }

    /** 카카오 브릿지: 랜덤 비밀번호 설정 직후 로그인. 비밀번호는 저장하지 않고 매 로그인마다 교체. */
    public AuthenticationResultType rotatePasswordAndLogin(String username) {
        String password = randomPassword();
        client().adminSetUserPassword(b -> b.userPoolId(poolId()).username(username)
                .password(password).permanent(true));
        return loginWithPassword(username, password);
    }

    private static String randomPassword() {
        StringBuilder sb = new StringBuilder(ROTATION_PASSWORD_LENGTH);
        sb.append(LOWER.charAt(RANDOM.nextInt(LOWER.length())));
        sb.append(UPPER.charAt(RANDOM.nextInt(UPPER.length())));
        sb.append(DIGIT.charAt(RANDOM.nextInt(DIGIT.length())));
        for (int i = 3; i < ROTATION_PASSWORD_LENGTH; i++) {
            sb.append(ALL.charAt(RANDOM.nextInt(ALL.length())));
        }
        return sb.toString();
    }

    private String poolId() {
        return poolIdOpt.orElseThrow();
    }

    private String clientId() {
        return clientIdOpt.orElseThrow();
    }

    private CognitoIdentityProviderClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    String region = System.getenv().getOrDefault("AWS_REGION", "ap-northeast-2");
                    client = CognitoIdentityProviderClient.builder()
                            .region(Region.of(region))
                            .httpClientBuilder(UrlConnectionHttpClient.builder())
                            .build();
                }
            }
        }
        return client;
    }
}
