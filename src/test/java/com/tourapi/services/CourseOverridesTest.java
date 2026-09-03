package com.tourapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourapi.lib.AdminStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자가 고친 코스 원고가 앱 응답에 반영되는 경로. 여기가 새면 좌표까지 텍스트 입력으로
 * 덮여 경로가 망가진다 — 편집 가능한 필드만 통과해야 한다.
 */
@QuarkusTest
public class CourseOverridesTest {

    private static final ObjectMapper M = new ObjectMapper();

    @InjectMock
    AdminStore store;

    @Inject
    CourseOverrides overrides;

    @Inject
    CourseCatalog catalog;

    private void stored(String courseId, String json) throws Exception {
        when(store.getAll(CourseOverrides.PREFIX))
                .thenReturn(json == null ? Map.of() : Map.of(courseId, M.readTree(json)));
    }

    @BeforeEach
    void 기억한_값_비우기() {   // ApplicationScoped 라 테스트끼리 메모가 샌다
        overrides.invalidate();
    }

    @Test
    public void 좌표나_경로는_고칠_수_없다() {
        for (String field : new String[]{"polyline", "poi", "checkpoints", "km", "lat", "id"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> overrides.save("gangneung-gyeongpo", M.createObjectNode().put(field, "x")),
                    field + " 는 거절돼야 한다");
        }
        verify(store, never()).put(any(), any());
    }

    @Test
    public void 문단_필드에_문자열을_주면_거절한다() {
        assertThrows(IllegalArgumentException.class,
                () -> overrides.save("gangneung-gyeongpo",
                        M.createObjectNode().put("body", "한 줄짜리")));
    }

    @Test
    public void 고친_원고가_앱_응답에_반영된다() throws Exception {
        stored("gangneung-gyeongpo", "{\"headline\":\"고친 제목\"}");
        JsonNode c = catalog.byId("gangneung-gyeongpo");
        assertEquals("고친 제목", c.path("headline").asText());
        // 건드리지 않은 필드와 좌표는 그대로여야 한다
        assertEquals(200, c.path("polyline").size());
        assertTrue(c.path("subhead").asText().contains("경포대"));
    }

    @Test
    public void 수정이_없으면_원본을_그대로_돌려준다() throws Exception {
        stored(null, null);
        assertTrue(overrides.isEmpty());
        assertEquals(69, catalog.list(null).size());
    }

    @Test
    public void 빈_값은_지워서_원본으로_되돌린다() throws Exception {
        stored(null, null);
        overrides.save("gangneung-gyeongpo", M.createObjectNode().put("headline", "   "));
        verify(store).remove(eq(CourseOverrides.PREFIX + "gangneung-gyeongpo"));
    }

    @Test
    public void 저장소가_죽으면_번들_원본을_쓴다() { // 원고를 못 읽었다고 앱을 죽이지 않는다
        when(store.getAll(any())).thenThrow(new IllegalStateException("dynamo down"));
        assertEquals(69, catalog.list(null).size());
    }
}
