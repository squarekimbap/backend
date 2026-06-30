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

**Key gotchas:** build needs `JAVA_HOME=$(/usr/libexec/java_home -v 21)` (Homebrew Maven pulls JDK 25). Keep project on ASCII path. Gov-data API normalization (items.item array/object/empty, resultCode "0000", mapX/mapx, serviceKey encoding) and coords→areaCd geocoding must be done server-side. TMAP/Elevation kept internal (keys hidden). Running recommendation is a **2-phase** flow (candidates → user picks → routes). See [[user-prefers-free-learn-by-doing]].
