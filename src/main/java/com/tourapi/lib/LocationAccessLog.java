package com.tourapi.lib;

import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 위치정보법상 취급대장(위치정보 이용·제공 사실)을 요청마다 한 줄씩 남긴다.
 * 로그 그룹 {@code /aws/lambda/tour-api} 가 그대로 보존 장치 역할을 하므로 별도 저장소가 없다.
 *
 * <p>좌표는 일부러 적지 않는다 — 대장이 요구하는 항목이 아니고, 남기면 개인식별자와 위치가
 * 한 줄에 묶여 "개인위치정보를 저장하지 않는다"는 보호조치와 어긋난다.
 */
public final class LocationAccessLog {

    /** CloudWatch에서 `취급대장` 한 단어로 필터링되도록 전용 카테고리를 쓴다. */
    private static final Logger LOG = Logger.getLogger("location.access");

    private static final DateTimeFormatter KST = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(ZoneId.of("Asia/Seoul"));

    /** 위치정보사업자를 거치지 않고 iOS 앱이 Core Location으로 받아 보낸 좌표다. */
    private static final String SOURCE = "애플 Core Location API(단말GPS)";

    /** 이용자가 지정하는 제3자에게 제공하지 않는다. */
    private static final String RECIPIENT = "-";

    private LocationAccessLog() {
    }

    /**
     * @param subject 개인위치정보주체 식별값(Cognito sub). 비로그인 요청은 null
     * @param service 제공서비스 식별값
     */
    public static void record(String subject, String service) {
        LOG.info(line(subject, service, Instant.now()));
    }

    static String line(String subject, String service, Instant at) {
        return "위치정보 취급대장 | 대상=" + subjectOf(subject)
                + " | 취득경로=" + SOURCE
                + " | 제공서비스=" + service
                + " | 제공받는자=" + RECIPIENT
                + " | 이용일시=" + KST.format(at);
    }

    private static String subjectOf(String subject) {
        return subject == null || subject.isBlank() ? "미로그인" : subject;
    }
}
