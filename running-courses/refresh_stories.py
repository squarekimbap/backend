#!/usr/bin/env python3
"""코스 체크포인트에 오디(Odii) 도슨트 오디오를 붙인다. 경로·경유지는 건드리지 않는다.

    python3 running-courses/fetch_odii.py      # 먼저 이야기 캐시
    python3 running-courses/refresh_stories.py

동작
  1. 기존 체크포인트(경유지 자리)에서 MATCH_RADIUS 안에 이야기가 있으면 그 오디오를 붙인다.
  2. 경로에서 MATCH_RADIUS 안이면서 기존 체크포인트와 STORY_MIN_GAP 이상 떨어진 이야기는
     새 체크포인트로 더한다(코스당 STORY_LIMIT 개까지). 경로 진행 순서에 맞춰 끼워 넣는다.

기존 체크포인트를 지우지 않는 이유: 앱이 그 좌표로 도슨트 진입을 판정하고 있고,
배포 게이트가 경유지 이름이 체크포인트에 순서대로 들어 있기를 요구한다. 새 이야기는
그 사이에 끼워 넣으므로 경유지 부분수열의 순서는 그대로다.

오디오가 없는 체크포인트는 그대로 둔다 — 그 자리에 오디 콘텐츠가 아예 없다는 뜻이다.
"""

import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / ".cache"
BUNDLE = ROOT.parent / "src/main/resources/data/courses.json"
BUILT = ROOT / "courses.json"
DATA = ROOT / "data"

LANGS = ("ko", "en", "jp")
# 앱 OdiiClient 와 같은 값을 써야 서버가 고른 지점과 앱이 트리거하는 지점이 어긋나지 않는다
MATCH_RADIUS = 250      # 이야기를 그 지점의 것으로 볼 최대 거리(m)
STORY_MIN_GAP = 300     # 체크포인트끼리 최소 간격(m) — 붙어 있으면 연달아 재생된다
STORY_LIMIT = 8         # 코스당 새로 더할 이야기 수 상한


def haversine(lat1, lng1, lat2, lng2):
    r = 6371000
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def load_stories():
    """stid 로 묶은 이야기. 오디오 주소가 하나도 없는 이야기는 버린다(무음이라 의미 없다)."""
    by_stid = {}
    for lang in LANGS:
        path = CACHE / f"odii_{lang}.json"
        if not path.exists():
            raise SystemExit(f"{path} 가 없다 — 먼저 fetch_odii.py 를 돌려라")
        for it in json.loads(path.read_text(encoding="utf-8")):
            url = (it.get("audioUrl") or "").strip()
            if not url:
                continue
            # 앱이 http를 https로 강제 치환하므로(ATS) 아예 https로 저장한다
            url = "https://" + url.split("://", 1)[-1] if "://" in url else url
            try:
                lat, lng = float(it["mapY"]), float(it["mapX"])
            except (TypeError, ValueError):
                continue
            seconds = int(it.get("playTime") or 0)
            entry = by_stid.setdefault(it["stid"], {"lat": lat, "lng": lng, "audio": {}})
            entry["audio"][lang] = {
                "name": (it.get("title") or "").strip(),
                "audioUrl": url,
                "seconds": seconds,
                "script": (it.get("script") or "").strip(),
            }
            if lang == "ko":                 # 대표 좌표는 한국어 기준으로 맞춘다
                entry["lat"], entry["lng"] = lat, lng
    return by_stid


CELL = 0.01   # 약 1.1km. MATCH_RADIUS(250m)보다 크므로 인접 한 칸만 봐도 충분하다


def build_grid(stories):
    grid = {}
    for stid, s in stories.items():
        grid.setdefault((int(s["lat"] / CELL), int(s["lng"] / CELL)), []).append((stid, s))
    return grid


def nearby(grid, lat, lng):
    gy, gx = int(lat / CELL), int(lng / CELL)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            yield from grid.get((gy + dy, gx + dx), ())


def representative(audio):
    """앱과 같은 우선순위: ko → en → 아무거나."""
    for lang in ("ko", "en"):
        if lang in audio:
            return audio[lang]
    return next(iter(audio.values()))


def distance_to_path(lat, lng, path):
    return min(haversine(lat, lng, p[0], p[1]) for p in path) if path else 9e9


def progress_on_path(lat, lng, path):
    """경로에서 가장 가까운 점의 인덱스 — 새 체크포인트를 끼워 넣을 위치를 정한다."""
    return min(range(len(path)), key=lambda i: haversine(lat, lng, path[i][0], path[i][1]))


def attach(course, stories, grid):
    path = [(p[0], p[1]) for p in course.get("polyline") or []]
    checkpoints = course.get("checkpoints") or []
    if not path or not checkpoints:
        return 0, 0

    used = set()

    # 1) 기존 체크포인트에 가장 가까운 이야기를 붙인다
    attached = 0
    for cp in checkpoints:
        best, best_d = None, MATCH_RADIUS
        for stid, s in nearby(grid, cp["lat"], cp["lng"]):
            if stid in used:
                continue
            d = haversine(cp["lat"], cp["lng"], s["lat"], s["lng"])
            if d < best_d:
                best, best_d = stid, d
        cp.pop("audio", None)
        cp.pop("audioUrl", None)
        if best is None:
            continue
        used.add(best)
        rep = representative(stories[best]["audio"])
        cp["audio"] = stories[best]["audio"]
        cp["audioUrl"] = rep["audioUrl"]
        cp["audioSeconds"] = rep["seconds"] or cp.get("audioSeconds", 0)
        attached += 1

    # 2) 경로 근처에 남은 이야기를 새 체크포인트로 더한다
    candidates = {}
    for lat, lng in path:
        for stid, s in nearby(grid, lat, lng):
            candidates.setdefault(stid, s)

    added = []
    for stid, s in sorted(candidates.items(), key=lambda kv: kv[0]):
        if stid in used or len(added) >= STORY_LIMIT:
            continue
        if distance_to_path(s["lat"], s["lng"], path) > MATCH_RADIUS:
            continue
        others = checkpoints + added
        if any(haversine(s["lat"], s["lng"], o["lat"], o["lng"]) < STORY_MIN_GAP for o in others):
            continue
        rep = representative(s["audio"])
        added.append({
            "id": f"{course['id']}-story-{stid}",
            "name": rep["name"],
            "lat": round(s["lat"], 7),
            "lng": round(s["lng"], 7),
            "audioSeconds": rep["seconds"],
            "description": rep["script"][:160],
            "audioUrl": rep["audioUrl"],
            "audio": s["audio"],
        })

    if added:
        # 경로 진행 순서로 끼워 넣는다. 기존 체크포인트의 상대 순서는 건드리지 않아
        # 경유지 부분수열이 그대로 유지된다(배포 게이트 조건).
        marks = [progress_on_path(cp["lat"], cp["lng"], path) for cp in checkpoints]
        merged = list(checkpoints)
        for new in sorted(added, key=lambda n: progress_on_path(n["lat"], n["lng"], path)):
            p = progress_on_path(new["lat"], new["lng"], path)
            at = sum(1 for m in marks if m <= p)
            merged.insert(at, new)
            marks.insert(at, p)
        course["checkpoints"] = merged
    return attached, len(added)


def rewrite(path, mutate):
    """포맷을 보존해 다시 쓴다 — indent=1 왕복이 원본과 바이트 단위로 같음을 확인했다."""
    data = json.loads(path.read_text(encoding="utf-8"))
    mutate(data)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def main():
    stories = load_stories()
    grid = build_grid(stories)
    print(f"오디 이야기 {len(stories)}건 (오디오 있는 것만)")

    stats, summary = {}, None

    def walk(data):
        courses = data if isinstance(data, list) else data.get("courses", [])
        for c in courses:
            if isinstance(c, dict) and "courses" in c:      # data/*.json 은 도시별로 감싸져 있다
                walk(c["courses"])
                continue
            if "id" not in c:
                continue
            a, n = attach(c, stories, grid)
            if a or n:
                stats[c["id"]] = (a, n)

    for path in [BUNDLE, BUILT, *sorted(DATA.glob("*.json"))]:
        if path.exists():
            stats.clear()
            rewrite(path, walk)
            if summary is None:    # 번들이 전체를 담고 있으므로 그걸로 집계한다
                summary = dict(stats)

    total_a = sum(a for a, _ in summary.values())
    total_n = sum(n for _, n in summary.values())
    print(f"오디오 붙은 기존 체크포인트 {total_a}개, 새로 더한 이야기 {total_n}개, "
          f"영향 코스 {len(summary)}개")


if __name__ == "__main__":
    main()
