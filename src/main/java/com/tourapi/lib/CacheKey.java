package com.tourapi.lib;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 버전이 포함된 외부 API 캐시 키를 고정 길이로 만든다. */
public final class CacheKey {

    private CacheKey() {
    }

    public static String sha256(String namespace, String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return namespace + '#' + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }
}
