# CLAUDE.md — 프로젝트 작업 컨텍스트

이 파일은 향후 작업 시 빠르게 맥락을 잡기 위한 메모다. (사람용 개요는 [README.md](README.md))

## 이 프로젝트가 뭔가
한 앱의 **전체 백엔드 서버**(서버리스). 인증/로그인 + 사용자 DB + 기능 API.
**첫 기능 도메인 = 관광/러닝 추천**(외부 API 프록시·집계). 관광은 전부가 아니라 첫 도메인.

## 위치 · 원격
- 로컬: `/Users/mega/Downloads/tour-api` — **반드시 ASCII 경로.** 한글 폴더(`서버`)에 두면 IntelliJ 런처가 깨짐(이전에 발생, 그래서 이리로 이동).
- GitHub: `https://github.com/squarekimbap/backend` (origin/main, HTTPS, 자격증명 store됨)

## 스택 (결정 + 이유)
- **AWS Lambda (java21) + Function URL** — Function URL은 API Gateway의 12개월-후-과금을 피하려고 선택(무료 유지).
- **Java 21 + Quarkus 3.37** (`quarkus-amazon-lambda-http`) + **RESTEasy(JAX-RS)**. Java는 사용자의 Spring/Java 경험 활용. Quarkus는 콜드스타트(native) 목적.
- **Maven** 빌드, **AWS SAM**(`template.yaml`) 배포.
- **DynamoDB 캐시 도입됨**: 테이블 `tour-api-cache`(pk 단일키, TTL 속성 `ttl`, provisioned 5/5 — always-free 25/25 한도 내). *(예정)* **Cognito**(로그인/인증), **DynamoDB 사용자 데이터**, **SSM Parameter Store**(시크릿).
- 리전 **ap-northeast-2**(서울).

## AWS 계정
- account `038832652275`, IAM user `kimbap`(AdministratorAccess 부여됨), **Paid 플랜**(기존 계정이라 $200 크레딧 없음 — always-free는 적용).

## 배포된 것 (현재)
- Lambda `tour-api`(Active, java21, **1024MB / Timeout 30s** — /popular이 느린 집중률 API를 호출해서 상향), 스택 `tour-api`(UPDATE_COMPLETE).
- Function URL(고정): `https://akt4wffwphw5czb3ofr2hy4hhm0emmil.lambda-url.ap-northeast-2.on.aws/`
- `GET /hello` → `"hello jaxrs"`. 콜드 ~3.9s / 워밍 ~수십ms. (AuthType NONE = 현재 공개)
- `GET /v1/tour/places?lat&lng[&radius&type&page&size]` → 위치기반 관광정보(TourAPI `locationBasedList2` 프록시). 라이브 검증됨(2026-07-01 배포).
- `GET /v1/tour/popular?lat&lng[&size]` → 좌표 주변 인기 관광지 **집중률 순위**(30일 평균). **DynamoDB 캐시 적용**(키 `popular#<signguCd>#<KST yyyyMMdd>`, TTL 26h, size 자르기 전 전체 순위 저장): 시군구당 하루 첫 1회만 느림(~10s) → 이후 ~1.5s(워밍 히트, 로그 "집중률 캐시 히트"로 검증, 2026-07-02). 남은 최적화: 히트 시에도 좌표→시군구 역지오코딩(locationBasedList2 ~1s)은 매번 호출 — L1 인메모리로 줄일 수 있음.
- Swagger UI `/q/swagger-ui` · 스펙 `/q/openapi` (현재 공개 노출 — 닫으려면 `quarkus.swagger-ui.always-include=false`).
- 인증키는 SAM 파라미터 `TourApiKey`(NoEcho) → Lambda 환경변수 `TOUR_API_KEY`로 주입(깃 미포함). 배포: `sam deploy ... --parameter-overrides TourApiKey=<디코딩키>`.

## ⚠️ 빌드 주의
Homebrew Maven이 JDK 25를 끌어와서, **빌드/실행 시 JAVA_HOME을 21로 지정**해야 한다:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```
(시스템 `java`는 21로 잡혀 있음. Android Studio는 자체 JBR 17 사용 — 영향 없음.)

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
- 안드로이드 개발자, Spring Boot/EC2 경험. **무료** 강하게 선호. **하면서 배우는** 방식(긴 학습 X). 한국어.
- 러닝/난이도 판정: km당 상승고도 10m↓=하 / 25m↓=중 / 초과=상.

## 현재 위치 (로드맵)
hello world 배포까지 완료. 다음: 공통 유틸 → 시크릿/지역코드 → 프록시 엔드포인트 → ~~캐시~~(✅ DynamoDB, /popular 적용) → 인증/로그인(Cognito)+DB → 러닝 Phase A/B → native/rate limit.
캐시 구현 주의: `cache.table`은 env `CACHE_TABLE`로만 주입(MP-Config 자동 매핑). **properties에 `${CACHE_TABLE:}`로 쓰면 빈값이 '값 없음' 처리돼 기동 실패** → `RankingCache`는 `Optional<String>` 주입. 캐시 실패는 요청을 죽이지 않고 업스트림 폴백.
첫 실제 엔드포인트 `/v1/tour/places`(위치기반 관광정보, TourAPI `locationBasedList2` 프록시) 구현됨 — 호출엔 **공공데이터포털 인증키**(`TOUR_API_KEY`) 필요.
둘째 엔드포인트 `/v1/tour/popular`(좌표 주변 인기 관광지 **집중률 순위**, `TatsCnctrRateService/tatsCnctrRatedList`, 30일 평균 정렬) 구현됨(**배포됨**, 2026-07-01). 핵심 트릭:
- **좌표→시군구**: `locationBasedList2`의 `lDongRegnCd`(시도2) + `lDongSignguCd`(시군구3, 좌측 0패딩) 이어붙여 `signguCd`(5자리) 생성 → 추가 키 불필요(`lib/RegionResolver`).
- **집중률 서비스 함정**: base가 `TatsCnctrRateService`(별도), 파라미터 `areaCd`+`signguCd` 필수, **에러 응답이 flat**(`{resultCode,resultMsg}`) / 성공은 nested → `PublicData.ensureOk`가 둘 다 처리. 시군구당 (관광지×30일)행이라 페이지 수집 후 관광지별 집계.
