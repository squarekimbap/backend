package com.tourapi.lib;

/** 앱이 보낸 카카오 액세스 토큰이 무효(만료·위조)일 때. AuthResource가 401로 매핑한다. */
public class InvalidKakaoTokenException extends RuntimeException {

    public InvalidKakaoTokenException() {
        super("카카오 액세스 토큰이 유효하지 않음");
    }
}
