package com.tourapi.lib;

/** data.go.kr(TourAPI) 호출/응답 실패. 라우트에서 502로 매핑한다. */
public class UpstreamException extends RuntimeException {
    public UpstreamException(String message) {
        super(message);
    }

    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
