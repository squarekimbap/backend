#!/usr/bin/env python3
"""편집 코스에 TMAP 경로·안내, 고도, 체크포인트를 정적으로 베이크한다.

원본 ``data/*.json``과 병합본 ``courses.json``, 서버 번들
``../src/main/resources/data/courses.json``을 같은 값으로 갱신한다.
키는 저장소 루트 ``.env``의 TMAP_APP_KEY / GOOGLE_MAPS_API_KEY를 사용한다.

    python3 enrich_routes.py --dry-run
    python3 enrich_routes.py
    python3 enrich_routes.py --course busan-haeundae --dry-run

TMAP 후보는 코스의 첫 POI를 출발점으로 삼아, 나머지 POI 조합과
loop/oneway 형태 중 편집 거리와 가장 가까운 결과를 고른다. 결과 경로는 최대
200점으로 줄이고, TMAP Point feature의 description을 guide로 보존한다.
"""

from __future__ import annotations

import argparse
import itertools
import json
import math
import pathlib
import subprocess
import time
import urllib.parse


ROOT = pathlib.Path(__file__).parent
REPO = ROOT.parent
DATA = ROOT / "data"
MERGED = ROOT / "courses.json"
SERVER_BUNDLE = REPO / "src/main/resources/data/courses.json"
CACHE_PATH = pathlib.Path("/tmp/tour-api-tmap-route-cache.json")
TMAP_URL = "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&format=json"
ELEVATION_URL = "https://maps.googleapis.com/maps/api/elevation/json"

ONEWAY_WORDS = ("편도", "종주", "업힐", "옛 기찻길")
LOOP_WORDS = ("왕복", "한 바퀴", "순환", "회복런", "첫 5K", "두 호수", "큰 바퀴")


def args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="외부 호출·검증만 하고 파일은 쓰지 않음")
    parser.add_argument("--skip-elevation", action="store_true", help="고도 호출 없이 기존/0 값을 사용")
    parser.add_argument("--course", action="append", default=[], help="지정한 코스 id만 처리(반복 가능)")
    return parser.parse_args()


def env_key(name: str) -> str:
    env_path = REPO / ".env"
    if not env_path.exists():
        raise SystemExit("저장소 루트 .env가 없습니다")
    for raw in env_path.read_text().splitlines():
        line = raw.strip()
        if line.startswith(name + "="):
            value = line.split("=", 1)[1].strip().strip('"').strip("'")
            if value:
                return value
    raise SystemExit(f".env에 {name} 값이 없습니다")


def curl_json(url: str, *, headers: dict[str, str] | None = None,
              body: dict | None = None, timeout: int = 20) -> dict:
    cmd = ["curl", "-sS", "--retry", "3", "--retry-all-errors", "--retry-delay", "1",
           "--max-time", str(timeout), url]
    for key, value in (headers or {}).items():
        cmd.extend(["-H", f"{key}: {value}"])
    if body is not None:
        cmd.extend(["-H", "Content-Type: application/json", "--data-binary",
                    json.dumps(body, ensure_ascii=False, separators=(",", ":"))])
    try:
        result = subprocess.run(cmd, capture_output=True, timeout=timeout + 5)
    except subprocess.TimeoutExpired as error:
        raise RuntimeError("외부 API 호출 시간 초과") from error
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", "replace").strip()
        raise RuntimeError(f"외부 API 호출 실패: {message or 'curl exit ' + str(result.returncode)}")
    try:
        return json.loads(result.stdout or b"{}")
    except json.JSONDecodeError as error:
        raise RuntimeError("외부 API JSON 파싱 실패") from error


def load_sources() -> tuple[list[tuple[pathlib.Path, object, list[dict]]], dict[str, dict]]:
    sources = []
    by_id: dict[str, dict] = {}
    for path in sorted(DATA.glob("*.json")):
        raw = json.loads(path.read_text())
        regions = raw if isinstance(raw, list) else [raw]
        for region in regions:
            for course in region["courses"]:
                course_id = course["id"]
                if course_id in by_id:
                    raise RuntimeError(f"코스 id 중복: {course_id}")
                by_id[course_id] = course
        sources.append((path, raw, regions))
    return sources, by_id


def route_points(course: dict) -> list[dict]:
    points = []
    for poi in course.get("poi", []):
        if poi.get("lat") is None or poi.get("lng") is None:
            continue
        points.append(poi)
    if len(points) < 2:
        raise RuntimeError(f"{course['id']}: 경로 좌표가 2개 미만")
    return points[:6]


def allowed_shapes(course: dict) -> list[str]:
    text = f"{course.get('n', '')} {course.get('headline', '')}"
    if any(word in text for word in ONEWAY_WORDS):
        return ["oneway", "loop"]
    if any(word in text for word in LOOP_WORDS) or course.get("scene") in {"lake", "city"}:
        return ["loop", "oneway"]
    return ["loop", "oneway"]


def haversine(a: dict | tuple[float, float], b: dict | tuple[float, float]) -> float:
    lat1, lng1 = point_tuple(a)
    lat2, lng2 = point_tuple(b)
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lng2 - lng1)
    x = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 6371000 * 2 * math.atan2(math.sqrt(x), math.sqrt(max(0.0, 1 - x)))


def point_tuple(point: dict | tuple[float, float] | list[float]) -> tuple[float, float]:
    if isinstance(point, dict):
        return float(point["lat"]), float(point["lng"])
    return float(point[0]), float(point[1])


def estimated_distance(start: dict, order: tuple[dict, ...], shape: str) -> float:
    total = 0.0
    current = start
    for point in order:
        total += haversine(current, point)
        current = point
    if shape == "loop":
        total += haversine(current, start)
    return total


def candidates(course: dict, points: list[dict], limit: int = 6) -> list[tuple[str, tuple[dict, ...]]]:
    start, rest = points[0], points[1:]
    target = float(course["km"]) * 1000
    ranked_by_shape: dict[str, dict[tuple, tuple[float, str, tuple[dict, ...]]]] = {}
    shapes = allowed_shapes(course)
    for shape_index, shape in enumerate(shapes):
        ranked: dict[tuple, tuple[float, str, tuple[dict, ...]]] = {}
        for size in range(1, len(rest) + 1):
            for subset in itertools.combinations(rest, size):
                orders = [subset, tuple(reversed(subset))]
                for order in orders:
                    signature = (shape,) + tuple((p["lat"], p["lng"]) for p in order)
                    estimate = estimated_distance(start, order, shape)
                    if shape == "loop" and estimate > 0:
                        estimate *= max(1, min(10, round(target / estimate)))
                    missing_penalty = (len(rest) - len(order)) * min(250.0, target * 0.025)
                    rank = abs(estimate - target) + missing_penalty
                    rank += shape_index * target * 0.02
                    previous = ranked.get(signature)
                    if previous is None or rank < previous[0]:
                        ranked[signature] = (rank, shape, order)
        # 기존 nextM은 실제 TMAP 구간거리다. 원본 순서의 prefix는 직선거리보다
        # 이 값이 훨씬 정확하므로 후보 상한 밖으로 밀려나지 않게 별도 랭크한다.
        running_distance = 0.0
        for size in range(1, len(rest) + 1):
            segment = points[size - 1].get("nextM")
            if segment is None:
                break
            running_distance += float(segment)
            order = tuple(rest[:size])
            effective = running_distance if shape == "oneway" else running_distance * 2
            if shape == "loop" and effective > 0:
                effective *= max(1, min(10, round(target / effective)))
            signature = (shape,) + tuple((p["lat"], p["lng"]) for p in order)
            missing_penalty = (len(rest) - len(order)) * min(250.0, target * 0.025)
            rank = abs(effective - target) + missing_penalty + shape_index * target * 0.02
            previous = ranked.get(signature)
            if previous is None or rank < previous[0]:
                ranked[signature] = (rank, shape, order)
        ranked_by_shape[shape] = ranked

    # 한 형태의 부분집합 후보가 상한을 독점하지 않게 양쪽 형태를 반드시 포함한다.
    picked = []
    per_shape = max(1, limit // len(shapes))
    for shape in shapes:
        picked.extend(sorted(ranked_by_shape[shape].values(), key=lambda item: item[0])[:per_shape])
    remain = [item for shape in shapes for item in ranked_by_shape[shape].values()
              if item not in picked]
    picked.extend(sorted(remain, key=lambda item: item[0])[:max(0, limit - len(picked))])
    return [(shape, order) for _, shape, order in sorted(picked, key=lambda item: item[0])[:limit]]


def tmap_route(key: str, start: dict, order: tuple[dict, ...], shape: str) -> dict:
    if shape == "loop":
        via = order
        end = start
    else:
        via = order[:-1]
        end = order[-1]
    body = {
        "startX": f"{float(start['lng']):.6f}",
        "startY": f"{float(start['lat']):.6f}",
        "endX": f"{float(end['lng']):.6f}",
        "endY": f"{float(end['lat']):.6f}",
        "reqCoordType": "WGS84GEO",
        "resCoordType": "WGS84GEO",
        "startName": "start",
        "endName": "end",
    }
    if via:
        body["passList"] = "_".join(
            f"{float(point['lng']):.6f},{float(point['lat']):.6f}" for point in via)
    cache_key = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    cache = load_route_cache()
    root = cache.get(cache_key)
    if root is None:
        root = curl_json(TMAP_URL, headers={"appKey": key}, body=body)
        cache[cache_key] = root
        CACHE_PATH.write_text(json.dumps(cache, ensure_ascii=False, separators=(",", ":")))
    if root.get("error"):
        raise RuntimeError(f"TMAP: {root['error'].get('message', '경로 오류')}")

    path: list[list[float]] = []
    guide: list[dict] = []
    distance = duration = None
    for feature in root.get("features", []):
        properties = feature.get("properties") or {}
        geometry = feature.get("geometry") or {}
        if distance is None and properties.get("totalDistance") is not None:
            distance = int(properties["totalDistance"])
            duration = int(properties.get("totalTime") or 0)
        if geometry.get("type") == "LineString":
            for coordinate in geometry.get("coordinates") or []:
                if len(coordinate) >= 2:
                    append_unique(path, [round(float(coordinate[1]), 7), round(float(coordinate[0]), 7)])
        elif geometry.get("type") == "Point":
            coordinate = geometry.get("coordinates") or []
            text = str(properties.get("description") or "").strip()
            if len(coordinate) >= 2 and text:
                guide.append({
                    "lat": round(float(coordinate[1]), 7),
                    "lng": round(float(coordinate[0]), 7),
                    "text": text,
                })
    if distance is None or len(path) < 2:
        raise RuntimeError("TMAP 경로 없음")
    return {
        "shape": shape,
        "distanceM": distance,
        "walkDurationS": duration or 0,
        "pathRaw": path,
        "polyline": downsample(path, 200),
        "guide": dedupe_guide(guide),
        "used": order,
    }


def append_unique(items: list, value) -> None:
    if not items or items[-1] != value:
        items.append(value)


def downsample(items: list, maximum: int) -> list:
    if len(items) <= maximum:
        return items
    indices = [round(i * (len(items) - 1) / (maximum - 1)) for i in range(maximum)]
    return [items[index] for index in indices]


def dedupe_guide(items: list[dict]) -> list[dict]:
    out = []
    seen = set()
    for item in items:
        signature = (item["lat"], item["lng"], item["text"])
        if signature not in seen:
            out.append(item)
            seen.add(signature)
    return out


_ROUTE_CACHE: dict[str, dict] | None = None


def load_route_cache() -> dict[str, dict]:
    global _ROUTE_CACHE
    if _ROUTE_CACHE is None:
        try:
            value = json.loads(CACHE_PATH.read_text())
            _ROUTE_CACHE = value if isinstance(value, dict) else {}
        except (FileNotFoundError, json.JSONDecodeError):
            _ROUTE_CACHE = {}
    return _ROUTE_CACHE


def choose_route(course: dict, points: list[dict], tmap_key: str) -> dict:
    target = float(course["km"]) * 1000
    preferred_shape = allowed_shapes(course)[0]
    results = []
    errors = []
    for shape, order in candidates(course, points):
        try:
            route = tmap_route(tmap_key, points[0], order, shape)
            missing = len(points) - 1 - len(order)
            route["score"] = (abs(route["distanceM"] - target)
                              + missing * min(250.0, target * 0.025)
                              + (0 if shape == preferred_shape else target * 0.02))
            results.append(route)
            repeated = repeated_loop(route, target)
            if repeated is not None:
                repeated["score"] = (abs(repeated["distanceM"] - target)
                                     + missing * min(250.0, target * 0.025)
                                     + (0 if shape == preferred_shape else target * 0.02))
                results.append(repeated)
            # 거리 오차 8% 이내면 앱 경로로 충분하다. 후보를 더 호출하지 않아
            # 고정 카탈로그 일괄 생성 시 TMAP 일일 쿼터를 아낀다.
            if min(abs(item["distanceM"] - target) / target for item in results) <= 0.08:
                break
        except Exception as error:
            errors.append(str(error))
        time.sleep(0.05)
    if not results:
        detail = errors[0] if errors else "후보 없음"
        raise RuntimeError(f"{course['id']}: TMAP 후보 전체 실패 ({detail})")
    best = min(results, key=lambda item: item["score"])
    if abs(best["distanceM"] - target) / target > 0.12:
        for route in synthetic_routes(course, points, tmap_key, target):
            route["score"] = (abs(route["distanceM"] - target)
                              + (0 if route["shape"] == preferred_shape else target * 0.02))
            results.append(route)
    return min(results, key=lambda item: item["score"])


def synthetic_routes(course: dict, points: list[dict], tmap_key: str,
                     target: float) -> list[dict]:
    """POI 간격만으로 편집 거리를 만들 수 없을 때 같은 진행 방향으로 반환점을 보정한다.

    하천·해안처럼 표시 POI는 가깝지만 실제 코스는 더 길게 이어지는 경우가 많다.
    첫 POI에서 다음 POI 방향을 유지한 채 목표 거리의 절반(loop) 또는 전체(oneway)
    지점으로 반환점을 옮기고, 실제 TMAP 거리 비율로 한 번 더 보정한다.
    """
    start = points[0]
    direction = next((point for point in points[1:] if haversine(start, point) >= 100), None)
    if direction is None:
        return []
    start_lat, start_lng = point_tuple(start)
    end_lat, end_lng = point_tuple(direction)
    base_distance = haversine(start, direction)
    out = []
    for shape in allowed_shapes(course):
        desired_radial = target / (2 if shape == "loop" else 1)
        factor = max(0.2, min(12.0, desired_radial / base_distance))
        for attempt in range(2):
            synthetic = {
                "n": "거리 보정 반환점",
                "lat": start_lat + (end_lat - start_lat) * factor,
                "lng": start_lng + (end_lng - start_lng) * factor,
            }
            try:
                route = tmap_route(tmap_key, start, (synthetic,), shape)
                out.append(route)
                if route["distanceM"] <= 0:
                    break
                next_factor = max(0.2, min(12.0, factor * target / route["distanceM"]))
                if abs(next_factor - factor) < 0.03:
                    break
                factor = next_factor
            except Exception:
                break
            finally:
                time.sleep(0.05)
    return out


def repeated_loop(route: dict, target: float) -> dict | None:
    if route["shape"] != "loop" or route["distanceM"] <= 0:
        return None
    laps = max(1, min(10, round(target / route["distanceM"])))
    if laps <= 1:
        return None
    raw = []
    for _ in range(laps):
        for point in route["pathRaw"]:
            append_unique(raw, point)
    repeated = dict(route)
    repeated["distanceM"] = route["distanceM"] * laps
    repeated["walkDurationS"] = route["walkDurationS"] * laps
    repeated["pathRaw"] = raw
    repeated["polyline"] = downsample(raw, 200)
    return repeated


def elevations(api_key: str, path: list[list[float]]) -> tuple[float, float, str]:
    # 좌표를 너무 많이 GET 쿼리에 넣으면 프록시/서버가 긴 URL을 지연시키므로
    # 정적 코스의 누적 상승량에 충분한 50개 지점으로 제한한다.
    samples = downsample(path, 50)
    locations = "|".join(f"{point[0]:.5f},{point[1]:.5f}" for point in samples)
    query = urllib.parse.urlencode({"locations": locations, "key": api_key})
    root = curl_json(ELEVATION_URL + "?" + query)
    if root.get("status") != "OK":
        raise RuntimeError(f"Elevation status={root.get('status', 'unknown')}")
    values = [float(item.get("elevation") or 0) for item in root.get("results") or []]
    if len(values) != len(samples):
        raise RuntimeError("Elevation 결과 수 불일치")
    ascent = sum(max(0.0, values[index] - values[index - 1]) for index in range(1, len(values)))
    return round(ascent, 1), 0.0, ""


def distance_to_path(point: dict, path: list[list[float]]) -> float:
    if not path:
        return math.inf
    best = math.inf
    for index in range(1, len(path)):
        best = min(best, point_to_segment(point, path[index - 1], path[index]))
    return best


def point_to_segment(point: dict, a: list[float], b: list[float]) -> float:
    lat, lng = point_tuple(point)
    ref = math.radians(lat)
    scale_x = 111320 * math.cos(ref)
    scale_y = 110540
    px, py = 0.0, 0.0
    ax, ay = (a[1] - lng) * scale_x, (a[0] - lat) * scale_y
    bx, by = (b[1] - lng) * scale_x, (b[0] - lat) * scale_y
    dx, dy = bx - ax, by - ay
    denominator = dx * dx + dy * dy
    t = 0.0 if denominator == 0 else max(0.0, min(1.0, (-(ax * dx + ay * dy)) / denominator))
    cx, cy = ax + t * dx, ay + t * dy
    return math.hypot(cx - px, cy - py)


def checkpoint(course_id: str, index: int, poi: dict) -> dict:
    description = str(poi.get("d") or poi.get("n") or "").strip()
    spoken = len("".join(description.split()))
    seconds = max(15, min(90, round(spoken / 3.2)))
    return {
        "id": f"{course_id}-{index + 1}",
        "name": poi["n"],
        "lat": round(float(poi["lat"]), 7),
        "lng": round(float(poi["lng"]), 7),
        "audioSeconds": seconds,
        "description": description,
    }


def enrich(course: dict, route: dict, elevation_key: str | None) -> dict:
    distance_km = route["distanceM"] / 1000
    if elevation_key:
        ascent, _, _ = elevations(elevation_key, route["pathRaw"])
    else:
        ascent = float(course.get("ascentM") or 0)
    ascent_per_km = round(ascent / distance_km, 1) if distance_km > 0 else 0.0
    difficulty = "하" if ascent_per_km <= 10 else "중" if ascent_per_km <= 25 else "상"
    checkpoints = []
    for index, poi in enumerate(course.get("poi", [])):
        if poi.get("lat") is None or poi.get("lng") is None:
            continue
        if distance_to_path(poi, route["pathRaw"]) <= 100:
            checkpoints.append(checkpoint(course["id"], index, poi))
    if not checkpoints:
        raise RuntimeError(f"{course['id']}: 경로 100m 안 체크포인트 없음")
    return {
        "routeShape": route["shape"],
        "distanceM": route["distanceM"],
        "walkDurationS": route["walkDurationS"],
        "ascentM": ascent,
        "ascentPerKm": ascent_per_km,
        "difficulty": difficulty,
        "polyline": route["polyline"],
        "guide": route["guide"],
        "checkpoints": checkpoints,
    }


def validate(course: dict) -> None:
    required = ("polyline", "guide", "checkpoints")
    for field in required:
        if field not in course or not isinstance(course[field], list):
            raise RuntimeError(f"{course['id']}: {field} 배열 누락")
    if not 2 <= len(course["polyline"]) <= 200:
        raise RuntimeError(f"{course['id']}: polyline 점 개수 오류")
    if not course["guide"]:
        raise RuntimeError(f"{course['id']}: guide 비어 있음")
    if not course["checkpoints"]:
        raise RuntimeError(f"{course['id']}: checkpoints 비어 있음")
    for point in course["polyline"]:
        if len(point) < 2 or not (-90 <= point[0] <= 90 and -180 <= point[1] <= 180):
            raise RuntimeError(f"{course['id']}: polyline 좌표 오류")


def write_json(path: pathlib.Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=1) + "\n")


def main() -> None:
    options = args()
    tmap_key = env_key("TMAP_APP_KEY")
    elevation_key = None if options.skip_elevation else env_key("GOOGLE_MAPS_API_KEY")
    sources, source_by_id = load_sources()
    merged = [
        dict(course, city=region["city"], cityId=region["cityId"], region=region["region"])
        for _, _, regions in sources
        for region in regions
        for course in region["courses"]
    ]
    merged_by_id = {course["id"]: course for course in merged}
    if set(source_by_id) != set(merged_by_id):
        raise RuntimeError("data/*.json과 courses.json의 코스 id 집합이 다름")
    selected = set(options.course)
    unknown = selected - set(source_by_id)
    if unknown:
        raise RuntimeError("없는 코스 id: " + ", ".join(sorted(unknown)))

    reports = []
    for course_id, source_course in source_by_id.items():
        if selected and course_id not in selected:
            continue
        points = route_points(source_course)
        route = choose_route(source_course, points, tmap_key)
        fields = enrich(source_course, route, elevation_key)
        source_course.update(fields)
        merged_by_id[course_id].update(fields)
        validate(source_course)
        error_percent = abs(fields["distanceM"] - float(source_course["km"]) * 1000) \
            / (float(source_course["km"]) * 1000) * 100
        reports.append((course_id, fields["routeShape"], fields["distanceM"], error_percent,
                        len(fields["polyline"]), len(fields["guide"]), len(fields["checkpoints"])))
        print(f"{course_id:30} {fields['routeShape']:6} {fields['distanceM']:5}m "
              f"오차 {error_percent:5.1f}% · path {len(fields['polyline']):3} · "
              f"guide {len(fields['guide']):3} · checkpoints {len(fields['checkpoints'])}")

    if not selected:
        if len(source_by_id) != 64:
            raise RuntimeError(f"전체 코스 수 오류: {len(source_by_id)}")
        for course in source_by_id.values():
            validate(course)
    if options.dry_run:
        print(f"\ndry-run 완료: {len(reports)}개, 파일 변경 없음")
        return

    for path, raw, _ in sources:
        write_json(path, raw)
    write_json(MERGED, merged)
    write_json(SERVER_BUNDLE, merged)
    print(f"\n저장 완료: {len(reports)}개 코스 → 원본·병합본·서버 번들")


if __name__ == "__main__":
    main()
