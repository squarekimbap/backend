# 앱 인증 API 가이드

계정 수명주기 전체. 러닝 API는 [app-api-flow.md](app-api-flow.md) 참고.

```
[가입] signup → (메일 코드) → confirm ──┐
                    ↑ resend-code       ├→ [로그인] login ──→ 토큰 3종 보관
[카카오] kakao ─────────────────────────┘                      │
                                                               ├→ [자동로그인] refresh
[비번분실] forgot-password → reset-password → login            ├→ [로그아웃] logout
                                                               └→ [탈퇴] DELETE /v1/users/me
```

## 0. 공통

| 항목 | 값 |
| --- | --- |
| Base URL | `https://akt4wffwphw5czb3ofr2hy4hhm0emmil.lambda-url.ap-northeast-2.on.aws` |
| 인증 필요 | `/v1/users/*` 만. `Authorization: Bearer <accessToken>` |
| 에러 본문 | `{ "error": "코드", "message": "설명" }` (전 엔드포인트 공통) |

**토큰 3종** (`login`·`kakao`·`refresh` 응답):

| 필드 | 수명 | 앱 처리 |
| --- | --- | --- |
| `accessToken` | 1시간 | 보호 API 호출에 사용. 메모리 보관 |
| `idToken` | 1시간 | 사용자 정보 표시용(선택) |
| `refreshToken` | **365일** | **Keychain에 보관** — 자동로그인의 핵심. `refresh` 응답에는 `null`(재발급 안 함) |
| `expiresIn` | 초(3600) | accessToken 갱신 시점 계산 |

---

## 1. 가입 — `POST /v1/auth/signup`

`{ "email": "a@b.c", "password": "Abcd1234", "nickname": "닉" }` → **201**

비밀번호 정책: 8자 이상, 대문자·소문자·숫자 포함(기호 불필요).

| HTTP | error | 앱 처리 |
| --- | --- | --- |
| 400 | `weak_password` | 정책 안내 |
| 409 | `email_exists` | "이미 가입된 이메일 — 로그인하세요" |

가입하면 Cognito가 확인코드를 메일로 보낸다.

## 2. 가입 확인 — `POST /v1/auth/confirm`

`{ "email": "a@b.c", "code": "123456" }` → **204**

| HTTP | error | 앱 처리 |
| --- | --- | --- |
| 400 | `invalid_code` | "코드가 틀리거나 만료됨" + 재발송 버튼 노출 |

## 3. 확인코드 재발송 — `POST /v1/auth/resend-code`

`{ "email": "a@b.c" }` → **204**

| HTTP | error | 앱 처리 |
| --- | --- | --- |
| 400 | `already_confirmed` | "이미 인증됨" → 로그인 화면으로 |
| 429 | `too_many_requests` | "잠시 후 다시" (버튼 쿨다운 60초 권장) |

> 없는 이메일도 **204**로 온다(가입 여부를 캐낼 수 없게). "코드를 보냈습니다"로 안내하면 된다.

## 4. 로그인 — `POST /v1/auth/login`

`{ "email": "a@b.c", "password": "Abcd1234" }` → **200** + 토큰 3종

| HTTP | error | 앱 처리 |
| --- | --- | --- |
| 401 | `invalid_credentials` | "이메일 또는 비밀번호가 올바르지 않습니다" (틀린 비번/없는 계정 구분 안 됨 — 의도된 것) |
| 403 | `user_not_confirmed` | 가입 확인 화면으로 이동 + 재발송 |

## 5. 카카오 로그인 — `POST /v1/auth/kakao`

`{ "kakaoAccessToken": "<카카오 SDK 토큰>" }` → **200** + 토큰 3종. **첫 로그인이 곧 가입**(별도 signup 없음).

| HTTP | error | 앱 처리 |
| --- | --- | --- |
| 401 | `invalid_kakao_token` | 카카오 재인증 |
| 502 | `upstream_error` | 1회 재시도 후 안내 |

> ⚠️ 아직 실제 앱 토큰으로 검증되지 않은 경로다. 연동 시 서버 로그를 함께 확인할 것.

## 6. 자동로그인 — `POST /v1/auth/refresh`

`{ "refreshToken": "<보관한 토큰>" }` → **200** + 토큰(단 `refreshToken`은 `null`)

앱 시작 시 흐름:

```
Keychain에 refreshToken 있음? ─ 아니오 → 로그인 화면
        │ 예
        ↓
POST /v1/auth/refresh ─ 401 → refreshToken 폐기 후 로그인 화면
        │ 200
        ↓
accessToken 메모리 보관 → 홈 화면
```

| HTTP | error | 앱 처리 |
| --- | --- | --- |
| 401 | `invalid_refresh_token` | **저장된 토큰 삭제 후 로그인 화면**(만료·로그아웃·탈퇴 모두 여기로) |

## 7. 로그아웃 — `POST /v1/auth/logout`

`{ "refreshToken": "<보관한 토큰>" }` → **204**

- 서버가 refresh 토큰을 폐기한다 → 그 세션의 accessToken도 함께 무효.
- 이미 폐기된 토큰도 204. **앱은 응답과 무관하게 로컬 토큰을 지울 것.**

## 8. 비밀번호 찾기 — `POST /v1/auth/forgot-password` → `/reset-password`

**① 코드 발송**: `{ "email": "a@b.c" }` → **204**

| HTTP | error | 앱 처리 |
| --- | --- | --- |
| 403 | `user_not_confirmed` | 가입 확인이 먼저 필요 → 재발송 화면 |
| 429 | `too_many_requests` | 쿨다운 |

> 없는 이메일·카카오 가입자도 **204**. "메일을 확인하세요"로 안내한다(카카오 사용자는 메일이 오지 않으므로, 카카오 로그인 버튼도 함께 보여줄 것).

**② 새 비밀번호 적용**: `{ "email": "a@b.c", "code": "123456", "newPassword": "Newpw1234" }` → **204**

| HTTP | error | 앱 처리 |
| --- | --- | --- |
| 400 | `invalid_code` | "코드가 틀리거나 만료됨" |
| 400 | `weak_password` | 정책 안내 |
| 429 | `too_many_requests` | "시도가 너무 많습니다" |

성공 후엔 새 비밀번호로 `login`을 다시 호출한다(토큰을 여기서 주지 않음).

---

## 9. 내 프로필 — `GET /v1/users/me` 🔒

`Authorization: Bearer <accessToken>` → **200**

```json
{ "userId": "sub-값", "email": "a@b.c", "nickname": "닉",
  "provider": "email", "createdAt": "2026-08-23T04:00:00Z" }
```

| 필드 | 비고 |
| --- | --- |
| `email` | **카카오 사용자는 `null`** |
| `provider` | `email` 또는 `kakao` |

| HTTP | error | 앱 처리 |
| --- | --- | --- |
| 401 | — | accessToken 만료 → `refresh` 후 1회 재시도 |
| 404 | `profile_not_found` | 드묾. `refresh` 한 번 호출하면 서버가 복구한다 |

## 10. 프로필 수정 — `PATCH /v1/users/me` 🔒

`{ "nickname": "새닉" }` → **200** + 수정된 프로필 전체

- 닉네임 1~20자(앞뒤 공백은 서버가 제거).

| HTTP | error | 앱 처리 |
| --- | --- | --- |
| 400 | `bad_request` | 길이·빈값 — UI에서 먼저 막을 것 |
| 404 | `profile_not_found` | `refresh` 후 재시도 |
| 502 | `upstream_error` | 1회 재시도 후 안내 |

## 11. 탈퇴 — `DELETE /v1/users/me` 🔒

바디 없음 → **204**

- 프로필과 계정이 모두 삭제된다. **복구 불가** — 실행 전 확인 다이얼로그 필수.
- 이미 지워진 계정도 204(멱등).
- ⚠️ 발급된 accessToken은 만료(최대 1시간)까지 기술적으로 유효하다. **앱이 로컬 토큰을 즉시 지워야 한다.**

| HTTP | error | 앱 처리 |
| --- | --- | --- |
| 502 | `upstream_error` | 재시도(멱등하므로 안전) |

---

## 체크리스트

- [ ] `refreshToken`은 Keychain(암호화), `accessToken`은 메모리만
- [ ] 401 → `refresh` 1회 → 그래도 401이면 로그아웃 처리(무한 루프 금지)
- [ ] 로그아웃·탈퇴는 **응답과 무관하게** 로컬 토큰 삭제
- [ ] `email`·`nickname`은 null 가능 → nullable로 선언
- [ ] 재발송·비번찾기 버튼에 쿨다운(429 방지)
- [ ] 탈퇴는 확인 다이얼로그 + "복구 불가" 문구
