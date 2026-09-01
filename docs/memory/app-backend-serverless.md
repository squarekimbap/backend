---
name: app-backend-serverless
description: "User's full app backend — serverless on AWS (Lambda + Function URL + Quarkus/Java). Scope, stack, decisions, deployed resources, current state."
metadata: 
  node_type: memory
  type: project
  originSessionId: 77067790-e8b7-4f2d-875f-9451f16d0c00
---

User is building the **full serverless backend for an app** (not just tourism — that's the first feature domain). Covers auth/login, user DB, and feature APIs. Repo lives at `/Users/mega/Downloads/tour-api` (ASCII path — moved out of a Korean-named folder `서버` because the Korean path broke IntelliJ's launcher). GitHub: `https://github.com/squarekimbap/backend` (origin/main). Full working context is in the repo's `CLAUDE.md` and `README.md`.

**Stack (decided):** AWS Lambda `java21` + **Function URL** (NOT API Gateway — avoids its post-12-month cost, stays free) + **Quarkus 3.37** (`quarkus-amazon-lambda-http`) + RESTEasy/JAX-RS + **Maven** + **AWS SAM** (`template.yaml`). Planned: **Cognito** (login/auth), **DynamoDB** (user data + cache, provisioned 25/25), **SSM Parameter Store** (secrets). Region `ap-northeast-2`. AWS account `038832652275`, IAM user `kimbap` (AdministratorAccess), **Paid plan** (existing account → no $200 credit; always-free still applies).

**Deployed now:** Lambda `tour-api`, Function URL `https://akt4wffwphw5czb3ofr2hy4hhm0emmil.lambda-url.ap-northeast-2.on.aws/`, `GET /hello` → "hello jaxrs" (cold ~3.5s, warm ~52ms, AuthType NONE = public). Only the scaffold/pipeline is built; login/auth/DB/real endpoints are NOT yet implemented (planned).

**AWS always-free limits (both plans, indefinite):** Lambda 1M req + 400k GB-s/mo; DynamoDB 25GB + 25 RCU/WCU (provisioned only); Cognito 10,000 MAU/mo (Lite/Essentials, not Plus); SSM Parameter Store standard free; CloudWatch Logs 5GB/mo. S3 is NOT reliably free for this (existing) account. Google Elevation = only paid external API (5,000/mo free, or swap to Open-Meteo).

**Key gotchas:** build needs `JAVA_HOME=$(/usr/libexec/java_home -v 21)` (Homebrew Maven pulls JDK 25). Keep project on ASCII path. Gov-data API normalization (items.item array/object/empty, resultCode "0000", mapX/mapx, serviceKey encoding) and coords→areaCd geocoding must be done server-side. TMAP/Elevation stay internal. Running flow is candidates → user picks → authenticated route-options → user picks → summary. Route-options has a route-entry 22s deadline (8s reserved for JWT/cold start), KST 3/day/user + 6/min/user quota and 5m cache. 앱 UUID `Idempotency-Key`+요청 지문의 날짜 독립 상태 원장과 일일 쿼터를 트랜잭션으로 예약하고, 30초 임대 중 중복은 409(`Retry-After: 8`)로 막는다. 모든 HTTP 시도와 500/502 뒤 실제 재실행은 분당 상한에 포함한다. 성공 JSON은 별도 압축 항목에 5분만 저장·재생하고 이후 같은 키는 410이다. 실패 정리는 실행 소유자 조건의 단일 트랜잭션(최대 2초)이다. DDB calls time out at 2s and cache TTL is checked on read. See [[user-prefers-free-learn-by-doing]].
