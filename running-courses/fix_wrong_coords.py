#!/usr/bin/env python3
"""동명 업소로 잘못 박힌 경유지 좌표를 바로잡는다 (audit_coords.py 결과 반영).

원인: fill_missing_coords.py 가 후보의 **이름·업종을 확인하지 않고** 기존 지점에서
km*1000 반경 안이면 채택했다. 네이버는 같은 이름으로 진짜 지물과 동명 업소를 함께
주기 때문에, '한남대교'가 송파구 방이동 술집(술집>요리주점)으로 박히는 일이 생겼다.

여기 값은 네이버 지역검색(업종=도로시설/지명/여행,명소 등 지리 지물)과
한국관광공사 키워드검색으로 교차 확인해 고른 것이다.

    python3 fix_wrong_coords.py --dry
    python3 fix_wrong_coords.py            # data/*.json 수정 + nextM 재계산
"""
import glob, json, math, pathlib, subprocess, sys

ROOT = pathlib.Path(__file__).parent
DRY = '--dry' in sys.argv
NO_TMAP = '--no-tmap' in sys.argv

# 코스 → 경유지 이름 → (lat, lng, addr, 근거)
FIXES = {
    'seoul-banpo-10k': {
        '한남대교': (37.52691, 127.01316, '서울특별시 강남구 신사동', '술집(송파구 방이동)으로 박혀 8.6km 어긋남'),
        '반포대교': (37.51585, 126.99589, '서울특별시 서초구 반포동', '지역명소 표기 → 교량 지점'),
    },
    'seoul-ttukseom-7k': {
        '청담대교': (37.52620, 127.06427, '서울특별시 광진구 자양동', '강동구 고깃집으로 박혀 6.0km 어긋남'),
        '영동대교': (37.53029, 127.05731, '서울특별시 성동구 성수동2가', '상가 점포 → 교량 지점'),
    },
    'seoul-wirye-humanring': {
        '위례중앙광장': (37.47300, 127.14186, '경기도 성남시 수정구 창곡동 506', '고깃집 → 광장'),
        '창곡천': (37.46912, 127.14645, '경기도 성남시 수정구 창곡동 527-1', '코스에서 1.8km 떨어져 있었음'),
    },
    'seoul-gyeongbokgung-wall': {
        '광화문': (37.57603, 126.97684, '서울특별시 종로구 사직로 161', '세종대로 광장 → 실제 문 위치'),
    },
    'cheonan-gokgyocheon': {
        '곡교천': (36.79735, 126.99679, '충청남도 아산시 온천동', '9.4km 하류로 박혀 있었음'),
        '현충사': (36.81132, 127.03231, '충청남도 아산시 염치읍 현충사길 48', '주소 없는 근사치 → 정확 지점'),
    },
    'jeonju-jeonjucheon-dawn': {
        '전주천': (35.80184, 127.17550, '전북특별자치도 전주시 완산구 대성동 1030-1', '코스에서 5.2km 떨어져 있었음'),
    },
    'jeonju-deokjin': {
        '덕진공원': (35.84747, 127.12185, '전북특별자치도 전주시 덕진구 권삼득로 390', '한식당으로 박혀 1.8km 어긋남'),
    },
    'gwangju-gwangjucheon': {
        '광주천': (35.15689, 126.89999, '전남광주통합특별시 서구', '자전거길 지점과 같은 좌표였음'),
    },
    'daegu-jinbatgol-uphill': {
        '용지봉': (35.80320, 128.64973, '대구광역시 수성구 범물동', '한정식집(들안로)으로 박혀 4.3km 어긋남'),
    },
    'daegu-duryu-park': {
        '두류공원 야외음악당': (35.85108, 128.55616, '대구광역시 달서구 야외음악당로 180', '중구 치킨집으로 박혀 2.3km 어긋남'),
        '83타워': (35.85273, 128.56689, '대구광역시 달서구 두류공원로 200', '근사치 → 전망대 지점'),
    },
    'ulsan-taehwagang': {
        '십리대숲': (35.54877, 129.29120, '울산광역시 중구 태화동 667', '주점으로 박혀 958m 어긋남'),
    },
    'chuncheon-uiamho': {
        '의암호': (37.87845, 127.70209, '강원특별자치도 춘천시 서면', '케이블카 승강장 → 호수'),
    },
    'sokcho-yeongnangho': {
        '범바위': (38.21427, 128.58082, '강원특별자치도 속초시 영랑호반길 140', '호수 밖 4.6km 지점에 박혀 있었음'),
    },
    'busan-gwangalli-night': {
        '광안리해수욕장': (35.15412, 129.12014, '부산광역시 수영구 광안해변로 219', '주소 없는 근사치 → 해변 지점'),
    },
    'busan-oncheoncheon': {
        '온천천 카페거리': (35.19163, 129.10246, '부산광역시 동래구 온천천로 451', '개별 베이커리 → 거리 기준점'),
    },
    'jeju-yongdam-coast': {
        '용두암': (33.51608, 126.51173, '제주특별자치도 제주시 용두암길 15', '해산물 식당으로 박혀 853m 어긋남'),
    },
    'jeju-saryeoni': {
        '사려니오름': (33.34267, 126.64444, '제주특별자치도 서귀포시 남원읍 516로 1662-300', '휴양림 시설 → 오름'),
    },
    'siheung-sihwa-seawall': {
        '오이도': (37.34354, 126.69464, '경기도 시흥시 정왕동 오이도', '조개구이집 → 섬 기준점'),
    },
}

# 좌표는 맞지만 코스 경로 밖이라 '지나는 곳'에서 빼고 related로 옮길 경유지.
# (경로 위 지점만 남겨야 '이런 곳을 지나요'와 구간거리가 맞는다)
OFF_ROUTE = {
    'cheonan-gokgyocheon': ['천안삼거리공원'],   # 아산 코스인데 천안 시내, 5.3km 밖
    'cheongju-musimcheon': ['문암생태공원'],     # 벚꽃철 대피 코스로 언급된 곳, 4.8km 밖
}


def hav(a, b):
    R = 6371000
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    x = (math.sin(math.radians(b[0] - a[0]) / 2) ** 2
         + math.cos(p1) * math.cos(p2) * math.sin(math.radians(b[1] - a[1]) / 2) ** 2)
    return R * 2 * math.asin(math.sqrt(x))


def main():
    changed, moved_anchor, off = [], [], []
    touched_courses = set()
    for path in sorted(glob.glob(str(ROOT / 'data' / '*.json'))):
        raw = json.load(open(path))
        was_dict = isinstance(raw, dict)
        regions = [raw] if was_dict else raw
        dirty = False
        for reg in regions:
            for c in reg['courses']:
                for name, (la, ln, addr, why) in FIXES.get(c['id'], {}).items():
                    p = next((q for q in c['poi'] if q['n'] == name), None)
                    if not p:
                        raise SystemExit(f"경유지 없음: {c['id']}/{name}")
                    old = (p['lat'], p['lng'])
                    d = hav(old, (la, ln))
                    # 코스 대표 좌표(주변 맛집 기준점)가 이 경유지에 묶여 있으면 같이 옮긴다
                    if abs(c['lat'] - old[0]) < 1e-9 and abs(c['lng'] - old[1]) < 1e-9:
                        c['lat'], c['lng'] = la, ln
                        moved_anchor.append(c['id'])
                    p['lat'], p['lng'], p['addr'] = la, ln, addr
                    p['_moved'] = True     # 이 지점이 낀 구간만 nextM을 다시 잰다
                    changed.append((c['id'], name, round(d), why))
                    dirty = True
                    touched_courses.add(c['id'])
                for name in OFF_ROUTE.get(c['id'], []):
                    p = next((q for q in c['poi'] if q['n'] == name), None)
                    if not p:
                        raise SystemExit(f"경유지 없음: {c['id']}/{name}")
                    i = c['poi'].index(p)
                    if i > 0:
                        c['poi'][i - 1]['_moved'] = True   # 앞 구간의 목적지가 바뀐다
                    c['poi'].remove(p)
                    c.setdefault('related', []).append({
                        'n': p['n'], 'd': p.get('d'), 'photo': None, 'addr': p.get('addr'),
                        'lat': p.get('lat'), 'lng': p.get('lng'),
                        'naver': p.get('naver'), 'nextM': None})
                    off.append((c['id'], name))
                    dirty = True
                    touched_courses.add(c['id'])
        if dirty and not DRY:
            pathlib.Path(path).write_text(
                json.dumps(regions[0] if was_dict else regions, ensure_ascii=False, indent=1))
    return changed, moved_anchor, off, touched_courses


def walk_m(a, b):
    """TMAP 보행 경로 거리(m). 실패하면 None.

    ⚠️ enrich_poi.walk_m 을 import 해서 쓰지 않는다 — 그 모듈은 최상위가 스크립트라
    import 만으로 217개 poi 전체를 다시 지오코딩하고 TMAP 일 한도를 태워버린다.
    """
    body = json.dumps({
        'startX': f'{a[1]:.6f}', 'startY': f'{a[0]:.6f}',
        'endX': f'{b[1]:.6f}', 'endY': f'{b[0]:.6f}',
        'reqCoordType': 'WGS84GEO', 'resCoordType': 'WGS84GEO',
        'startName': 'a', 'endName': 'b'})
    key = next(l.split('=', 1)[1].strip() for l in (ROOT.parent / '.env').read_text().splitlines()
               if l.startswith('TMAP_APP_KEY='))
    try:
        out = subprocess.run(
            ['curl', '-s', '-m', '20',
             'https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&format=json',
             '-H', f'appKey: {key}', '-H', 'Content-Type: application/json',
             '-X', 'POST', '--data-binary', body], capture_output=True, timeout=25).stdout
        d = json.loads(out or b'{}')
        for f in d.get('features', []):
            if 'totalDistance' in f.get('properties', {}):
                return int(f['properties']['totalDistance'])
        if d.get('error'):
            print(f"   TMAP 오류: {d['error'].get('code')} {d['error'].get('message')}")
    except Exception as e:
        print(f'   TMAP 예외: {e}')
    return None


def recompute_next_m(course_ids):
    """좌표가 바뀐 구간만 nextM을 다시 잰다.

    바뀌지 않은 구간은 기존 값을 그대로 둔다(TMAP 호출을 아끼고, 멀쩡한 값을 날리지 않는다).
    다시 재지 못하면 None으로 비운다 — 틀린 거리를 남기는 것보다 없는 편이 낫다.
    """
    done = []
    for path in sorted(glob.glob(str(ROOT / 'data' / '*.json'))):
        raw = json.load(open(path))
        was_dict = isinstance(raw, dict)
        regions = [raw] if was_dict else raw
        dirty = False
        for reg in regions:
            for c in reg['courses']:
                if c['id'] not in course_ids:
                    continue
                pois = c['poi']
                for i in range(len(pois) - 1):
                    a, b = pois[i], pois[i + 1]
                    if not (a.get('_moved') or b.get('_moved')):
                        continue          # 양끝이 그대로면 기존 구간거리가 유효하다
                    m = walk_m((a['lat'], a['lng']), (b['lat'], b['lng']))
                    a['nextM'] = m
                    done.append((c['id'], a['n'], m))
                    dirty = True
                # 마지막 경유지에는 다음 구간이 없다 — 원본 스키마대로 키를 두지 않는다
                if pois:
                    pois[-1].pop('nextM', None)
                for p in pois:
                    p.pop('_moved', None)
                dirty = True
        if dirty:
            pathlib.Path(path).write_text(
                json.dumps(regions[0] if was_dict else regions, ensure_ascii=False, indent=1))
    return done


if __name__ == '__main__':
    ch, anch, off, ids = main()
    print(f'좌표 정정 {len(ch)}건')
    for cid, n, d, why in ch:
        print(f'  {cid:26} {n:14} {d:6}m 이동 — {why}')
    print(f'\n대표좌표(맛집 기준점) 함께 이동: {len(anch)}개 코스 — {", ".join(sorted(set(anch)))}')
    print(f'경로 밖 경유지를 related로 이동: {len(off)}건 — {off}')
    if not DRY and not NO_TMAP:
        print(f'\nnextM 재계산 ({len(ids)}개 코스)…')
        for cid, n, m in recompute_next_m(ids):
            print(f'  {cid:26} {n:14} → {m}')
