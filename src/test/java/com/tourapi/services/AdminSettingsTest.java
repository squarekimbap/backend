package com.tourapi.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourapi.lib.AdminStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 코스 생성 횟수를 관리 화면에서 바꾸는 경로. 여기가 틀리면 사용자가 코스를 아예 못 만들거나
 * (0으로 저장) 유료 API가 무제한으로 새어 나간다.
 */
@QuarkusTest
public class AdminSettingsTest {

    private static final ObjectMapper M = new ObjectMapper();

    @InjectMock
    AdminStore store;

    @Inject
    AdminSettings settings;

    private void saved(String json) throws Exception {
        when(store.get(AdminSettings.KEY)).thenReturn(json == null ? null : M.readTree(json));
    }

    @BeforeEach
    void 기억한_값_비우기() {   // ApplicationScoped 라 테스트끼리 메모가 샌다
        settings.invalidate();
    }

    @Test
    public void 저장된_값이_없으면_배포_설정값() throws Exception {
        saved(null);
        assertEquals(3, settings.dailyLimit());
        assertEquals(6, settings.perMinuteLimit());
    }

    @Test
    public void 저장된_값이_있으면_그걸_쓴다() throws Exception {
        saved("{\"dailyLimit\":10,\"perMinuteLimit\":20}");
        assertEquals(10, settings.dailyLimit());
        assertEquals(20, settings.perMinuteLimit());
    }

    @Test
    public void 저장소가_죽으면_설정값으로_폴백한다() { // 제한을 못 읽었다고 서비스를 막지 않는다
        when(store.get(any())).thenThrow(new IllegalStateException("dynamo down"));
        assertEquals(3, settings.dailyLimit());
    }

    @Test
    public void 범위를_벗어난_저장값은_무시하고_설정값() throws Exception {
        saved("{\"dailyLimit\":0}");          // 0이면 아무도 코스를 못 만든다
        assertEquals(3, settings.dailyLimit());
        saved("{\"dailyLimit\":9999}");       // 상한 넘김
        assertEquals(3, settings.dailyLimit());
    }

    @Test
    public void 잘못된_입력은_저장하지_않고_거절한다() throws Exception {
        saved(null);
        assertThrows(IllegalArgumentException.class, () -> settings.update(0, null));
        assertThrows(IllegalArgumentException.class, () -> settings.update(101, null));
        assertThrows(IllegalArgumentException.class, () -> settings.update(null, 61));
        verify(store, never()).put(any(), any());
    }

    @Test
    public void null인_항목은_현재값을_유지한다() throws Exception {
        saved("{\"dailyLimit\":5,\"perMinuteLimit\":7}");
        settings.update(9, null);
        verify(store).put(eq(AdminSettings.KEY),
                eq(java.util.Map.of("dailyLimit", 9, "perMinuteLimit", 7)));
    }

    @Test
    public void 저장_실패는_삼키지_않는다() throws Exception { // 관리자가 누른 변경이 사라지면 안 된다
        saved(null);
        doThrow(new IllegalStateException("write failed")).when(store).put(any(), any());
        assertThrows(IllegalStateException.class, () -> settings.update(5, null));
    }
}
