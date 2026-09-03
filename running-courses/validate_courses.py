#!/usr/bin/env python3
"""배포 전에 편집 코스 69개의 API 계약과 경로 기하를 검증한다."""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import re
import sys


ROOT = pathlib.Path(__file__).parent
DEFAULT_BUNDLE = ROOT.parent / "src/main/resources/data/courses.json"
EARTH_RADIUS_M = 6_371_000.0
SHAPES = {"roundTrip": "loop", "oneWay": "oneway"}
SUSPICIOUS_ADDRESS = re.compile(r"(?:\d+층|\d+호(?:\D|$)|상가|오피스텔|빌딩)")


def haversine(a: tuple[float, float] | list[float], b: tuple[float, float] | list[float]) -> float:
    lat1, lng1 = a
    lat2, lng2 = b
    p1, p2 = math.radians(lat1), math.radians(lat2)
    delta_lat = p2 - p1
    delta_lng = math.radians(lng2 - lng1)
    value = (math.sin(delta_lat / 2) ** 2
             + math.cos(p1) * math.cos(p2) * math.sin(delta_lng / 2) ** 2)
    return EARTH_RADIUS_M * 2 * math.atan2(math.sqrt(value), math.sqrt(max(0.0, 1 - value)))


def point_to_segment(point: tuple[float, float], a: list[float], b: list[float]) -> float:
    lat, lng = point
    ref = math.radians(lat)
    ax = EARTH_RADIUS_M * math.cos(ref) * math.radians(a[1] - lng)
    ay = EARTH_RADIUS_M * math.radians(a[0] - lat)
    bx = EARTH_RADIUS_M * math.cos(ref) * math.radians(b[1] - lng)
    by = EARTH_RADIUS_M * math.radians(b[0] - lat)
    dx, dy = bx - ax, by - ay
    denominator = dx * dx + dy * dy
    fraction = 0.0 if denominator == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / denominator))
    return math.hypot(ax + fraction * dx, ay + fraction * dy)


def distance_to_path(point: tuple[float, float], path: list[list[float]]) -> float:
    return min(point_to_segment(point, path[index - 1], path[index])
               for index in range(1, len(path)))


def valid_coordinate(lat: object, lng: object) -> bool:
    return (isinstance(lat, (int, float)) and isinstance(lng, (int, float))
            and math.isfinite(lat) and math.isfinite(lng)
            and -90 <= lat <= 90 and -180 <= lng <= 180)


def validate(courses: list[dict]) -> list[str]:
    errors: list[str] = []
    # 코스를 늘리거나 줄일 때 이 숫자도 같이 고친다 — 병합 사고로 조용히 사라지는 걸 막는다
    if len(courses) != 69:
        errors.append(f"전체 코스 수가 69가 아님: {len(courses)}")
    ids = [course.get("id") for course in courses]
    if len(ids) != len(set(ids)):
        errors.append("코스 ID 중복")

    for course in courses:
        course_id = str(course.get("id") or "<id 없음>")
        shape = course.get("shape")
        if shape not in SHAPES:
            errors.append(f"{course_id}: shape 누락/오류 ({shape!r})")
            continue
        if course.get("routeShape") != SHAPES[shape]:
            errors.append(f"{course_id}: shape과 routeShape 불일치")

        path = course.get("polyline")
        if not isinstance(path, list) or not 2 <= len(path) <= 200:
            errors.append(f"{course_id}: polyline은 2~200점이어야 함")
            continue
        if any(not isinstance(point, list) or len(point) != 2
               or not valid_coordinate(point[0], point[1]) for point in path):
            errors.append(f"{course_id}: polyline 좌표 형식/범위 오류")
            continue

        start_end_m = haversine(path[0], path[-1])
        if shape == "roundTrip" and start_end_m > 100:
            errors.append(f"{course_id}: roundTrip 시작-종점 {start_end_m:.0f}m")

        geometry_m = sum(haversine(path[index - 1], path[index])
                         for index in range(1, len(path)))
        display_m = float(course.get("km") or 0) * 1000
        if display_m <= 0 or abs(geometry_m - display_m) / display_m > 0.10:
            errors.append(
                f"{course_id}: polyline {geometry_m / 1000:.2f}km와 km={course.get('km')} 오차 10% 초과")
        distance_m = float(course.get("distanceM") or 0)
        if display_m <= 0 or abs(distance_m - display_m) > 100:
            errors.append(f"{course_id}: distanceM={distance_m:.0f}와 km={course.get('km')} 불일치")

        poi = course.get("poi")
        if not isinstance(poi, list) or len(poi) < 2:
            errors.append(f"{course_id}: 실제 경유지 POI 2개 미만")
            poi = []
        poi_names: list[str] = []
        for item in poi:
            name = str(item.get("n") or "").strip()
            poi_names.append(name)
            if not name:
                errors.append(f"{course_id}: POI 이름 누락")
            if not valid_coordinate(item.get("lat"), item.get("lng")):
                errors.append(f"{course_id}/{name}: POI 좌표 누락/오류")
                continue
            distance = distance_to_path((item["lat"], item["lng"]), path)
            if distance > 100:
                errors.append(f"{course_id}/{name}: POI가 경로에서 {distance:.0f}m")
            address = str(item.get("addr") or "").strip()
            if not address:
                errors.append(f"{course_id}/{name}: POI 주소 누락")
            elif SUSPICIOUS_ADDRESS.search(address):
                errors.append(f"{course_id}/{name}: 동명 업소로 의심되는 주소 ({address})")
            place_lat, place_lng = item.get("placeLat"), item.get("placeLng")
            if (place_lat is None) != (place_lng is None) or (
                    place_lat is not None and not valid_coordinate(place_lat, place_lng)):
                errors.append(f"{course_id}/{name}: 원 장소 좌표 placeLat/placeLng 오류")

        landmarks = course.get("landmarks", [])
        if not isinstance(landmarks, list):
            errors.append(f"{course_id}: landmarks는 배열이어야 함")
            landmarks = []
        landmark_names = [str(item.get("n") or "").strip() for item in landmarks]
        overlap = set(poi_names) & set(landmark_names)
        if overlap:
            errors.append(f"{course_id}: POI와 landmarks 중복 ({', '.join(sorted(overlap))})")
        for item in landmarks:
            if not item.get("n") or not valid_coordinate(item.get("lat"), item.get("lng")):
                errors.append(f"{course_id}: landmark 이름/좌표 오류")

        guide = course.get("guide")
        if not isinstance(guide, list) or not guide:
            errors.append(f"{course_id}: guide 누락")
            guide = []
        for index, item in enumerate(guide):
            if not valid_coordinate(item.get("lat"), item.get("lng")) or not str(item.get("text") or "").strip():
                errors.append(f"{course_id}: guide[{index}] 형식 오류")
                continue
            distance = distance_to_path((item["lat"], item["lng"]), path)
            if distance > 1:
                errors.append(f"{course_id}: guide[{index}]가 polyline에서 {distance:.1f}m")
        if shape == "oneWay" and guide:
            finish_guide_m = haversine(path[-1], [guide[-1]["lat"], guide[-1]["lng"]])
            if finish_guide_m > 1:
                errors.append(f"{course_id}: oneWay 종점과 마지막 guide가 {finish_guide_m:.1f}m 불일치")

        # 체크포인트는 경유지를 모두 순서대로 담되, 그 사이에 오디 도슨트가 끼어들 수 있다.
        # (refresh_stories.py가 경로 진행 순서로 끼워 넣는다 — 경유지 부분수열은 그대로 유지)
        checkpoints = course.get("checkpoints") or []
        checkpoint_names = [str(item.get("name") or "") for item in checkpoints]
        remaining = list(poi_names)
        for name in checkpoint_names:
            if remaining and name == remaining[0]:
                remaining.pop(0)
        if remaining:
            errors.append(f"{course_id}: checkpoints에 POI가 순서대로 들어 있지 않다 "
                          f"(빠짐: {', '.join(remaining)})")

        for item in checkpoints:
            url = str(item.get("audioUrl") or "")
            # 앱이 http를 https로 강제 치환하므로(ATS) http 주소는 재생이 실패한다
            if url and not url.startswith("https://"):
                errors.append(f"{course_id}/{item.get('name')}: 오디오가 https가 아니다")
            audio = item.get("audio") or {}
            # 오디 규격은 jp다. ja로 넣으면 앱이 일본어를 못 찾는다
            if "ja" in audio:
                errors.append(f"{course_id}/{item.get('name')}: 언어 키는 jp여야 한다(ja 발견)")
            if url and not item.get("audioSeconds"):
                errors.append(f"{course_id}/{item.get('name')}: 오디오가 있는데 길이가 없다")

    return errors


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", nargs="?", type=pathlib.Path, default=DEFAULT_BUNDLE)
    options = parser.parse_args()
    courses = json.loads(options.path.read_text())
    errors = validate(courses)
    if errors:
        print(f"코스 검증 실패 {len(errors)}건", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        raise SystemExit(1)
    print(f"코스 검증 통과: {len(courses)}개")


if __name__ == "__main__":
    main()
