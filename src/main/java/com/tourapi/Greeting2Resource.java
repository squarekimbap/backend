package com.tourapi;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** 배포 파이프라인 확인용 엔드포인트. 자바 파일 추가만으로 API가 느는지 보는 용도. */
@Path("/hello2")
public class Greeting2Resource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello2() {
        return "hello2 from github actions";
    }
}
