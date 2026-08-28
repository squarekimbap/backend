# 데이터 · API · 에셋

## 규모

| 항목 | 수 |
|---|---|
| 도시 | 21곳 |
| 코스 | 77개 |
| 이야기 (체크포인트) | 28곳 |
| 코스 대표 사진 | 77장 |
| 경유지 사진 | 16곳 |

## 코스 모델

```swift
struct Course {
    let id: String
    let name: String            // n
    let city: String
    let km: Double
    let minutes: Int            // min
    let level: Level            // 쉬움 / 보통 / 어려움
    let mood: String            // 문화유산 / 야경 / 풍경 / 평지 / 업힐 / 트레일
    let tags: [String]          // 초보 추천 · 야경 · 호수·강변 · 바다 · 숲길 · 여행 확장
    let routed: Bool            // 길 안내 확보 여부 — false면 시작·GPX 잠금
    let polyline: [Coordinate]? // routed일 때만
    let poi: [String]           // 지나는 곳
    let checkpoints: [Checkpoint]
    let source: Source          // 직접 제작 / 공공 출처 / 러너 후기
    // 원고가 있는 코스
    let headline: String?       // h1 — 편집된 대제목
    let subhead: String?        // h2
    let body: [String]?         // 어떤 길인지
    let deep: [String]?         // 더 알아두면 좋은 것
    let ops: [String]?          // 가기 전에 알아두면 좋아요
    let unsure: [String]?       // 아직 확인 중인 것
    let season: String?, when: String?
}

struct Checkpoint {
    let name: String
    let coordinate: Coordinate
    let radius: Double = 100    // m — ADR-031
    let audioSeconds: Int
    let description: String     // 열린 뒤에만 노출
}

struct RunRecord {
    let date: Date, courseName: String
    let km: Double, seconds: Int
    let storiesHeard: Int, storiesTotal: Int
    let backgroundSeconds: Int   // 화면 꺼진 동안 기록한 시간
}
```

## 트리거 규칙 (러닝 중 이야기 재생)

프로토타입에서 검증한 값. **그대로 옮길 것.**

| 조건 | 값 |
|---|---|
| 반경 | 100m |
| GPS 정확도 게이트 | 50m 이하일 때만 판정 |
| 연속 진입 | 2회 연속 반경 안이어야 발동 |
| 중복 방지 | 세션당 1회. 건너뛴 지점은 같은 세션에서 재발동 안 함 |
| 워밍업 | 출발 후 300m까지 보류 |
| 재생 중 | 다른 체크포인트 트리거는 대기 |
| 틱 주기 | 250ms |

## API

| API | 용도 | 파라미터 |
|---|---|---|
| TourAPI `locationBasedList2` | 주변 관광지 조회 | `mapX`, `mapY`, 거리·형태별 동적 반경(500m~20km), `contentTypeId=12·14·28` |
| TourAPI 분류코드 | 무드 자동 결정 | `cat1`, `cat2`, `cat3` 분포 |
| TourAPI 오디오 가이드 | 이야기 콘텐츠 | 재생 가능 여부 검증 필요 (P0) |
| TMAP 보행 경로 | 경로 계산 | 후보별 `passList` 단일 호출 · route-options 최대 5회/routes 최대 3회 · 동일 입력 5분 캐시 |

### 생성 로직

```
① 위치 확인 (CoreLocation)
② [사용자 개입] 거리(3·5·10·15km)와 왕복/편도 선택
③ `/v1/running/candidates` → 관광지와 Odii 도슨트 가용성 조회
④ [선택] 꼭 지날 곳 0~5개 고르기 — 건너뛰기 가능
⑤ `/v1/running/route-options` → 관광지 우선/거리 우선 경유지 조합
⑥ TMAP 보행 경로 → Google 고도 → 구간거리·난이도·도슨트 매칭
⑦ [사용자 개입] "고른 곳을 지나요" / "거리가 딱 맞아요" 중 선택
⑧ `/v1/running/summary` → 도착지 주변 음식점·카페 결합
⑨ 코스 총정리 화면
```

실패 분기:

- `nopoi` — 주변 관광지가 거리별 필요 수보다 적음
- `dist` — 거리 우선 후보도 오차 10%를 넘음(`withinTolerance=false`로 대체 옵션 노출)
- `route` — TMAP 경로 계산 실패

## 이미지 에셋

| 항목 | 내용 |
|---|---|
| 출처 | Wikimedia Commons (자유 라이선스) |
| 표기 의무 | 대부분 **CC BY-SA** → 촬영자 이름 + 라이선스 종류 필요 |
| 현재 상태 | 캡션과 원본 링크만 있음 → **출시 전 촬영자 확인 필요** |
| 크기 | 원본 1280px. 서버에서 리사이즈·캐시 권장 |
| 실패 대비 | 로드 실패 시 SVG 결 배경이 드러남 — 카드가 비지 않음 |

### 대체 이미지 (해당 장소 사진이 없어 유사한 것으로 대신함)

| 장소 | 대체 내용 |
|---|---|
| 첨성대 | 문화유산 코스 대표 이미지 (첨성대 사진 없음) |
| 대릉원 | 문화유산 코스 대표 이미지 (대릉원 사진 없음) |
| 동궁과 월지 | 야간 수변 이미지 (동궁과 월지 사진 없음) |
| 월정교 | 문화유산 교량 이미지 (월정교 사진 없음) |
| 교촌한옥마을 | 한옥마을 이미지 (교촌 사진 없음) |

### 지도 경로 도형

이미지가 아니라 좌표 배열. 코스 성격에 따라 자동 선택.

| 도형 | 성격 |
|---|---|
| `lake` `city` | 닫힌 폐곡선 — 순환 코스 |
| `river` `coast` | 왕복 — 갈 때와 올 때를 살짝 벌려 겹치지 않게 |
| `trail` `hill` | 편도 — 숲길은 구불구불, 업힐은 지그재그 |

실제 앱에서는 TMAP이 반환한 polyline으로 대체한다.

## GPX 내보내기

```xml
<gpx version="1.1" creator="어디 뛰지">
  <metadata>
    <name>코스명</name>
    <desc>거리 · 시간 · 강도. 관광 정보는 한국관광공사 TourAPI,
          보행 경로는 TMAP을 사용했습니다.</desc>
  </metadata>
  <trk><trkseg><trkpt lat="" lon=""/>…</trkseg></trk>
  <wpt lat="" lon=""><name>첨성대</name><type>관광지</type></wpt>
</gpx>
```

- 지나는 곳을 `wpt`로 넣어야 가민 화면에 지점 이름이 표시된다
- `routed == false`인 코스는 **내보내기 금지** — 대략 형태가 파일로 나가면 사용자가 믿고 따라간다
