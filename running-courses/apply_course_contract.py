#!/usr/bin/env python3
"""64개 편집 코스의 권위 있는 형태와 수동 검증한 장소 정보를 반영한다.

이 파일은 텍스트 추론으로 코스 형태를 정하지 않기 위한 명시적 데이터 마이그레이션이다.
실행 결과는 ``data/*.json`` 원본에 저장되고, 이후 ``enrich_routes.py``가 경로를 다시 만든다.
"""

from __future__ import annotations

import json
import pathlib


ROOT = pathlib.Path(__file__).parent
DATA = ROOT / "data"

# 실제 출발지와 도착지가 다른 코스. 이외 54개는 출발지로 돌아오는 코스다.
ONE_WAY_IDS = {
    "busan-samnak-lsd",
    "jeju-saryeoni",
    "daejeon-gapcheon",
    "yeosu-expo-9k",
    "yeosu-old-railroad",
    "gunsan-saemangeum",
    "seoul-bugak-skyway",
    "seongnam-tancheon",
    "anyang-anyangcheon-jongju",
    "siheung-sihwa-seawall",
}

# 코스 가까이에서 보거나 러닝 뒤에 들르는 장소로, 실제 경유지와 구분한다.
LANDMARKS = {
    "busan-gwangalli-night": {"광안대교"},
    "chuncheon-uiamho": {"붕어섬"},
    "daegu-suseongmot": {"들안길"},
    "ulsan-taehwagang": {"태화강전망대"},
    "suncheon-dongcheon": {"순천만국가정원"},
    "hwaseong-dongtan-lake": {"루나쇼 분수"},
}

# 네이버 지역검색에서 지리 지물/공공시설 결과를 수동 확인한 값이다.
POI_FIXES = {
    ("cheongju-musimcheon", "무심천"): {
        "lat": 36.6392998, "lng": 127.4831849,
        "addr": "충청북도 청주시 서원구 사직동 78-2",
    },
    ("cheonan-gokgyocheon", "곡교천 은행나무길"): {
        "lat": 36.800063, "lng": 127.018091,
        "addr": "충청남도 아산시 염치읍 백암리 502-3",
    },
    ("gangneung-gyeongpo", "경포대"): {
        "lat": 37.7950602, "lng": 128.8966038,
        "addr": "강원특별자치도 강릉시 경포로 365",
    },
    ("gangneung-anmok-sunrise", "안목 커피거리"): {
        "lat": 37.7720407, "lng": 128.9480675,
        "addr": "강원특별자치도 강릉시 창해로14번길 20-1",
    },
    ("busan-songdo-cloud", "송도해상케이블카"): {
        "lat": 35.0766432, "lng": 129.0233986,
        "addr": "부산광역시 서구 송도해변로 171",
    },
    ("jeju-yongdam-coast", "용담해안도로"): {
        "lat": 33.5198621, "lng": 126.4983762,
        "addr": "제주특별자치도 제주시 용담삼동 2580",
    },
    ("changwon-yongji", "용지호수공원"): {
        "lat": 35.23254, "lng": 128.6804663,
        "addr": "경상남도 창원시 성산구 용지로169번길 26",
    },
    ("gwangju-gwangjucheon", "양동시장"): {
        "lat": 35.1535259, "lng": 126.9016926,
        "addr": "전남광주통합특별시 서구 천변좌로 238",
    },
    ("gwangju-yeongsangang", "영산강 5km 반환점"): {
        "addr": "전남광주통합특별시 광산구 영산강 자전거길",
    },
    ("yeosu-expo-9k", "이순신광장"): {
        "lat": 34.7395739, "lng": 127.7360329,
        "addr": "전남광주통합특별시 여수시 선어시장길 6",
    },
    ("seoul-seokchon-lake", "석촌호수 동호"): {
        "lat": 37.5098236, "lng": 127.1051384,
        "addr": "서울특별시 송파구 신천동",
    },
    ("seoul-gyeongbokgung-wall", "영추문"): {
        "lat": 37.5787738, "lng": 126.9740754,
        "addr": "서울특별시 종로구 사직로 161",
    },
    ("seoul-gyeongbokgung-wall", "신무문"): {
        "lat": 37.5835591, "lng": 126.9755257,
        "addr": "서울특별시 종로구 사직로 161",
    },
    ("seoul-childrens-park", "팔각당"): {
        "lat": 37.5499022, "lng": 127.0824806,
        "addr": "서울특별시 광진구 능동로 216",
    },
    ("seoul-bugak-skyway", "북악 팔각정"): {
        "lat": 37.6015694, "lng": 126.9806665,
        "addr": "서울특별시 종로구 북악산로 267",
    },
    ("anyang-hakuicheon", "학의천 2.5km 반환점"): {
        "addr": "경기도 안양시 동안구 관양동 학의천 산책로",
    },
    ("anyang-anyangcheon-jongju", "기아대교"): {
        "lat": 37.4387325, "lng": 126.900425,
        "addr": "서울특별시 금천구 시흥동",
    },
    ("anyang-anyangcheon-jongju", "안양천 합수부"): {
        "lat": 37.5539145, "lng": 126.8780014,
        "addr": "서울특별시 강서구 염창동",
    },
    ("uiwang-baegun-lake", "백운호수 동쪽 데크"): {
        "addr": "경기도 의왕시 학의동 백운호수",
    },
    ("osan-osancheon", "오산천 3km 반환점"): {
        "addr": "경기도 오산시 오산동 오산천 산책로",
    },
    ("hwaseong-jebudo", "제부도 바닷길 입구"): {
        "addr": "경기도 화성시 만세구 서신면 제부리",
    },
    ("yeosu-old-railroad", "구 덕양역"): {
        "lat": 34.8123121, "lng": 127.6273786,
        "addr": "전남광주통합특별시 여수시 소라면",
    },
    ("yeosu-old-railroad", "만흥공원"): {
        "lat": 34.7729387, "lng": 127.7441236,
        "addr": "전남광주통합특별시 여수시 만흥동 산258-9",
    },
    ("ulsan-taehwagang", "십리대숲"): {
        "lat": 35.5458717, "lng": 129.2971766,
        "addr": "울산광역시 중구 태화동",
    },
}

LANDMARK_FIXES = {
    ("busan-gwangalli-night", "광안대교"): {
        "lat": 35.14548, "lng": 129.1282041,
        "addr": "부산광역시 수영구",
    },
    ("daegu-suseongmot", "들안길"): {
        "addr": "대구광역시 수성구 들안로 일대",
    },
    ("ulsan-taehwagang", "태화강전망대"): {
        "addr": "울산광역시 남구 남산로 223",
    },
}


def main() -> None:
    files: list[tuple[pathlib.Path, object]] = []
    courses: dict[str, dict] = {}
    for path in sorted(DATA.glob("*.json")):
        raw = json.loads(path.read_text())
        files.append((path, raw))
        for region in raw if isinstance(raw, list) else [raw]:
            for course in region["courses"]:
                courses[course["id"]] = course

    if len(courses) != 64:
        raise RuntimeError(f"코스 수 오류: {len(courses)}")
    unknown = ONE_WAY_IDS - set(courses)
    if unknown:
        raise RuntimeError("없는 oneWay 코스: " + ", ".join(sorted(unknown)))

    for course_id, course in courses.items():
        course["shape"] = "oneWay" if course_id in ONE_WAY_IDS else "roundTrip"
        move_names = LANDMARKS.get(course_id, set())
        existing_landmarks = {item["n"]: item for item in course.get("landmarks", [])}
        kept = []
        for poi in course.get("poi", []):
            if poi["n"] in move_names:
                existing_landmarks[poi["n"]] = poi
            else:
                kept.append(poi)
        course["poi"] = kept
        if existing_landmarks:
            course["landmarks"] = list(existing_landmarks.values())

        for poi in course["poi"]:
            poi.update(POI_FIXES.get((course_id, poi["n"]), {}))
        for landmark in course.get("landmarks", []):
            landmark.update(LANDMARK_FIXES.get((course_id, landmark["n"]), {}))

    suseong = courses["daegu-suseongmot"]
    if not any(poi["n"] == "수성못 상화동산" for poi in suseong["poi"]):
        suseong["poi"].append({
            "n": "수성못 상화동산",
            "d": "수성못 북쪽의 잔디 공원. 한 바퀴 중간에 호흡을 고르고 다시 수변으로 붙는 지점이다.",
            "lat": 35.8289909,
            "lng": 128.6209378,
            "addr": "대구광역시 수성구 무학로 112",
            "naver": "https://map.naver.com/p/search/%EC%88%98%EC%84%B1%EB%AA%BB%20%EC%83%81%ED%99%94%EB%8F%99%EC%82%B0",
        })

    dongtan = courses["hwaseong-dongtan-lake"]
    dongtan["poi"][0].update({
        "lat": 37.1663729,
        "lng": 127.1017694,
        "addr": "경기도 화성시 동탄구 동탄호수2길 40",
    })
    for waypoint in [
        {
            "n": "동탄호수공원 물놀이장",
            "d": "호수 동쪽 산책로의 구간 표지. 여름에는 이용객이 많아 속도를 낮춰 통과한다.",
            "lat": 37.1693179,
            "lng": 127.1072297,
            "addr": "경기도 화성시 동탄구 동탄호수2길 158",
            "naver": "https://map.naver.com/p/search/%EB%8F%99%ED%83%84%ED%98%B8%EC%88%98%EA%B3%B5%EC%9B%90%20%EB%AC%BC%EB%86%80%EC%9D%B4%EC%9E%A5",
        },
        {
            "n": "동탄호수공원 제방가로원",
            "d": "호수 서쪽 제방을 따라 난 평탄한 길. 한 바퀴의 후반부를 알리는 지점이다.",
            "lat": 37.1691122,
            "lng": 127.0971396,
            "addr": "경기도 화성시 동탄구 동탄호수2길 25",
            "naver": "https://map.naver.com/p/search/%EB%8F%99%ED%83%84%ED%98%B8%EC%88%98%EA%B3%B5%EC%9B%90%20%EC%A0%9C%EB%B0%A9%EA%B0%80%EB%A1%9C%EC%9B%90",
        },
    ]:
        if not any(poi["n"] == waypoint["n"] for poi in dongtan["poi"]):
            dongtan["poi"].append(waypoint)

    # 9km 구간은 엑스포에서 돌산까지 끝나는 편도다. "한 바퀴" 표현을 제거한다.
    yeosu = courses["yeosu-expo-9k"]
    yeosu["n"] = "엑스포에서 돌산까지 해안 9km"
    yeosu["headline"] = "엑스포와 오동도, 돌산까지 이어 달리는 여수 해안 9km"
    yeosu["body"][0] = (
        "여수엑스포해양공원에서 출발해 오동도와 이순신광장을 지나 돌산 방면 "
        "여수해상케이블카 아래에서 마치는 약 9km 편도 코스다. 2012년 세계박람회가 "
        "열린 바다 옆 광장길이 초반 워밍업 구간이 된다."
    )

    ttukseom = courses["seoul-ttukseom-7k"]
    ttukseom["n"] = "뚝섬 한강변 왕복"
    ttukseom["body"][0] = (
        "뚝섬유원지역에서 나오면 바로 한강공원이다. 상류 쪽 청담대교를 먼저 찍고 "
        "도심 방향으로 돌아 영동대교까지 이어 간 뒤 출발점으로 복귀하는 왕복 약 "
        "7km 구성이다. 자벌레 전망대와 요트장이 중간의 볼거리 역할을 한다."
    )

    courses["busan-gwangalli-night"]["body"][0] = (
        "광안리해수욕장 서쪽에서 출발해 백사장을 오른쪽에 두고 동쪽으로 달린다. "
        "해변 산책로 어디에서든 광안대교가 시야에 들어오고, 약 1.4km 지점에서 "
        "민락수변공원 구간으로 넘어간다."
    )

    courses["chuncheon-uiamho"]["body"][1] = courses["chuncheon-uiamho"]["body"][1].replace(
        "8~10km", "11~12km")

    gyeongpo = courses["gangneung-gyeongpo"]
    gyeongpo["headline"] = "경포호와 경포대, 초당 솔숲을 잇는 6.8km"
    gyeongpo["body"][0] = (
        "경포호 둘레 자체는 약 4.3km다. 이 코스는 호숫길에서 경포대와 "
        "허난설헌생가터까지 이어 달린 뒤 출발점으로 돌아와 약 6.8km가 된다. "
        "호수만 짧게 돌 날과 문화유산 구간까지 붙일 날을 구분해 쓰기 좋다."
    )
    for poi in gyeongpo["poi"]:
        if poi["n"] == "경포호":
            poi["d"] = "호수 둘레 자체는 약 4.3km. 이 코스는 경포대와 초당 솔숲까지 연결해 6.8km로 돈다."

    anmok = courses["gangneung-anmok-sunrise"]
    anmok["headline"] = "안목해변, 해 뜨는 길과 커피를 잇는 6.3km"
    anmok["body"] = [text.replace("5km가 짧게", "6.3km가 짧게") for text in anmok["body"]]

    sincheon = courses["daegu-sincheon"]
    sincheon["body"][3] = sincheon["body"][3].replace("10km를 처음", "15km를 처음")
    sincheon["body"][5] = sincheon["body"][5].replace("왕복 10km", "왕복 약 15km")

    courses["pohang-yeongildae"]["headline"] = "영일대 해변과 환호공원을 잇는 4.5km"

    yongji = courses["changwon-yongji"]
    yongji["n"] = "용지호수 도심 순환"
    yongji["headline"] = "용지호수와 문화공원을 잇는 5.1km"
    yongji["body"][0] = (
        "용지호수공원에서 출발해 용지문화공원, 성산아트홀, 창원시청을 차례로 "
        "지난 뒤 호수로 돌아오는 약 5.1km 도심 순환이다. 호수만 짧게 돌 때와 "
        "도심 구간까지 붙일 때를 나눠 쓸 수 있다."
    )

    dreamroad = courses["changwon-jinhae-dreamroad"]
    dreamroad["body"][4] = dreamroad["body"][4].replace("9km를 75분", "12km를 100분")

    nammaeji = courses["gyeongsan-nammaeji"]
    nammaeji["headline"] = "남매지와 영남대 방면 연결로를 묶는 2.7km"
    nammaeji["body"][0] = (
        "남매지 데크길에서 출발해 호수 둘레와 영남대 방면 연결로를 묶어 "
        "돌아오면 약 2.7km다. 호수 둘레만 짧게 돌거나 연결로를 붙여 거리를 "
        "늘릴 수 있어 생활권 러닝에 쓰기 좋다."
    )

    deokjin = courses["jeonju-deokjin"]
    deokjin["body"] = [text.replace("3km가 짧게", "2.1km가 짧게") for text in deokjin["body"]]

    old_rail = courses["yeosu-old-railroad"]
    old_rail["body"][0] = (
        "덕양역 자리에서 출발해 옛 전라선 공원길과 연결 도로를 따라 만흥공원까지 "
        "가는 약 19.5km 편도 코스다. 폐선 공원 자체는 약 16km이고, 출발·도착 "
        "접근 구간을 합친 거리가 GPX에 담긴다."
    )
    old_rail["body"] = [text.replace("16km가 지루하지", "19.5km가 지루하지")
                        .replace("16km 전부", "19.5km 전부") for text in old_rail["body"]]

    dongcheon = courses["suncheon-dongcheon"]
    dongcheon["body"][0] = (
        "동천 둔치에서 출발해 국가정원 담장 바깥 하천길과 죽도봉공원 아래를 "
        "연결한 뒤 출발점으로 돌아오는 약 10.3km 왕복이다. 관광지는 담장 안이 "
        "아니라 무료로 이용할 수 있는 동천 하천변에서 바라본다."
    )

    banpo = courses["seoul-banpo-10k"]
    banpo["headline"] = "반포 한강공원과 한남대교를 잇는 야간 7.2km"
    banpo["body"][3] = (
        "서버 GPX의 기본 코스는 반포한강공원에서 한남대교 반환 구간을 묶은 약 "
        "7.2km다. 10km를 채우려면 잠수교 왕복을 한 차례 더 붙이면 된다. "
        "편의점과 화장실, 식수대가 이어져 보급 동선도 짧다."
    )

    yeouido = courses["seoul-yeouido-5k"]
    yeouido["n"] = "여의도 한강 첫 6K"
    yeouido["headline"] = "여의도, 직선 끝에 페이스만 남는 첫 6K"
    yeouido["body"][0] = yeouido["body"][0].replace("5km가 얼추 맞는다", "약 6km가 된다")
    yeouido["body"][1] = yeouido["body"][1].replace("첫 5K", "첫 6K")
    yeouido["body"][5] = yeouido["body"][5].replace("첫 5km", "첫 6km")

    olympic = courses["seoul-olympic-loop"]
    olympic["headline"] = "올림픽공원 몽촌토성 안쪽을 도는 2.9km"
    olympic["body"][1] = olympic["body"][1].replace("약 3.5km", "약 2.9km")

    ilsan = courses["goyang-ilsan-lake"]
    ilsan["headline"] = "일산호수공원 주요 광장을 잇는 6.1km 순환"
    ilsan["body"][0] = (
        "호수 둘레의 한울광장, 노래하는분수대, 고양꽃전시관을 모두 잇는 서버 "
        "코스는 약 6.1km다. 공원 안쪽의 짧은 둘레만 돌면 약 4.7km라 그날 "
        "훈련 거리에 맞춰 갈림길을 고를 수 있다."
    )

    anyang = courses["anyang-anyangcheon-jongju"]
    anyang["headline"] = "안양천 종주, 쌍개울에서 한강 합수부까지 22km"
    anyang["body"][0] = anyang["body"][0].replace("약 14km다", "약 22km다")

    giheung = courses["yongin-giheung-lake"]
    giheung["headline"] = "기흥호수공원, 노면이 네 번 바뀌는 12.9km"
    giheung["body"][0] = giheung["body"][0].replace("약 10km", "약 12.9km").replace(
        "10km라는", "12.9km라는")
    giheung["body"][4] = giheung["body"][4].replace("10km 기록", "13km 기록")
    giheung["body"][5] = giheung["body"][5].replace("10km가 한 바퀴", "12.9km가 한 바퀴")

    dongtan["headline"] = "동탄호수공원 바깥 산책로를 도는 야간 4.5km"
    dongtan["body"][0] = (
        "동탄호수공원의 물놀이장과 서쪽 제방가로원을 잇는 바깥 산책로를 돌면 "
        "약 4.5km다. 짧은 호숫가 구간만 반복하거나 바깥 순환을 한 번에 도는 "
        "방식으로 거리를 조절할 수 있다."
    )
    dongtan["body"][1] = (
        "루나쇼 분수는 달리는 길에서 바라보는 야간 랜드마크다. 공연 시간에는 "
        "관람 인파가 몰리므로 분수 쪽으로 들어가지 않고 산책로를 이어 간다."
    )

    for path, raw in files:
        path.write_text(json.dumps(raw, ensure_ascii=False, indent=1) + "\n")
    print(f"shape 64개·landmarks {sum(len(c.get('landmarks', [])) for c in courses.values())}개 반영")


if __name__ == "__main__":
    main()
