# CLAUDE.md — 프로젝트 작업 컨텍스트

이 파일은 향후 작업 시 빠르게 맥락을 잡기 위한 메모다. (사람용 개요는 [README.md](README.md))

## 이 프로젝트가 뭔가
한 앱의 **전체 백엔드 서버**(서버리스). 인증/로그인 + 사용자 DB + 기능 API.
**첫 기능 도메인 = 관광/러닝 추천**(외부 API 프록시·집계). 관광은 전부가 아니라 첫 도메인.

## 위치 · 원격
- 로컬: `/Users/mega/Downloads/tour-api` (kimdongjoo 맥은 `~/Developer/personal/backend`) — **반드시 ASCII 경로.** 한글 폴더(`서버`)에 두면 IntelliJ 런처가 깨짐(이전에 발생, 그래서 이리로 이동).
- GitHub: `https://github.com/squarekimbap/backend` (origin/main, HTTPS, 자격증명 store됨)

## 스택 (결정 + 이유)
- **AWS Lambda (java21) + Function URL** — Function URL은 API Gateway의 12개월-후-과금을 피하려고 선택(무료 유지).
- **Java 21 + Quarkus 3.37** (`quarkus-amazon-lambda-http`) + **RESTEasy(JAX-RS)**. Java는 사용자의 Spring/Java 경험 활용. Quarkus는 콜드스타트(native) 목적.
- **Maven** 빌드, **AWS SAM**(`template.yaml`) 배포.
- **DynamoDB 캐시·사용자 테이블과 Cognito 인증 배포됨**: `tour-api-cache`(pk, TTL, provisioned 5/5), `app-users`(userId, 5/5), Cognito User Pool. **SSM Parameter Store**(시크릿)는 예정.
- 리전 **ap-northeast-2**(서울).

## AWS 계정
- account `038832652275`, IAM user `kimbap`(AdministratorAccess 부여됨), **Paid 플랜**(기존 계정이라 $200 크레딧 없음 — always-free는 적용).

## 배포된 것 (현재)
- Lambda `tour-api`(Active, java21, **1024MB / Timeout 30s** — /popular이 느린 집중률 API를 호출해서 상향), 스택 `tour-api`(UPDATE_COMPLETE).
- Function URL(고정): `https://akt4wffwphw5czb3ofr2hy4hhm0emmil.lambda-url.ap-northeast-2.on.aws/`
- `GET /hello` → `"hello jaxrs"`. 콜드 ~3.9s / 워밍 ~수십ms. (AuthType NONE = 현재 공개)
- `GET /v1/tour/places?lat&lng[&radius&type&page&size]` → 위치기반 관광정보(TourAPI `locationBasedList2` 프록시). 라이브 검증됨(2026-07-01 배포).
- `GET /v1/tour/popular?lat&lng[&size]` → 좌표 주변 인기 관광지 **집중률 순위**(30일 평균). **DynamoDB 캐시 적용**(키 `popular#<signguCd>#<KST yyyyMMdd>`, TTL 26h, size 자르기 전 전체 순위 저장): 시군구당 하루 첫 1회만 느림(~10s) → 이후 ~1.5s(워밍 히트, 로그 "집중률 캐시 히트"로 검증, 2026-07-02). 남은 최적화: 히트 시에도 좌표→시군구 역지오코딩(locationBasedList2 ~1s)은 매번 호출 — L1 인메모리로 줄일 수 있음.
- `POST /v1/running/candidates` (러닝 Phase A) → 설문(lat·lng·distanceKm·shape[loop|oneway]·count) → 주변 관광지(타입 12·14·28) + 집중률 순위 매칭 후보. data.go.kr 4콜(타입3+지역)을 **전용 풀로 병렬화**(순차 6~12s → ~2s; Lambda 저코어라 commonPool 금지). 라이브 검증(2026-07-03): 콜드 6.8s / 워밍 0.4s.
- 신규 앱 흐름(로컬 구현, 배포 전): JWT 필수 `POST /v1/running/route-options`는 관광지 우선/거리 우선 최대 2개와 구간거리·Odii 도슨트 위치를 반환한다. Haversine 빔 탐색 후 TMAP 최대 5콜을 쓴다. 이 실시간 생성 API는 라우트 진입 기준 22초 deadline(JWT·콜드스타트 여유 8초), 사용자별 KST 일 3회·분당 6회, 동일 생성 입력 완성 응답 5분 캐시를 적용한다. TMAP 경로는 버전 키·약 10m 좌표 정규화·분산 single-flight와 함께 약관상 24시간 미만인 23시간 55분 캐시하고 Google 고도 결과는 별도 장기 캐시하지 않는다. 앱 UUID `Idempotency-Key`+요청 지문의 날짜 독립 상태 원장과 일일 쿼터를 DynamoDB 트랜잭션으로 예약한다. 중복 실행은 30초 임대 중 409(`Retry-After: 8`)로 막고, 모든 HTTP 시도와 500/502 뒤 실제 재실행은 분당 상한에 포함한다. 성공 JSON은 별도 압축 항목에 5분만 저장·재생한 뒤 같은 키를 410으로 종료한다. 500/502 정리는 소유자 조건의 단일 트랜잭션(최대 2초)이라 다른 성공 요청을 환불하지 않는다. 외부 HTTP timeout은 설정값과 absolute deadline 잔여시간 중 작은 값이며 단계 전환 때 시간이 없으면 후속 Elevation/Odii를 호출하지 않는다. DynamoDB는 2초 timeout·TTL 직접 검증·rate-limit 장애 fail-closed를 적용한다. `POST /v1/running/summary`는 선택 코스에 도착지 주변 TourAPI 음식점·카페를 붙인다.
- `GET /v1/courses[?city=도시명|cityId]` · `GET /v1/courses/{id}` → **편집 코스 카탈로그**(어디 뛰지 수집본 + 러닝갤 큐레이션 69코스, 홈 피드/상세 화면용). 목록은 실제 경유 `poi`와 이름·순서가 같은 `waypoints` 문자열 배열을 준다. 상세 `shape`은 `roundTrip|oneWay`, `poi`는 모두 경로 100m 안이며 멀리서 보는 관광 지점은 `landmarks`로 분리한다. `guide` 좌표는 `polyline` 위에 있고 편도의 마지막 guide는 실제 종점이다. **`poi`의 `n`은 사람이 읽는 지명이어야 한다** — 앱이 상세 "이런 곳을 지나요", 러닝 중 다음 목표 배너, 완주 기록 세 곳에 그대로 노출한다. 경로 정밀도를 높이려고 좌표를 경유지로 박으면 안 되고, 필요하면 경유지와 별개 필드로 논의할 것(2026-09-03 앱팀 합의). 데이터는 `src/main/resources/data/courses.json` **번들**(DB 없음) — 원본은 `running-courses/data/*.json`. 갱신 전후 `python3 running-courses/validate_courses.py`를 통과해야 하며 CI 배포 게이트에도 포함된다. 현재 id는 `seoul-banpo-10k` 형식이며 동일 코스로 확인된 iOS 구 ID 10개는 상세·GPX·주변 장소에서 alias로 지원한다.
- `GET /v1/courses/{id}/gpx` → 앱이 XML을 직접 만들지 않고 공유할 Garmin Connect 코스용 GPX 1.1 Track. `polyline`은 `trk/trkseg/trkpt`, 좌표가 있는 `poi`는 표준 `wpt`로 내보낸다. Garmin Connect가 외부 waypoint를 보존하지 않을 수 있으므로 경유지명 표시는 보장하지 않는다.
  ⚠️ 테스트 함정: 테스트용 MockEventServer가 큰(~24KB) 청크 응답을 종료하지 않아 read timeout — **전체 목록은 HTTP 테스트 금지, 서비스 레벨(CourseCatalog 주입)로 검증**(dev·실서버는 정상 확인).
- **도슨트 오디오**(2026-09-03): `checkpoints[].audio.{ko,en,jp}` 에 오디(Odii) mp3·대본, 최상위 `audioUrl`·`audioSeconds`(실제 길이)를 함께 준다. 69코스 273지점 중 140개에 소리가 있고 47개 코스가 재생된다. 앱 실기기 재생 확인됨.
  ⚠️ **오디오 주소는 `storyBasedList` 에만 있다** — `storyLocationBasedList` 는 `audioUrl` 이 항상 빈 문자열이라 위치 검색용으로도 쓰면 안 된다. 전체를 언어별로 받아 좌표로 직접 고른다(`running-courses/fetch_odii.py` → `refresh_stories.py`).
  ⚠️ 언어 키는 **`jp`**(`ja` 는 0건). 언어 간 연결은 **`stid`** — `stlid` 는 언어마다 다르다. 주소는 반드시 https(앱 ATS가 http를 https로 치환).
  ⚠️ **매칭 반경 250m 를 넓히지 말 것** — 앱 트리거 반경이 100m(ADR-031)라 더 넓히면 러너가 도착해도 안 열리거나 엉뚱한 이야기가 난다. 오디 콘텐츠가 없는 지점은 무음으로 두면 앱이 알아서 걸러낸다.
  ⚠️ 게이트가 `checkpoints ⊇ poi`(순서 유지)를 검사한다. 도슨트는 경유지 사이에 끼어들 수 있다.
- **lv·mood·scene·tags 는 정해진 어휘만 쓴다**(`validate_courses.py` 가 검사). 앱이 이 값으로 목록 배지와 필터를 만들어서, 새 값을 하나 넣으면 그 코스 혼자 있는 필터 칸이 생긴다. **늘릴 때는 배포 전에 앱팀에 알릴 것** — 앱이 어휘마다 번역 파일 3개(ko/en/zh-Hans)를 채워야 해서, 서버가 먼저 나가면 그동안 미번역으로 뜬다. `difficulty` 도 `ascentPerKm` 에서 자동 판정(10↓ 하 / 25↓ 중 / 초과 상)해 손으로 적은 값과 어긋나면 배포가 막힌다.
- Swagger UI `/q/swagger-ui` · 스펙 `/q/openapi` (현재 공개 노출 — 닫으려면 `quarkus.swagger-ui.always-include=false`).
- `GET /v1/courses/{id}/nearby[?radius]` → 코스 상세의 **뛰고 나서 들를 맛집/카페**. 기준점은 `polyline`의 마지막 좌표인 실제 완주 지점이며 최대 8곳을 반환한다. 경로가 없을 때만 마지막 poi→대표 좌표 순으로 폴백한다. 요청 반경에서 부족하면 2.5km→5km까지 자동 확장하고 실제 채택 반경을 `radiusM`으로 반환한다. 생성 코스 `POST /v1/running/summary`도 같은 확장 규칙과 응답 `nearbyRadiusM`을 쓴다. **1차 TourAPI(타입39) + 2차 네이버 지역검색(sort=comment=리뷰순) 교차검증** → `trust`: verified(양쪽) · trending(네이버만=최근 뜬 곳) · tour(관광공사만). 하루 캐시(`nearby-v3#<id>#<요청반경>#<KST날짜>`, TTL 26h, **빈 결과는 캐시 안 함**).
  ⚠️ **네이버 검색 API는 NAVER API HUB로 이관됨**(개발자센터 아님). 옛 방식으로 부르면 키가 맞아도 401 errorCode 024가 난다 — 실제로 이 함정에 한 번 빠졌다.
  - (옛) `openapi.naver.com/v1/search/local.json` + `X-Naver-Client-Id/Secret`
  - (현) `naverapihub.apigw.ntruss.com/search/v1/local` + `X-NCP-APIGW-API-KEY-ID/KEY` ← 키는 **NCP 콘솔**에서 발급. 개발자센터 등록 폼의 "사용 API" 목록엔 검색이 아예 없다.
  - 검색어 전략(실측): **동 > 장소명 > 시군구** 순. 잠수교 기준 "반포동 맛집"은 상위 5곳이 모두 1.3km 내였지만 "용산구 맛집"은 0곳, "잠수교 맛집"은 5km 밖 성수동이 나왔다. TourAPI 주소 괄호(`(반포동)`)에서 동을 뽑고, 없으면 경유지 이름, 그것도 없으면 시군구.
  - 정렬은 verified만 앞에 세우고 나머지는 거리순(트렌드를 무조건 위에 두면 더 가까운 가게가 밀려남).
- 키 5개는 SAM 파라미터(NoEcho) → Lambda 환경변수로 주입(깃 미포함): `TourApiKey→TOUR_API_KEY`, `TmapAppKey→TMAP_APP_KEY`, `GoogleMapsApiKey→GOOGLE_MAPS_API_KEY`, `NaverClientId→NAVER_CLIENT_ID`, `NaverClientSecret→NAVER_CLIENT_SECRET`(네이버는 선택 — 비어도 배포는 진행, CI가 warning만). Apple 폐기용 3종도 선택: `AppleTeamId→AUTH_APPLE_TEAM_ID`, `AppleKeyId→AUTH_APPLE_KEY_ID`, `ApplePrivateKey→AUTH_APPLE_PRIVATE_KEY`(**.p8은 여러 줄 PEM이라 sam `--parameter-overrides`에 그대로 못 싣는다** — 워크플로가 BEGIN/END 줄과 줄바꿈을 걷어내 base64 한 줄로 넘기고, `AppleTokens.parseP8`은 한 줄·PEM·문자 `\n` 셋 다 받는다). Odii 활용신청 후 `TourAudioEnabled=true`도 배포 파라미터에 넣는다. TMAP·Google 키는 **내부 전용**(응답/로그 노출 금지, `lib/TmapClient`·`lib/ElevationClient`).

## 배포됨 — 인증/로그인 (2026-07-07 배포, 2026-08-19 라이브 재확인)
- **이메일+카카오 인증 전체 구현·테스트 완료(34개 그린) 후 배포 완료.** UserPool(`app-users`, `ap-northeast-2_eaLSAOL0A`)·UserPoolClient(backend)·UsersTable(`app-users`, pk `userId`=sub) 모두 2026-07-07 12:48 생성됨.
- 라이브 검증(2026-08-19): `/v1/users/me` 무토큰 **401**, `/v1/auth/login` 빈 바디 **400**, OpenAPI에 인증 5개 경로 노출. UsersTable에 사용자 1명 존재. ⚠️ 문서가 한동안 '배포 대기'로 남아 있었음 — 배포 후 이 파일 갱신할 것.
- 엔드포인트: `POST /v1/auth/{signup,confirm,resend-code,login,logout,refresh,forgot-password,reset-password,kakao}` (공개) · `GET|PATCH|DELETE /v1/users/me` (`@Authenticated`, Cognito JWT). `/v1/tour/*` 등 기존 API는 계속 공개(단계적 전환 예정).
- **핵심 트릭**: ① username은 백엔드 생성 — 이메일 `email_<sha256(정규화 이메일) 32자>` / 카카오 `kakao_<회원번호>` (이메일을 username으로 안 쓴 이유: 카카오 공존 + 중복가입이 username 충돌로 차단). ② 카카오는 **연합 IdP 금지**(무료 50 MAU 함정) — 백엔드가 kapi `/v2/user/me`로 토큰 검증 후 일반 사용자로 연결, **AdminSetUserPassword 회전**(랜덤 64자 설정→즉시 로그인→폐기, 경합 시 1회 재시도)으로 토큰 발급 → 10,000 MAU 무료 유지. ③ JWT 검증은 `quarkus-smallrye-jwt`+Cognito JWKS(`mp.jwt.verify.*`, `${USER_POOL_ID:unset}` placeholder — 빈 default 금지 함정 회피).
- 프로필: 로그인 성공 시 idToken 클레임(sub/email/nickname)으로 UsersTable에 **putIfAbsent**(조건식 `attribute_not_exists` — 재로그인이 닉네임 안 덮음). 저장 실패는 로그인에 영향 없음(RankingCache 폴백 철학). env `USERS_TABLE`/`USER_POOL_ID`/`USER_POOL_CLIENT_ID`는 코드에서 `Optional<String>` 주입.
- **이메일 없는 소셜 사용자 경로 검증됨**(2026-09-02): 실 UserPool에 `AdminCreateUser`(nickname만, SUPPRESS) → `AdminSetUserPassword --permanent` → `ADMIN_USER_PASSWORD_AUTH`까지 돌려 **RefreshToken 포함 토큰 3종**을 받고 테스트 사용자를 지웠다. 카카오 이메일은 선택 동의라 안 와도 되고, 풀 스키마도 email을 필수로 잡지 않는다. `/apple`·`/kakao`가 이 흐름을 그대로 쓰므로 **소셜 로그인 응답에는 refreshToken이 항상 있다**.
  ⚠️ 단 `/v1/auth/refresh`는 refreshToken이 **null**이다(Cognito가 재발급 안 함) — 앱이 "refreshToken 없으면 실패"로 처리하면 자동로그인이 전부 깨진다. 그 규칙은 로그인 응답에만 적용할 것.
- **카카오 app_id 검사 켜짐**(2026-09-02): `auth.kakao.app-id=1436970` — 이 앱에서 발급된 토큰만 로그인되고 나머지는 401. `app_id`는 `/v2/user/me`에 **없고** `/v1/user/access_token_info`에만 있어서 카카오 콜이 1회 는다(그래서 옵션으로 뒀고, 줄을 지우면 검사가 꺼진다). 회원번호는 앱마다 다르게 발급돼 검사 없이도 남의 계정 탈취는 아니지만, 없으면 아무 앱 토큰으로나 가입된다.
  ⚠️ 네이티브 앱 키·REST API 키는 **서버가 쓰지 않는다** — 앱이 준 액세스 토큰만 검증하므로 저장할 이유가 없다. 앱 ID만 필요하고 그건 비밀이 아니다.
  ⚠️ 이 검사를 켜기 전에 만들어진 kakao 사용자 2명(8/25, 8/28)이 다른 앱 토큰에서 왔다면 재로그인이 막힌다. 회원번호가 앱마다 달라 어차피 새 계정이 생기던 상태라 실사용자면 확인할 것.
- 남은 실검증: 실제 카카오 앱 토큰으로 `/v1/auth/kakao` 왕복(앱에 네이티브 키가 아직 안 들어감). app_id 검사가 켜졌으니 첫 시도에서 401이 나면 `auth.kakao.app-id` 값부터 의심할 것.

## 계정 수명주기 (2026-08-23 구현, **배포됨**)
로그아웃·자동로그인·탈퇴·비밀번호 찾기·확인코드 재발송·프로필 수정 + Apple 토큰 폐기(2026-09-02 배포).
Apple 폐기는 **실기기 왕복까지 라이브 검증 완료**(2026-09-02 13:33 KST): 실제 앱 로그인 → 탈퇴로
`Apple refresh 토큰 교환 성공` → 18초 뒤 `Apple 토큰 폐기 완료`가 CloudWatch에 찍혔고, users 행과
Cognito 계정도 함께 사라졌다. 키는 `R2V626HA7Y`("Dali Sign in with Apple", primary App ID = `com.mega.dali`).
실사용자 없이 client_secret만 다시 확인하려면 `AppleTokens.parseP8`+`clientSecret`을 부르는 일회성 main으로
더미 토큰 revoke를 쳐 보면 된다 — `invalid_client`가 아니면(HTTP 200) 팀 ID·키 ID·번들 ID·ES256 서명이 맞는 것이다.
**진단은 로그로만 된다**(성공·실패 모두 응답에 안 드러남): 성공 `Apple refresh 토큰 교환 성공`/`Apple 토큰 폐기 완료`,
실패 `Apple code 교환 실패`/`Apple 토큰 폐기 실패`, 앱이 코드를 안 보내면 `authorizationCode 없음`.
⚠️ 이 기능 배포(2026-09-02) **전에 가입한 Apple 사용자는 저장된 토큰이 없어 탈퇴해도 폐기되지 않는다** — 재로그인하면 채워진다.
- **로그아웃** `POST /v1/auth/logout` — RevokeToken으로 refresh 토큰 폐기(발급된 access 토큰도 무효화). 이미 폐기된 토큰도 204. `EnableTokenRevocation`이 꺼지면 로그아웃이 조용히 무력화되므로 `UnsupportedTokenType`은 502로 드러낸다.
- **자동로그인** — 기존 `/v1/auth/refresh`가 그대로 쓰인다. 서버 쪽은 ① `RefreshTokenValidity` 30일(기본)→**365일**, ② refresh 때도 프로필 `putIfAbsent` 호출(로그인 시 저장 실패를 자가복구). provider는 요청만 봐선 몰라서 username 접두사로 되짚는다(`CognitoAuth.providerOfUsername`).
- **탈퇴** `DELETE /v1/users/me` — **Apple 토큰 폐기 → 프로필 행 → Cognito 계정 순서.** 행 삭제가 멱등이라 Cognito 삭제 실패 시 같은 요청 재시도가 통한다. 역순이면 재시도가 UserNotFound로 끊겨 남은 행을 치울 길이 없다. 발급된 access 토큰은 만료(1h)까지 유효 — 앱도 로컬 토큰을 지울 것.
- **Apple 토큰 폐기**(App Store 심사 5.1.1(v)) — 폐기에 필요한 Apple refresh 토큰은 `authorizationCode`로만 얻는데 코드가 **5분·1회용**이라 탈퇴 때까지 못 들고 있는다 → `POST /v1/auth/apple`의 **선택 필드 `authorizationCode`**를 받아 그 자리에서 교환해 users 행 `appleRefreshToken`에 넣어 두고, 탈퇴 때 `auth/revoke`로 폐기한다(`lib/AppleTokens`). **DELETE 요청 바디는 그대로 없음** — 앱은 로그인에만 필드를 추가하면 된다. 폐기가 맨 앞인 이유: 토큰이 프로필 행에 있어 행을 지우면 못 읽고, 뒤에 두면 Cognito 삭제 실패 후 재시도에서 영영 폐기 못 한다. 저장된 토큰이 없으면 no-op이라 username 접두사 분기가 필요 없다. 교환·폐기 실패는 전부 로그만 남기고 로그인/탈퇴를 막지 않는다.
  ⚠️ 함정: client_secret은 .p8로 서명한 **ES256 JWT**인데 JOSE는 서명을 R‖S 원시 64바이트로 요구한다. 기본 `SHA256withECDSA`는 DER을 뱉어 Apple이 `invalid_client`로 거절한다 → **`SHA256withECDSAinP1363Format`**(JDK 9+)을 쓰면 변환 없이 JOSE 형식이다.
  ⚠️ `client_id`는 **번들 ID `com.mega.dali`**(네이티브 앱은 Services ID가 아님) — identityToken 검증의 `aud`와 같은 값이라 `auth.apple.audience` 설정 하나를 공유한다.
- **비밀번호 찾기** `POST /v1/auth/forgot-password` → `/reset-password`. 카카오 사용자는 username이 `kakao_*`라 이메일로 조회되지 않아 자동으로 UserNotFound → 204. **provider 분기 불필요**(예전 메모 정정). 미확인 계정은 403 `user_not_confirmed`.
- **확인코드 재발송** `POST /v1/auth/resend-code` — 없는 계정 204(누설 금지), 이미 확인된 계정 400 `already_confirmed`, 한도 초과 429.
- **프로필 수정** `PATCH /v1/users/me` (닉네임, ≤20자) — Cognito 속성 → UsersTable 순서. 행이 없으면 404지만 Cognito는 이미 바뀌어 다음 로그인에 새 닉네임으로 생성됨. `UserStore.updateNickname`은 putIfAbsent와 달리 **실패를 삼키지 않는다**(명시적 변경이 조용히 사라지면 안 됨).
- 누설 방지 원칙: 계정 존재 여부는 `login`·`forgot-password`·`reset-password`·`resend-code(없는 계정)`에서 구분해 주지 않는다. 단 `signup` 409와 `resend-code` 400은 예외(이미 signup이 알려주므로 새 누설 없음 + 앱 UX 이득).
- 관리자 API용 username은 `cognito:username`(ID 토큰) → `username`(access 토큰) → `sub` 순서로 읽는다(`UserResource.cognitoUsername`).
- 추가된 IAM: `RevokeToken`, `AdminUpdateUserAttributes`, `AdminDeleteUser`.

## 운영 콘솔 (2026-09-03 배포)
- 화면 `/admin/index.html` — Lambda가 정적 서빙(새 인프라 없음). API는 `/v1/admin/*`.
- **인증은 앱과 같은 Cognito 계정**을 쓰고, idToken의 `email`이 `admin.emails`(env `ADMIN_EMAILS`, 쉼표 구분) 허용목록에 있어야 통과한다. **비어 있으면 전원 403**(기본 잠김) — 설정을 깜빡한 채 배포해도 열리지 않는다. `email_verified`도 검사한다(미확인 주소는 주인이라는 근거가 없음). 화면 HTML 자체는 공개지만 토큰 없이는 데이터가 안 나온다.
  ⚠️ 시크릿만 바꾸면 배포가 안 돈다 — 빈 커밋은 `paths-ignore`에 걸려 스킵된다. `gh workflow run deploy.yml --repo squarekimbap/backend --ref main`으로 수동 실행할 것.
- **저장소는 캐시 테이블에 얹었다**(`lib/AdminStore`, pk 접두사 `admin#`). **ttl 속성을 안 넣어 만료되지 않는다.** 새 테이블을 만들면 provisioned 합계가 always-free 25/25를 넘어 과금되기 때문. 캐시와 달리 **쓰기 실패는 던진다**(관리자가 누른 저장이 조용히 사라지면 안 됨).
- **코스 생성 제한을 화면에서 조절**(`services/AdminSettings`): 하루 N회·분당 N회. 저장값이 없으면 배포 설정값, 저장소 장애 시에도 설정값으로 폴백(제한을 못 읽었다고 서비스를 막지 않는다). 1~100/1~60 범위 밖은 거절. `RunningGenerationRateLimiter`가 요청마다 여기서 읽는다(60초 메모).
- **사용자별 한도와 남은 횟수**: users 행의 `dailyLimit`(숫자)이 있으면 그 사용자만 그 값을 쓰고, 없으면 전체 기본값(`AdminSettings.dailyLimitFor`). 오늘 쓴 횟수는 쿼터 항목의 `hits`를 읽는다 — 키는 `RunningGenerationRateLimiter.dailyQuotaKey()` 한 군데서만 만든다(`quota#running-generation#<userId>#<KST yyyyMMdd>`). 가입자 화면은 users 테이블 속성을 **전부** 내보내고(Apple 폐기 토큰은 값 없이 존재 여부만) 행을 열면 한도 수정·오늘 사용량 초기화가 있다.
  ⚠️ 한도를 올려도 오늘 이미 쓴 횟수는 그대로다 — 당장 풀어주려면 `DELETE /v1/admin/users/{id}/quota`로 사용량을 지워야 한다.
  ponytail: 목록이 사용자마다 쿼터 항목을 하나씩 읽는다. 가입자가 수백을 넘으면 목록에서 빼고 상세에서만 읽을 것.
- **코스 원고를 화면에서 수정**(`services/CourseOverrides`): 번들 JSON은 그대로 두고 **바뀐 필드만** 따로 저장해 읽을 때 덮는 오버레이 방식 — 코스를 DB로 옮기지 않으려고 이렇게 했다. 고칠 수 있는 건 앱이 보여주는 글과 사진뿐(`n·headline·subhead` 문자열, `body·deep·ops` 문단배열, `photo·photoTitle·photoLicense`). **경로·경유지·도슨트 좌표는 거절**한다(배포 게이트가 검증하고 앱의 100m 도슨트 트리거가 걸려 있음). 빈 값으로 저장하면 그 필드는 원본으로 되돌아간다.
  ⚠️ 메모가 60초라 저장 후 다른 Lambda 실행 환경에는 최대 1분 뒤 퍼진다.
- 화면 파일은 **한 벌**(`src/main/resources/META-INF/resources/admin/index.html`)이고 세 모드로 돈다: 배포본(로그인+편집), `admin/serve.py`(로컬, `~/.aws` 사용, 편집 불가), `--export`(단일 파일, 가입자·편집 없음).
  ⚠️ 전체 검수 목록은 응답이 커서 **HTTP 테스트 금지**(코스 목록과 같은 MockEventServer 함정) — 계산은 `CourseReviewTest`가 서비스 레벨로 본다.

## CI/CD (GitHub Actions)
- `.github/workflows/deploy.yml` — **main 푸시 → 테스트 → `sam deploy` → 스모크 테스트**. PR은 빌드/테스트만(AWS 미접근), 문서만 바뀌면 아예 안 돎(`paths-ignore`, 비공개 저장소라 Actions 분이 과금 대상 — 무료 2,000분/월).
- 인증은 **OIDC**(액세스 키 없음). 역할은 `infra/github-oidc.yaml`로 생성 — `github-actions-tour-api-deploy`, 신뢰 조건은 `repo:squarekimbap/backend:ref:refs/heads/main` 하나뿐. 권한은 PowerUserAccess + `tour-api-*` 역할 IAM 쓰기(PowerUser는 IAM을 막아서 SAM의 Lambda 실행 역할 생성이 실패함).
- GitHub Secrets 4개(필수): `AWS_DEPLOY_ROLE_ARN`, `TOUR_API_KEY`, `TMAP_APP_KEY`, `GOOGLE_MAPS_API_KEY`. 선택: `NAVER_CLIENT_ID`/`NAVER_CLIENT_SECRET`, `APPLE_TEAM_ID`/`APPLE_KEY_ID`/`APPLE_PRIVATE_KEY`(.p8 원문 그대로 붙여넣기 — 워크플로가 한 줄로 정리한다). 없으면 해당 기능만 꺼진 채 배포된다.
- **함정 방어**: `template.yaml`의 키 파라미터에 `Default: ""`가 있어 `--parameter-overrides` 없이 배포하면 **운영 키가 빈값으로 덮어써진다**. 워크플로가 배포 전 시크릿 공백을 검사하고, 배포 후 `/v1/tour/places` 200 · 무토큰 `/v1/users/me` 401을 실제로 호출해 확인한다.
- 나중에 GitHub Environment(승인 게이트)를 붙이면 OIDC `sub`가 `repo:...:environment:<이름>`으로 바뀌므로 `infra/github-oidc.yaml`의 신뢰 조건도 같이 고칠 것.

## ⚠️ 빌드 주의
Homebrew Maven이 JDK 25를 끌어와서, **빌드/실행 시 JAVA_HOME을 21로 지정**해야 한다:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```
(시스템 `java`는 21로 잡혀 있음. Android Studio는 자체 JBR 17 사용 — 영향 없음.)

kimdongjoo 맥(2026-07 확인): `mvn`·`sam`·`aws` CLI와 `~/.aws` 자격증명, `.env` 전부 없음 —
- Maven은 IntelliJ 내장으로 대체: `"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"`
- 테스트는 `TOUR_API_KEY=dummy-for-tests` env 필요(없으면 기동 실패). 예: `JAVA_HOME=$(/usr/libexec/java_home -v 21) TOUR_API_KEY=dummy-for-tests "<위 mvn 경로>" test -q`
- **배포하려면**: `brew install maven aws-sam-cli` + `aws configure`(IAM kimbap 키) 필요.

## 명령
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn quarkus:dev          # 로컬(자동리로드) → localhost:8080
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn clean package        # 빌드 → target/function.zip, target/sam.*.yaml
sam deploy --template-file template.yaml --stack-name tour-api \
  --region ap-northeast-2 --capabilities CAPABILITY_IAM --resolve-s3 --no-confirm-changeset
sam delete --stack-name tour-api --region ap-northeast-2
```

## 코딩 규칙
- 계층: `routes/`(얇게) → `services/`(외부 API 호출+가공) → `lib/`(정규화·거리·캐시·지역코드).
- 공공데이터 함정은 `lib`에 가둔다: `items.item` 배열/객체/빈값 정규화, `resultCode=="0000"` 체크, `mapX/mapx` 정규화, serviceKey 인코딩 통일.
- **TMAP·Google Elevation은 내부 전용**(앱에 노출 금지, 키 보호).
- 시크릿은 SSM/환경변수로, **코드/깃에 절대 안 박음**(`.gitignore`로 `.env`·`target/` 제외).
- 빌드 산출 SAM 템플릿(`target/sam.jvm.yaml`)은 매 빌드 덮어써지므로 쓰지 말고, 직접 관리하는 루트 `template.yaml`(Function URL 방식)을 쓴다.

## 사용자 컨텍스트
- 안드로이드 개발자 출신, Spring Boot/EC2 경험. **무료** 강하게 선호. **하면서 배우는** 방식(긴 학습 X). 한국어.
- **제품 앱은 iOS 전용**(안드로이드 앱 없음, 2026-07-07 확인). ⚠️ App Store 심사 지침 4.8: 카카오 같은 제3자 로그인을 제공하는 앱은 **Sign in with Apple(또는 동급 프라이버시 로그인)도 제공해야 함** → 애플 로그인 연동이 사실상 필수 후속 과제 (Cognito 네이티브 소셜 지원, 일반 MAU로 무료 한도 적용).
- 러닝/난이도 판정: km당 상승고도 10m↓=하 / 25m↓=중 / 초과=상.

## 현재 위치 (로드맵)
hello world 배포까지 완료. 다음: 공통 유틸 → 시크릿/지역코드 → 프록시 엔드포인트 → ~~캐시~~(✅ DynamoDB, /popular 적용) → ~~러닝 Phase A/B~~(✅ 배포됨, 2026-07-03) → 인증/로그인(Cognito)+DB → 나머지 프록시(festivals·images 등) → native/rate limit.
**앱 생성 흐름은 러닝 3개**(candidates→route-options→summary). `/tour/places`·`/tour/popular`는 앱 미사용이지만 유지하며 candidates·summary가 내부 로직을 재사용하므로 삭제 금지.
러닝 구현 메모: 후보 정렬은 집중률 순위 우선(이름 정규화 매칭: 완전일치→포함관계 4자↑), Phase B의 walkDurationS는 TMAP 도보 기준(러닝 환산은 앱 몫). `.env`는 `KEY=값` 형식 유지(예전에 `KEY ="값"` 형식이라 dev 로더가 TMAP/Google 키를 못 읽었음 — 정규화함, 2026-07-03).
캐시 구현 주의: `cache.table`은 env `CACHE_TABLE`로만 주입(MP-Config 자동 매핑). **properties에 `${CACHE_TABLE:}`로 쓰면 빈값이 '값 없음' 처리돼 기동 실패** → `RankingCache`는 `Optional<String>` 주입. 캐시 실패는 요청을 죽이지 않고 업스트림 폴백.
첫 실제 엔드포인트 `/v1/tour/places`(위치기반 관광정보, TourAPI `locationBasedList2` 프록시) 구현됨 — 호출엔 **공공데이터포털 인증키**(`TOUR_API_KEY`) 필요.
둘째 엔드포인트 `/v1/tour/popular`(좌표 주변 인기 관광지 **집중률 순위**, `TatsCnctrRateService/tatsCnctrRatedList`, 30일 평균 정렬) 구현됨(**배포됨**, 2026-07-01). 핵심 트릭:
- **좌표→시군구**: `locationBasedList2`의 `lDongRegnCd`(시도2) + `lDongSignguCd`(시군구3, 좌측 0패딩) 이어붙여 `signguCd`(5자리) 생성 → 추가 키 불필요(`lib/RegionResolver`).
- **집중률 서비스 함정**: base가 `TatsCnctrRateService`(별도), 파라미터 `areaCd`+`signguCd` 필수, **에러 응답이 flat**(`{resultCode,resultMsg}`) / 성공은 nested → `PublicData.ensureOk`가 둘 다 처리. 시군구당 (관광지×30일)행이라 페이지 수집 후 관광지별 집계.
