package com.tourapi.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.tourapi.model.ApiError;
import com.tourapi.services.CourseCatalog;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * 편집 코스 카탈로그 라우트 (홈 피드·코스 상세 화면용).
 * 응답은 수집본 스키마 그대로 — 상세: id·n·km·min·lv·mood·tags·headline·subhead·
 * body[]·deep[]·ops[]·unsure[]·poi[{n,d,photo}]·photo·photoTitle·photoLicense·city·region.
 */
@Path("/v1/courses")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "courses", description = "편집 코스 카탈로그 (지역별 수집 원고)")
public class CourseResource {

    @Inject
    CourseCatalog catalog;

    @GET
    @Operation(summary = "코스 목록(요약)",
            description = "카드 렌더링용 요약 필드만 내려준다. city(도시명 '서울' 또는 cityId 'seoul')로 필터 가능. 상세는 GET /v1/courses/{id}.")
    @APIResponse(responseCode = "200", description = "{count, items:[{id,n,city,km,min,lv,mood,tags,headline,subhead,photo,...}]}")
    public Response list(
            @Parameter(description = "도시 필터(도시명 또는 cityId). 없으면 전체", example = "서울")
            @QueryParam("city") String city) {
        ArrayNode items = catalog.list(city);
        return Response.ok(java.util.Map.of("count", items.size(), "items", items)).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "코스 상세",
            description = "id로 코스 전체 원고(본문·팁·경유지·사진)를 조회한다. 예: seoul-banpo-10k")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "코스 전체 필드(JSON)"),
            @APIResponse(responseCode = "404", description = "없는 id")
    })
    public Response detail(
            @Parameter(description = "코스 id", required = true, example = "seoul-banpo-10k")
            @PathParam("id") String id) {
        JsonNode c = catalog.byId(id);
        if (c == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ApiError("not_found", "코스 없음: " + id)).build();
        }
        return Response.ok(c).build();
    }
}
