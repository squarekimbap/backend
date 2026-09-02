#!/usr/bin/env python3
"""로컬 전용 관리 도구. 코스·사진 검수와 가입자 조회를 한 화면에서 한다.

의존성 없음(표준 라이브러리만). 코스는 저장소의 번들 JSON을 그대로 읽고,
사용자는 이 맥의 ~/.aws 자격증명으로 aws CLI를 태운다 — 서버에 관리자 API를
새로 뚫지 않으므로 인증 설계도, 배포도, 비용도 없다.

    python3 admin/serve.py        # → http://127.0.0.1:8787

⚠️ 127.0.0.1에만 바인드한다. 이 도구는 인증이 없으므로 외부에 열지 말 것.
"""

import http.server
import json
import math
import re
import subprocess
import sys
import webbrowser
from pathlib import Path

PORT = 8787
ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
# 화면은 한 벌만 유지한다 — 같은 파일을 Lambda도 정적으로 서빙한다
PAGE = REPO / "src/main/resources/META-INF/resources/admin/index.html"
COURSES = REPO / "src/main/resources/data/courses.json"
REGION = "ap-northeast-2"
USERS_TABLE = "app-users"
LAMBDA_NAME = "tour-api"

# 경유지가 경로에서 이만큼 넘게 떨어지면 계약 위반(validate_courses.py와 같은 기준)
OFF_ROUTE_M = 100
# 카드의 route sigil은 이 점수로 줄여 그린다. 형태만 보면 되므로 원본 200점은 과하다.
SIGIL_POINTS = 48


# ── 지오메트리 ──────────────────────────────────────────────────────

def haversine(lat1, lng1, lat2, lng2):
    r = 6371000
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def dist_to_route(polyline, lat, lng):
    """경로까지의 거리(m). 꼭짓점 기준 — validate_courses.py와 같은 방식."""
    if not polyline:
        return None
    return min(haversine(lat, lng, p[0], p[1]) for p in polyline)


def downsample(polyline, n):
    if len(polyline) <= n:
        return polyline
    step = (len(polyline) - 1) / (n - 1)
    return [polyline[round(i * step)] for i in range(n)]


# ── 코스 검수 ────────────────────────────────────────────────────────

def load_courses():
    return json.loads(COURSES.read_text(encoding="utf-8"))


def flags_for(course, photo_users):
    """이 코스에서 사람이 봐야 할 이유들. 빈 리스트면 볼 것 없음."""
    flags = []
    poly = course.get("polyline") or []

    off = [p for p in course.get("poi", [])
           if (d := dist_to_route(poly, p["lat"], p["lng"])) and d > OFF_ROUTE_M]
    if off:
        flags.append({"kind": "off-route", "label": f"경유지 {len(off)}곳이 경로 밖"})

    far = [p for p in course.get("poi", [])
           if p.get("placeLat") is not None
           and (d := dist_to_route(poly, p["placeLat"], p["placeLng"])) and d > OFF_ROUTE_M]
    if far:
        flags.append({"kind": "placelat",
                      "label": f"원 좌표 {len(far)}곳이 경로 밖 — 핀에 쓰면 어긋난다"})

    if len(photo_users.get(course.get("photo"), [])) > 1:
        others = [i for i in photo_users[course["photo"]] if i != course["id"]]
        flags.append({"kind": "dup-photo", "label": "사진 공유: " + ", ".join(others)})

    if "제3유형" in (course.get("photoLicense") or ""):
        flags.append({"kind": "license", "label": "공공누리 제3유형 — 변경(크롭) 금지"})

    # photoTitle의 괄호는 "다른 장소 사진으로 대신함" 같은 자진 신고다
    if "(" in (course.get("photoTitle") or ""):
        flags.append({"kind": "substitute", "label": "다른 장소 사진으로 대체됨"})

    return flags


def course_summaries():
    courses = load_courses()
    photo_users = {}
    for c in courses:
        photo_users.setdefault(c.get("photo"), []).append(c["id"])

    out = []
    for c in courses:
        poly = c.get("polyline") or []
        out.append({
            "id": c["id"],
            "city": c.get("city"),
            "headline": c.get("headline"),
            "km": c.get("km"),
            "shape": c.get("shape"),
            "difficulty": c.get("difficulty"),
            "photo": c.get("photo"),
            "photoTitle": c.get("photoTitle"),
            "sigil": downsample(poly, SIGIL_POINTS),
            "poiCount": len(c.get("poi", [])),
            "flags": flags_for(c, photo_users),
        })
    return out


def course_detail(course_id):
    for c in load_courses():
        if c["id"] != course_id:
            continue
        poly = c.get("polyline") or []
        poi = []
        for i, p in enumerate(c.get("poi", []), 1):
            place = None
            if p.get("placeLat") is not None:
                place = {
                    "lat": p["placeLat"], "lng": p["placeLng"],
                    "offRouteM": round(dist_to_route(poly, p["placeLat"], p["placeLng"]) or 0),
                }
            poi.append({
                "no": i, "name": p.get("n"), "addr": p.get("addr"),
                "lat": p["lat"], "lng": p["lng"],
                "offRouteM": round(dist_to_route(poly, p["lat"], p["lng"]) or 0),
                "place": place,
            })
        checkpoints = [{
            "no": i, "name": cp.get("name"), "lat": cp["lat"], "lng": cp["lng"],
            "offRouteM": round(dist_to_route(poly, cp["lat"], cp["lng"]) or 0),
        } for i, cp in enumerate(c.get("checkpoints", []), 1)]
        return {
            "id": c["id"], "city": c.get("city"), "headline": c.get("headline"),
            "subhead": c.get("subhead"), "km": c.get("km"), "min": c.get("min"),
            "shape": c.get("shape"), "difficulty": c.get("difficulty"),
            "ascentM": c.get("ascentM"), "ascentPerKm": c.get("ascentPerKm"),
            "photo": c.get("photo"), "photoTitle": c.get("photoTitle"),
            "photoLicense": c.get("photoLicense"), "src": c.get("src"),
            "polyline": poly, "poi": poi, "checkpoints": checkpoints,
        }
    return None


# ── 사용자 (aws CLI 경유) ───────────────────────────────────────────

class AwsError(Exception):
    pass


def aws(*args):
    proc = subprocess.run(["aws", *args, "--region", REGION],
                          capture_output=True, text=True, timeout=60)
    if proc.returncode != 0:
        raise AwsError((proc.stderr or proc.stdout).strip().splitlines()[-1:] or ["aws CLI 실패"])
    return json.loads(proc.stdout) if proc.stdout.strip() else {}


_pool_id = None


def pool_id():
    """User Pool ID를 배포된 Lambda 환경변수에서 읽어 온다 — 하드코딩이 굳는 걸 막는다."""
    global _pool_id
    if _pool_id is None:
        cfg = aws("lambda", "get-function-configuration",
                  "--function-name", LAMBDA_NAME, "--output", "json")
        _pool_id = cfg["Environment"]["Variables"]["USER_POOL_ID"]
    return _pool_id


def list_users():
    res = aws("dynamodb", "scan", "--table-name", USERS_TABLE, "--output", "json")
    users = []
    for item in res.get("Items", []):
        users.append({k: list(v.values())[0] for k, v in item.items()
                      if k != "appleRefreshToken"})
        # Apple 폐기 토큰은 값을 내보내지 않는다. 있는지만 알린다.
        users[-1]["hasAppleToken"] = "appleRefreshToken" in item
    users.sort(key=lambda u: u.get("createdAt", ""))
    return users


def delete_user(user_id):
    """서버의 탈퇴와 같은 순서: 프로필 행 → Cognito 계정.

    ⚠️ Apple 토큰 폐기는 하지 않는다. 그건 .p8 서명이 필요하고 앱의 탈퇴 경로에만 있다.
    """
    steps = []
    aws("dynamodb", "delete-item", "--table-name", USERS_TABLE,
        "--key", json.dumps({"userId": {"S": user_id}}), "--output", "json")
    steps.append("프로필 행 삭제")
    try:
        aws("cognito-idp", "admin-delete-user", "--user-pool-id", pool_id(),
            "--username", user_id, "--output", "json")
        steps.append("Cognito 계정 삭제")
    except AwsError as e:
        if "UserNotFoundException" not in str(e):
            raise
        steps.append("Cognito 계정 없음(이미 삭제됨)")
    return steps


# ── HTTP ────────────────────────────────────────────────────────────

class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "tour-api-admin"

    def log_message(self, fmt, *args):
        sys.stderr.write("  %s\n" % (fmt % args))

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        raw = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        try:
            if self.path == "/":
                html = PAGE.read_text(encoding="utf-8").replace(
                    "<script>", "<script>window.__LOCAL__=true;</script>\n<script>", 1
                ).encode()
                return self._send(200, html, "text/html; charset=utf-8")
            if self.path == "/api/courses":
                return self._send(200, course_summaries())
            if m := re.fullmatch(r"/api/courses/([\w-]+)", self.path):
                detail = course_detail(m.group(1))
                return self._send(200 if detail else 404,
                                  detail or {"error": "그런 코스가 없다"})
            if self.path == "/api/users":
                return self._send(200, {"users": list_users()})
            self._send(404, {"error": "없는 주소"})
        except AwsError as e:
            self._send(502, {"error": f"aws CLI 실패: {e}"})
        except Exception as e:
            self._send(500, {"error": f"{type(e).__name__}: {e}"})

    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(n) or "{}")
            if self.path == "/api/users/delete":
                uid = body.get("userId", "")
                if not uid:
                    return self._send(400, {"error": "userId가 없다"})
                return self._send(200, {"steps": delete_user(uid)})
            self._send(404, {"error": "없는 주소"})
        except AwsError as e:
            self._send(502, {"error": f"aws CLI 실패: {e}"})
        except Exception as e:
            self._send(500, {"error": f"{type(e).__name__}: {e}"})


def export(dest):
    """코스 검수 화면만 자체 완결 HTML 한 장으로 뽑는다.

    데이터를 파일 안에 넣으므로 서버 없이 어디서든 열린다. 가입자 탭은 자격증명이
    필요해 자동으로 빠진다 — 그래서 이 파일에는 비밀이 없고 남에게 줘도 된다.
    """
    summaries = course_summaries()
    details = {c["id"]: course_detail(c["id"]) for c in summaries}
    blob = json.dumps({"courses": summaries, "details": details}, ensure_ascii=False)
    html = PAGE.read_text(encoding="utf-8")
    # </script> 가 문자열 안에 들어가면 파서가 스크립트를 일찍 닫는다
    safe = blob.replace("</", "<\\/")
    html = html.replace("<script>", f"<script>window.__EMBED__={safe};</script>\n<script>", 1)
    Path(dest).write_text(html, encoding="utf-8")
    size = Path(dest).stat().st_size / 1e6
    print(f"{dest} ({size:.1f}MB) — 서버 없이 열리는 코스 검수본. 가입자 탭은 빠져 있다.")


def main():
    if not COURSES.exists():
        sys.exit(f"코스 번들을 찾을 수 없다: {COURSES}")
    if "--export" in sys.argv:
        i = sys.argv.index("--export")
        return export(sys.argv[i + 1] if len(sys.argv) > i + 1 else "코스검수.html")
    url = f"http://127.0.0.1:{PORT}"
    print(f"코스 검수 도구 → {url}   (끄려면 Ctrl+C)")
    webbrowser.open(url)
    http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print()
