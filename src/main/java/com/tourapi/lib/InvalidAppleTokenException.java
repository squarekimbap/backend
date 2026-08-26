package com.tourapi.lib;

/** 앱이 보낸 Apple identityToken이 무효(만료·위조·대상 불일치)일 때. AuthResource가 401로 매핑한다. */
public class InvalidAppleTokenException extends RuntimeException {

    public InvalidAppleTokenException() {
        super("Apple identityToken이 유효하지 않음");
    }
}
