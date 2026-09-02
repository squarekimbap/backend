#!/usr/bin/env python3
"""편집 코스에 TMAP 경로·안내, 고도, 체크포인트를 정적으로 베이크한다.

원본 ``data/*.json``과 병합본 ``courses.json``, 서버 번들
``../src/main/resources/data/courses.json``을 같은 값으로 갱신한다.
키는 저장소 루트 ``.env``의 TMAP_APP_KEY / GOOGLE_MAPS_API_KEY를 사용한다.

    python3 enrich_routes.py --dry-run
    python3 enrich_routes.py
    python3 enrich_routes.py --course busan-haeundae --dry-run

코스 원본의 ``shape``(``roundTrip``/``oneWay``)를 권위 있는 형태로 사용한다.
첫 POI를 출발점으로 삼고 나머지 POI를 저장 순서대로 모두 통과시킨다. 결과
경로는 guide 좌표를 보존하면서 최대 200점으로 줄인다.
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import subprocess
import urllib.parse


ROOT = pathlib.Path(__file__).parent
REPO = ROOT.parent
DATA = ROOT / "data"
MERGED = ROOT / "courses.json"
SERVER_BUNDLE = REPO / "src/main/resources/data/courses.json"
CACHE_PATH = pathlib.Path("/tmp/tour-api-tmap-route-cache.json")
TMAP_URL = "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&format=json"
ELEVATION_URL = "https://maps.googleapis.com/maps/api/elevation/json"

API_TO_TMAP_SHAPE = {"roundTrip": "loop", "oneWay": "oneway"}


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
        # 일부 TMAP 안내 문구에 이스케이프되지 않은 제어문자가 섞여 올 수 있다.
        return json.loads(result.stdout or b"{}", strict=False)
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


def required_shape(course: dict) -> str:
    api_shape = course.get("shape")
    if api_shape not in API_TO_TMAP_SHAPE:
        raise RuntimeError(
            f"{course['id']}: shape은 roundTrip 또는 oneWay여야 함 (현재={api_shape!r})")
    return API_TO_TMAP_SHAPE[api_shape]


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
    guide = dedupe_guide(guide)
    # TMAP의 "도착" Point가 종점에서 수십 m 앞에 찍히거나 뒤에 다른 Point가
    # 붙는 응답이 있다. 도착 안내는 하나만 남기고 실제 완주점으로 정규화한다.
    guide = [item for item in guide if item["text"].strip() != "도착"]
    guide.append({"lat": path[-1][0], "lng": path[-1][1], "text": "도착"})
    return {
        "shape": shape,
        "distanceM": distance,
        "walkDurationS": duration or 0,
        "pathRaw": path,
        "polyline": downsample_preserving_guides(path, guide, 200),
        "guide": guide,
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


def downsample_preserving_guides(path: list[list[float]], guide: list[dict], maximum: int,
                                 required_points: list[list[float]] | None = None) -> list[list[float]]:
    """안내·경유 좌표를 경로 꼭짓점으로 보존하고 나머지 점만 줄인다."""
    if len(path) < 2:
        return path
    positioned: list[tuple[float, list[float], bool]] = [
        (float(index), point, index in {0, len(path) - 1})
        for index, point in enumerate(path)
    ]
    for item in guide:
        point = [float(item["lat"]), float(item["lng"])]
        positioned.append((closest_path_position(point, path), point, True))
    for point in required_points or []:
        positioned.append((closest_path_position(point, path), point, True))
    positioned.sort(key=lambda item: item[0])

    merged: list[tuple[float, list[float], bool]] = []
    for position, point, mandatory in positioned:
        if merged and merged[-1][1] == point:
            previous = merged[-1]
            merged[-1] = (previous[0], previous[1], previous[2] or mandatory)
        else:
            merged.append((position, point, mandatory))

    mandatory_indices = {index for index, item in enumerate(merged) if item[2]}
    if len(mandatory_indices) > maximum:
        raise RuntimeError(f"guide 필수점 {len(mandatory_indices)}개가 polyline 상한 {maximum} 초과")
    if len(merged) <= maximum:
        return [item[1] for item in merged]

    selected = set(mandatory_indices)
    available = [index for index in range(len(merged)) if index not in selected]
    need = maximum - len(selected)
    if need > 0:
        for slot in range(need):
            picked = round(slot * (len(available) - 1) / max(1, need - 1))
            selected.add(available[picked])
    return [merged[index][1] for index in sorted(selected)]


def closest_path_position(point: list[float], path: list[list[float]]) -> float:
    best_distance = math.inf
    best_position = 0.0
    for index in range(len(path) - 1):
        distance, fraction = point_to_segment_with_fraction(point, path[index], path[index + 1])
        if distance < best_distance:
            best_distance = distance
            best_position = index + fraction
    return best_position


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
    route = tmap_route(tmap_key, points[0], tuple(points[1:]), required_shape(course))
    choices = [route]
    repeated = repeated_loop(route, target)
    if repeated is not None:
        choices.append(repeated)
    return min(choices, key=lambda item: abs(item["distanceM"] - target))


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
    repeated["polyline"] = downsample_preserving_guides(raw, repeated["guide"], 200)
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


def point_to_segment(point: dict | list[float], a: list[float], b: list[float]) -> float:
    return point_to_segment_with_fraction(point, a, b)[0]


def point_to_segment_with_fraction(point: dict | list[float], a: list[float], b: list[float]) -> tuple[float, float]:
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
    return math.hypot(cx - px, cy - py), t


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
    # 시설 중심점이나 교량 중앙 좌표는 TMAP이 접근 가능한 산책로에서 조금
    # 떨어질 수 있다. 실제 검색 좌표는 placeLat/placeLng에 남기고, 앱의 100m
    # 트리거 좌표는 경로 위의 가장 가까운 원본 점으로 옮긴다.
    for poi in course.get("poi", []):
        if poi.get("lat") is None or poi.get("lng") is None:
            continue
        if distance_to_path(poi, route["pathRaw"]) > 95:
            poi.setdefault("placeLat", poi["lat"])
            poi.setdefault("placeLng", poi["lng"])
            nearest = min(route["pathRaw"], key=lambda point: haversine(poi, point))
            poi["lat"], poi["lng"] = nearest

    if course.get("shape") == "oneWay" and course.get("poi"):
        finish = course["poi"][-1]
        finish.setdefault("placeLat", finish["lat"])
        finish.setdefault("placeLng", finish["lng"])
        finish["lat"], finish["lng"] = route["pathRaw"][-1]

    required_points = [
        [float(poi["lat"]), float(poi["lng"])]
        for poi in course.get("poi", [])
        if poi.get("lat") is not None and poi.get("lng") is not None
    ]
    route["polyline"] = downsample_preserving_guides(
        route["pathRaw"], route["guide"], 200, required_points)

    checkpoints = []
    for index, poi in enumerate(course.get("poi", [])):
        if poi.get("lat") is None or poi.get("lng") is None:
            continue
        if distance_to_path(poi, route["pathRaw"]) <= 100:
            checkpoints.append(checkpoint(course["id"], index, poi))
    if not checkpoints:
        raise RuntimeError(f"{course['id']}: 경로 100m 안 체크포인트 없음")
    return {
        "shape": course["shape"],
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
    expected_route_shape = required_shape(course)
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
    if course.get("routeShape") != expected_route_shape:
        raise RuntimeError(f"{course['id']}: shape과 routeShape 불일치")
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
        original_km = float(source_course["km"])
        original_min = int(source_course.get("min") or 0)
        actual_km = round(route["distanceM"] / 1000, 1)
        source_course["km"] = actual_km
        if original_min > 0 and original_km > 0:
            source_course["min"] = max(1, round(original_min * actual_km / original_km))
        fields = enrich(source_course, route, elevation_key)
        source_course.update(fields)
        merged_by_id[course_id].update(fields)
        merged_by_id[course_id]["km"] = source_course["km"]
        merged_by_id[course_id]["min"] = source_course["min"]
        merged_by_id[course_id]["poi"] = source_course["poi"]
        validate(source_course)
        error_percent = abs(fields["distanceM"] - original_km * 1000) / (original_km * 1000) * 100
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
