#!/usr/bin/env python3
"""poi 보강(일회성 베이크): 주소·좌표(TourAPI 키워드검색) + 네이버지도 링크 + 다음 경유지까지 도보거리(TMAP).

data/*.json(원본)과 courses.json(병합본, _img 보존)을 함께 갱신한다.
키는 저장소 루트 .env의 TOUR_API_KEY / TMAP_APP_KEY 사용.

    python3 enrich_poi.py          # 전체
    python3 enrich_poi.py --dry    # 호출 없이 대상만 출력

poi에 추가되는 필드:
  addr  주소(못 찾으면 null) · lat/lng  좌표(null 가능)
  naver https://map.naver.com/p/search/<이름>  (항상)
  nextM 다음 경유지까지 도보 거리 m (TMAP, 양쪽 좌표 있을 때만 · 마지막 poi는 없음)
"""
import json, glob, sys, time, pathlib, subprocess, urllib.parse

ROOT = pathlib.Path(__file__).parent
DRY = '--dry' in sys.argv
NO_TMAP = '--no-tmap' in sys.argv  # TMAP 일 한도(1,000) 아낄 때 — 구간거리(nextM)만 건너뜀

def env_key(name):
    for line in (ROOT.parent / '.env').read_text().splitlines():
        if line.startswith(name + '='):
            return line.split('=', 1)[1].strip()
    raise SystemExit(f'.env에 {name} 없음')

TOUR = env_key('TOUR_API_KEY')
TMAP = env_key('TMAP_APP_KEY')

# 검색 결과 주소로 도시를 검증할 토큰 (광주는 경기도 광주와 구분)
CITY_TOKEN = {'광주': '광주광역시'}

def get_json(url, headers=None, data=None, timeout=15):
    # macOS 파이썬 SSL 인증서 문제(CERTIFICATE_VERIFY_FAILED)로 urllib 대신 curl 사용
    cmd = ['curl', '-s', '-m', str(timeout), url]
    for k, v in (headers or {}).items():
        cmd += ['-H', f'{k}: {v}']
    if data is not None:
        cmd += ['-X', 'POST', '--data-binary', data]
    out = subprocess.run(cmd, capture_output=True, timeout=timeout + 5).stdout
    return json.loads(out.decode('utf-8'))

def geocode(name, city):
    """TourAPI 키워드검색 → (addr, lat, lng) 또는 (None, None, None)."""
    q = urllib.parse.urlencode({
        'serviceKey': TOUR, 'MobileOS': 'ETC', 'MobileApp': 'tour-api',
        '_type': 'json', 'numOfRows': 10, 'pageNo': 1, 'keyword': name})
    try:
        d = get_json('https://apis.data.go.kr/B551011/KorService2/searchKeyword2?' + q)
        items = d['response']['body'].get('items') or {}
        items = items.get('item') or []
        if isinstance(items, dict):
            items = [items]
    except Exception:
        return None, None, None
    token = CITY_TOKEN.get(city, city)
    best = next((it for it in items if token in it.get('addr1', '')), None)
    if not best:
        return None, None, None
    try:
        return best.get('addr1') or None, float(best['mapy']), float(best['mapx'])
    except Exception:
        return best.get('addr1') or None, None, None

def nominatim(name, city):
    """OSM Nominatim 폴백(하천·역·공원 등 관광지 DB에 없는 이름). 1req/s 준수."""
    q = urllib.parse.urlencode({'q': f'{name}, {city}', 'format': 'json', 'limit': 3, 'countrycodes': 'kr'})
    try:
        d = get_json('https://nominatim.openstreetmap.org/search?' + q,
                     headers={'User-Agent': 'eodi-run-data/1.0 (course enrichment)'})
    except Exception:
        return None, None, None
    finally:
        time.sleep(1.1)
    token = CITY_TOKEN.get(city, city)
    for it in d if isinstance(d, list) else []:
        if token in it.get('display_name', ''):
            return None, float(it['lat']), float(it['lon'])  # 주소는 도로명 아님 → addr은 비움
    return None, None, None

def walk_m(a, b):
    """TMAP 보행 경로 거리(m). 실패 시 None."""
    body = json.dumps({
        'startX': f"{a[1]:.6f}", 'startY': f"{a[0]:.6f}",
        'endX': f"{b[1]:.6f}", 'endY': f"{b[0]:.6f}",
        'reqCoordType': 'WGS84GEO', 'resCoordType': 'WGS84GEO',
        'startName': 'a', 'endName': 'b'})
    try:
        d = get_json('https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&format=json',
                     headers={'appKey': TMAP, 'Content-Type': 'application/json'}, data=body)
        for f in d.get('features', []):
            p = f.get('properties', {})
            if 'totalDistance' in p:
                return int(p['totalDistance'])
    except Exception:
        pass
    return None

def naver(name):
    return 'https://map.naver.com/p/search/' + urllib.parse.quote(name)

# ── 1) data/*.json 보강 ─────────────────────────────────────────
stats = {'poi': 0, 'geo_ok': 0, 'geo_fail': [], 'seg_ok': 0, 'seg_fail': 0}
enriched = {}  # (courseId, poiIndex) → 보강 dict (courses.json 반영용)

for path in sorted(glob.glob(str(ROOT / 'data' / '*.json'))):
    raw = json.load(open(path))
    was_dict = isinstance(raw, dict)  # seoul.json은 단일 객체
    regions = [raw] if was_dict else raw
    for reg in regions:
        city = reg['city']
        for c in reg['courses']:
            pois = c.get('poi', [])
            for i, p in enumerate(pois):
                stats['poi'] += 1
                if DRY:
                    continue
                if not p.get('lat'):  # 좌표 없으면(첫 실행·이전 실패 모두) 시도
                    addr, lat, lng = geocode(p['n'], city)
                    if not lat:  # 관광지 DB에 없는 이름(하천·역·공원 구간)은 OSM 폴백
                        _, lat, lng = nominatim(p['n'], city)
                    p['addr'], p['lat'], p['lng'] = addr, lat, lng
                    time.sleep(0.1)
                p['naver'] = naver(p['n'])
                if p.get('lat'):
                    stats['geo_ok'] += 1
                else:
                    stats['geo_fail'].append(f"{c['id']}/{p['n']}")
            # 구간 거리
            if not DRY and not NO_TMAP:
                # 코스 길이보다 터무니없이 먼 구간은 경유 순서가 아니라 '주변 추천' poi로 보고 버린다
                cap = max(5000, int(float(c.get('km', 5)) * 1000))
                for i in range(len(pois) - 1):
                    a, b = pois[i], pois[i + 1]
                    if a.get('nextM') is not None:
                        stats['seg_ok'] += 1
                    elif a.get('lat') and b.get('lat'):
                        m = walk_m((a['lat'], a['lng']), (b['lat'], b['lng']))
                        if m is not None and m > cap:
                            m = None
                        a['nextM'] = m
                        stats['seg_ok' if m else 'seg_fail'] += 1
                        time.sleep(0.1)
                    else:
                        a['nextM'] = None
                        stats['seg_fail'] += 1
            if not DRY:
                for i, p in enumerate(pois):
                    enriched[(c['id'], i)] = {k: p.get(k) for k in ('addr', 'lat', 'lng', 'naver', 'nextM') if k in p}
    if not DRY:
        out = regions[0] if was_dict else regions
        pathlib.Path(path).write_text(json.dumps(out, ensure_ascii=False, indent=1))
        print(f"{path} 갱신")

# ── 2) courses.json에 동일 반영 (_img 등 기존 필드 보존) ─────────
if not DRY:
    cj = ROOT / 'courses.json'
    courses = json.load(open(cj))
    for c in courses:
        for i, p in enumerate(c.get('poi', [])):
            p.update(enriched.get((c['id'], i), {}))
    cj.write_text(json.dumps(courses, ensure_ascii=False, indent=1))
    print('courses.json 갱신')

print(f"\npoi {stats['poi']}곳 | 지오코딩 성공 {stats['geo_ok']} · 실패 {len(stats['geo_fail'])}"
      f" | 구간거리 성공 {stats['seg_ok']} · 실패 {stats['seg_fail']}")
if stats['geo_fail']:
    print('지오코딩 실패 목록:')
    for x in stats['geo_fail']:
        print('  -', x)
