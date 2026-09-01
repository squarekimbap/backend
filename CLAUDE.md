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
- 신규 앱 흐름(로컬 구현, 배포 전): JWT 필수 `POST /v1/running/route-options`는 관광지 우선/거리 우선 최대 2개와 구간거리·Odii 도슨트 위치를 반환한다. Haversine 빔 탐색 후 TMAP 최대 5콜을 쓴다. 이 실시간 생성 API는 라우트 진입 기준 22초 deadline(JWT·콜드스타트 여유 8초), 사용자별 KST 일 3회·분당 6회, 동일 입력 5분 캐시를 적용한다. 앱 UUID `Idempotency-Key`+요청 지문의 날짜 독립 상태 원장과 일일 쿼터를 DynamoDB 트랜잭션으로 예약한다. 중복 실행은 30초 임대 중 409(`Retry-After: 8`)로 막고, 모든 HTTP 시도와 500/502 뒤 실제 재실행은 분당 상한에 포함한다. 성공 JSON은 별도 압축 항목에 5분만 저장·재생한 뒤 같은 키를 410으로 종료한다. 500/502 정리는 소유자 조건의 단일 트랜잭션(최대 2초)이라 다른 성공 요청을 환불하지 않는다. 외부 HTTP timeout은 설정값과 absolute deadline 잔여시간 중 작은 값이며 단계 전환 때 시간이 없으면 후속 Elevation/Odii를 호출하지 않는다. DynamoDB는 2초 timeout·TTL 직접 검증·rate-limit 장애 fail-closed를 적용한다. `POST /v1/running/summary`는 선택 코스에 도착지 주변 TourAPI 음식점·카페를 붙인다.
- `GET /v1/courses[?city=도시명|cityId]` · `GET /v1/courses/{id}` → **편집 코스 카탈로그**(어디 뛰지 수집본 + 러닝갤 큐레이션 64코스·32도시, 홈 피드/상세 화면용). 목록은 거리 대신 홈 카드에 표시할 `waypoints` 이름 배열을 함께 준다. 데이터는 `src/main/resources/data/courses.json` **번들**(DB 없음) — 원본은 `running-courses/data/*.json`(build.py 산출물 courses.json을 복사). 갱신 = 재복사 후 배포. id는 수집본 그대로(`seoul-banpo-10k` 형식, 42개 유일성 검증됨).
- `GET /v1/courses/{id}/gpx` → 앱이 XML을 직접 만들지 않고 공유할 Garmin Connect 코스용 GPX 1.1 Track. `polyline`은 `trk/trkseg/trkpt`, 좌표가 있는 `poi`는 표준 `wpt`로 내보낸다. Garmin Connect가 외부 waypoint를 보존하지 않을 수 있으므로 경유지명 표시는 보장하지 않는다.
  ⚠️ 테스트 함정: 테스트용 MockEventServer가 큰(~24KB) 청크 응답을 종료하지 않아 read timeout — **전체 목록은 HTTP 테스트 금지, 서비스 레벨(CourseCatalog 주입)로 검증**(dev·실서버는 정상 확인).
- Swagger UI `/q/swagger-ui` · 스펙 `/q/openapi` (현재 공개 노출 — 닫으려면 `quarkus.swagger-ui.always-include=false`).
- `GET /v1/courses/{id}/nearby[?radius]` → 코스 상세의 **주변 맛집/카페**. 기준점은 좌표가 있는 마지막 poi. **1차 TourAPI(타입39) + 2차 네이버 지역검색(sort=comment=리뷰순) 교차검증** → `trust`: verified(양쪽) · trending(네이버만=최근 뜬 곳) · tour(관광공사만). 하루 캐시(`nearby#<id>#<반경>#<KST날짜>`, TTL 26h, **빈 결과는 캐시 안 함**). 좌표 미확보 코스(64개 중 9개)는 `count:0`으로 조용히 내려감.
  ⚠️ **네이버 검색 API는 NAVER API HUB로 이관됨**(개발자센터 아님). 옛 방식으로 부르면 키가 맞아도 401 errorCode 024가 난다 — 실제로 이 함정에 한 번 빠졌다.
  - (옛) `openapi.naver.com/v1/search/local.json` + `X-Naver-Client-Id/Secret`
  - (현) `naverapihub.apigw.ntruss.com/search/v1/local` + `X-NCP-APIGW-API-KEY-ID/KEY` ← 키는 **NCP 콘솔**에서 발급. 개발자센터 등록 폼의 "사용 API" 목록엔 검색이 아예 없다.
  - 검색어 전략(실측): **동 > 장소명 > 시군구** 순. 잠수교 기준 "반포동 맛집"은 상위 5곳이 모두 1.3km 내였지만 "용산구 맛집"은 0곳, "잠수교 맛집"은 5km 밖 성수동이 나왔다. TourAPI 주소 괄호(`(반포동)`)에서 동을 뽑고, 없으면 경유지 이름, 그것도 없으면 시군구.
  - 정렬은 verified만 앞에 세우고 나머지는 거리순(트렌드를 무조건 위에 두면 더 가까운 가게가 밀려남).
- 키 5개는 SAM 파라미터(NoEcho) → Lambda 환경변수로 주입(깃 미포함): `TourApiKey→TOUR_API_KEY`, `TmapAppKey→TMAP_APP_KEY`, `GoogleMapsApiKey→GOOGLE_MAPS_API_KEY`, `NaverClientId→NAVER_CLIENT_ID`, `NaverClientSecret→NAVER_CLIENT_SECRET`(네이버는 선택 — 비어도 배포는 진행, CI가 warning만). Odii 활용신청 후 `TourAudioEnabled=true`도 배포 파라미터에 넣는다. TMAP·Google 키는 **내부 전용**(응답/로그 노출 금지, `lib/TmapClient`·`lib/ElevationClient`).

## 배포됨 — 인증/로그인 (2026-07-07 배포, 2026-08-19 라이브 재확인)
- **이메일+카카오 인증 전체 구현·테스트 완료(34개 그린) 후 배포 완료.** UserPool(`app-users`, `ap-northeast-2_eaLSAOL0A`)·UserPoolClient(backend)·UsersTable(`app-users`, pk `userId`=sub) 모두 2026-07-07 12:48 생성됨.
- 라이브 검증(2026-08-19): `/v1/users/me` 무토큰 **401**, `/v1/auth/login` 빈 바디 **400**, OpenAPI에 인증 5개 경로 노출. UsersTable에 사용자 1명 존재. ⚠️ 문서가 한동안 '배포 대기'로 남아 있었음 — 배포 후 이 파일 갱신할 것.
- 엔드포인트: `POST /v1/auth/{signup,confirm,resend-code,login,logout,refresh,forgot-password,reset-password,kakao}` (공개) · `GET|PATCH|DELETE /v1/users/me` (`@Authenticated`, Cognito JWT). `/v1/tour/*` 등 기존 API는 계속 공개(단계적 전환 예정).
- **핵심 트릭**: ① username은 백엔드 생성 — 이메일 `email_<sha256(정규화 이메일) 32자>` / 카카오 `kakao_<회원번호>` (이메일을 username으로 안 쓴 이유: 카카오 공존 + 중복가입이 username 충돌로 차단). ② 카카오는 **연합 IdP 금지**(무료 50 MAU 함정) — 백엔드가 kapi `/v2/user/me`로 토큰 검증 후 일반 사용자로 연결, **AdminSetUserPassword 회전**(랜덤 64자 설정→즉시 로그인→폐기, 경합 시 1회 재시도)으로 토큰 발급 → 10,000 MAU 무료 유지. ③ JWT 검증은 `quarkus-smallrye-jwt`+Cognito JWKS(`mp.jwt.verify.*`, `${USER_POOL_ID:unset}` placeholder — 빈 default 금지 함정 회피).
- 프로필: 로그인 성공 시 idToken 클레임(sub/email/nickname)으로 UsersTable에 **putIfAbsent**(조건식 `attribute_not_exists` — 재로그인이 닉네임 안 덮음). 저장 실패는 로그인에 영향 없음(RankingCache 폴백 철학). env `USERS_TABLE`/`USER_POOL_ID`/`USER_POOL_CLIENT_ID`는 코드에서 `Optional<String>` 주입.
- 남은 실검증: 카카오 로그인(`/v1/auth/kakao`)은 실제 앱 토큰이 필요해 아직 미확인 — 특히 `AdminCreateUser`로 이메일 없이 kakao_* 사용자를 만드는 경로.

## 계정 수명주기 (2026-08-23 구현, **배포 대기**)
로그아웃·자동로그인·탈퇴·비밀번호 찾기·확인코드 재발송·프로필 수정. 테스트 65개 그린, 라이브 미검증.
- **로그아웃** `POST /v1/auth/logout` — RevokeToken으로 refresh 토큰 폐기(발급된 access 토큰도 무효화). 이미 폐기된 토큰도 204. `EnableTokenRevocation`이 꺼지면 로그아웃이 조용히 무력화되므로 `UnsupportedTokenType`은 502로 드러낸다.
- **자동로그인** — 기존 `/v1/auth/refresh`가 그대로 쓰인다. 서버 쪽은 ① `RefreshTokenValidity` 30일(기본)→**365일**, ② refresh 때도 프로필 `putIfAbsent` 호출(로그인 시 저장 실패를 자가복구). provider는 요청만 봐선 몰라서 username 접두사로 되짚는다(`CognitoAuth.providerOfUsername`).
- **탈퇴** `DELETE /v1/users/me` — **프로필 행 → Cognito 계정 순서.** 행 삭제가 멱등이라 Cognito 삭제 실패 시 같은 요청 재시도가 통한다. 역순이면 재시도가 UserNotFound로 끊겨 남은 행을 치울 길이 없다. 발급된 access 토큰은 만료(1h)까지 유효 — 앱도 로컬 토큰을 지울 것.
- **비밀번호 찾기** `POST /v1/auth/forgot-password` → `/reset-password`. 카카오 사용자는 username이 `kakao_*`라 이메일로 조회되지 않아 자동으로 UserNotFound → 204. **provider 분기 불필요**(예전 메모 정정). 미확인 계정은 403 `user_not_confirmed`.
- **확인코드 재발송** `POST /v1/auth/resend-code` — 없는 계정 204(누설 금지), 이미 확인된 계정 400 `already_confirmed`, 한도 초과 429.
- **프로필 수정** `PATCH /v1/users/me` (닉네임, ≤20자) — Cognito 속성 → UsersTable 순서. 행이 없으면 404지만 Cognito는 이미 바뀌어 다음 로그인에 새 닉네임으로 생성됨. `UserStore.updateNickname`은 putIfAbsent와 달리 **실패를 삼키지 않는다**(명시적 변경이 조용히 사라지면 안 됨).
- 누설 방지 원칙: 계정 존재 여부는 `login`·`forgot-password`·`reset-password`·`resend-code(없는 계정)`에서 구분해 주지 않는다. 단 `signup` 409와 `resend-code` 400은 예외(이미 signup이 알려주므로 새 누설 없음 + 앱 UX 이득).
- 관리자 API용 username은 `cognito:username`(ID 토큰) → `username`(access 토큰) → `sub` 순서로 읽는다(`UserResource.cognitoUsername`).
- 추가된 IAM: `RevokeToken`, `AdminUpdateUserAttributes`, `AdminDeleteUser`.

## CI/CD (GitHub Actions)
- `.github/workflows/deploy.yml` — **main 푸시 → 테스트 → `sam deploy` → 스모크 테스트**. PR은 빌드/테스트만(AWS 미접근), 문서만 바뀌면 아예 안 돎(`paths-ignore`, 비공개 저장소라 Actions 분이 과금 대상 — 무료 2,000분/월).
- 인증은 **OIDC**(액세스 키 없음). 역할은 `infra/github-oidc.yaml`로 생성 — `github-actions-tour-api-deploy`, 신뢰 조건은 `repo:squarekimbap/backend:ref:refs/heads/main` 하나뿐. 권한은 PowerUserAccess + `tour-api-*` 역할 IAM 쓰기(PowerUser는 IAM을 막아서 SAM의 Lambda 실행 역할 생성이 실패함).
- GitHub Secrets 4개: `AWS_DEPLOY_ROLE_ARN`, `TOUR_API_KEY`, `TMAP_APP_KEY`, `GOOGLE_MAPS_API_KEY`.
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
