#!/usr/bin/env python3
"""waypoints.json의 실제 경유지를 코스에 반영한다.

기존 poi는 '그 도시의 관련 스팟 추천'이라 '이런 곳을 지나요'와 의미가 맞지 않았다.
여기서 작성한 경로상 경유지로 poi를 교체하고, 옛 목록은 related[]로 보존한다.

안전장치: 각 경유지를 지오코딩해 코스 앵커에서 코스 길이 이내인지 검증한다.
        벗어나면 좌표 없이 이름만 남기고(경고 출력) 잘못된 좌표를 넣지 않는다.

    python3 apply_waypoints.py --dry
    python3 apply_waypoints.py
"""
import glob, json, math, pathlib, re, subprocess, sys, time, urllib.parse

ROOT = pathlib.Path(__file__).parent
DRY = '--dry' in sys.argv


def env_key(name):
    for line in (ROOT.parent / '.env').read_text().splitlines():
        if line.startswith(name + '='):
            return line.split('=', 1)[1].strip()
    raise SystemExit(f'.env에 {name} 없음')


TOUR = env_key('TOUR_API_KEY')
NID = env_key('NAVER_CLIENT_ID')
NSEC = env_key('NAVER_CLIENT_SECRET')
CITY_TOKEN = {'광주': '광주광역시'}


def get_json(url, headers=None, timeout=15):
    cmd = ['curl', '-s', '-m', str(timeout), url]
    for k, v in (headers or {}).items():
        cmd += ['-H', f'{k}: {v}']
    return json.loads(subprocess.run(cmd, capture_output=True, timeout=timeout + 5).stdout or b'{}')


def hav(a, b):
    R = 6371000
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    x = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(x), math.sqrt(1 - x))


def similar(search, title):
    """검색어와 결과 이름이 최소한 겹치는지 — '한남대교'로 엉뚱한 업소가 잡히는 것을 막는다."""
    a = re.sub(r'[^가-힣A-Za-z0-9]', '', search)
    b = re.sub(r'[^가-힣A-Za-z0-9]', '', re.sub(r'<[^>]*>', '', title))
    if a in b or b in a:
        return True
    grams = {a[i:i + 2] for i in range(len(a) - 1)}
    return sum(1 for g in grams if g in b) >= max(1, len(grams) // 2)


def naver(name, city):
    q = urllib.parse.quote(f'{city} {name}')
    try:
        d = get_json(f'https://naverapihub.apigw.ntruss.com/search/v1/local?query={q}&display=5',
                     {'X-NCP-APIGW-API-KEY-ID': NID, 'X-NCP-APIGW-API-KEY': NSEC})
    except Exception:
        return None
    token = CITY_TOKEN.get(city, city)
    for it in d.get('items', []):
        addr = it.get('roadAddress') or it.get('address') or ''
        if token not in addr or not similar(name, it.get('title', '')):
            continue
        try:
            lng, lat = int(it['mapx']) / 1e7, int(it['mapy']) / 1e7
        except Exception:
            continue
        if 124 <= lng <= 132 and 33 <= lat <= 39:
            return addr, lat, lng
    return None


def nominatim(name, city):
    """하천·다리·둔치처럼 상호 DB에 없는 지형지물은 OSM이 갖고 있다. 1req/s 준수."""
    q = urllib.parse.urlencode({'q': f'{name}, {city}', 'format': 'json',
                                'limit': 3, 'countrycodes': 'kr'})
    try:
        d = get_json('https://nominatim.openstreetmap.org/search?' + q,
                     {'User-Agent': 'eodi-run-data/1.0 (course waypoints)'})
    except Exception:
        return None
    finally:
        time.sleep(1.1)
    token = CITY_TOKEN.get(city, city)
    for it in d if isinstance(d, list) else []:
        if token in it.get('display_name', ''):
            return None, float(it['lat']), float(it['lon'])
    return None


def tourapi(name, city):
    q = urllib.parse.urlencode({'serviceKey': TOUR, 'MobileOS': 'ETC', 'MobileApp': 'tour-api',
                                '_type': 'json', 'numOfRows': 10, 'pageNo': 1, 'keyword': name})
    try:
        d = get_json('https://apis.data.go.kr/B551011/KorService2/searchKeyword2?' + q)
        items = d['response']['body'].get('items') or {}
        items = items.get('item') or []
        if isinstance(items, dict):
            items = [items]
    except Exception:
        return None
    token = CITY_TOKEN.get(city, city)
    for it in items:
        if token in it.get('addr1', ''):
            try:
                return it.get('addr1'), float(it['mapy']), float(it['mapx'])
            except Exception:
                continue
    return None


authored = {k: v for k, v in json.load(open(ROOT / 'waypoints.json')).items()
            if not k.startswith('_')}

stats = {'ok': 0, 'nogeo': 0, 'toofar': 0}
report = []

for path in sorted(glob.glob(str(ROOT / 'data' / '*.json'))):
    raw = json.load(open(path))
    was_dict = isinstance(raw, dict)
    regions = [raw] if was_dict else raw
    touched = False
    for reg in regions:
        city = reg['city']
        for c in reg['courses']:
            wps = authored.get(c['id'])
            if not wps:
                continue
            # 기존 코스 앵커는 낡은 poi에서 나온 값이라 믿을 수 없다(세종 코스가 세종호수공원을
            # 5km 밖으로 판정했다). 대신 경유지끼리 뭉치는 정도로 서로를 검증한다.
            limit = max(2000.0, float(c.get('km') or 0) * 1000 * 0.6)

            geos = []
            for w in wps:
                name = w['n'].split(':')[-1]
                search = w['n'].split(':')[0] if ':' in w['n'] else name
                geo = naver(search, city) or tourapi(search, city) or nominatim(search, city)
                time.sleep(0.1)
                geos.append((name, w['d'], geo))

            # 가장 큰 군집(서로 limit 안에 있는 지점들)만 좌표를 인정한다
            pts = [(i, g[2][1], g[2][2]) for i, g in enumerate(geos) if g[2]]
            best = set()
            for i, la, ln in pts:
                grp = {j for j, la2, ln2 in pts if hav((la, ln), (la2, ln2)) <= limit}
                if len(grp) > len(best):
                    best = grp

            resolved = []
            for i, (name, desc, geo) in enumerate(geos):
                item = {'n': name, 'd': desc}
                if geo and i in best:
                    addr, lat, lng = geo
                    if addr:
                        item['addr'] = addr
                    item.update({'lat': lat, 'lng': lng})
                    stats['ok'] += 1
                elif geo:
                    stats['toofar'] += 1
                    report.append(f"  ⚠ {c['id']}/{name}: 다른 경유지와 {int(limit)}m 넘게 떨어짐 — 좌표 버림")
                else:
                    stats['nogeo'] += 1
                    report.append(f"  · {c['id']}/{name}: 지오코딩 실패 — 이름만 유지")
                item['naver'] = 'https://map.naver.com/p/search/' + urllib.parse.quote(name)
                resolved.append(item)

            # 코스 대표 좌표도 경유지 군집의 첫 지점으로 새로 잡는다
            first = next((p for p in resolved if p.get('lat')), None)
            if first:
                c['lat'], c['lng'] = first['lat'], first['lng']

            old = c.get('poi', [])
            if old and not c.get('related'):
                c['related'] = old  # 도시 전역 추천은 버리지 않고 옮겨 둔다
            c['poi'] = resolved
            touched = True
    if touched and not DRY:
        out = regions[0] if was_dict else regions
        pathlib.Path(path).write_text(json.dumps(out, ensure_ascii=False, indent=1))
        print(f'{pathlib.Path(path).name} 갱신')

# courses.json 반영
if not DRY:
    src = {}
    for path in glob.glob(str(ROOT / 'data' / '*.json')):
        raw = json.load(open(path))
        for reg in ([raw] if isinstance(raw, dict) else raw):
            for c in reg['courses']:
                src[c['id']] = c
    cj = ROOT / 'courses.json'
    courses = json.load(open(cj))
    for c in courses:
        s = src.get(c['id'])
        if s and c['id'] in authored:
            c['poi'] = s['poi']
            if s.get('lat'):
                c['lat'], c['lng'] = s['lat'], s['lng']
            if s.get('related'):
                c['related'] = s['related']
    cj.write_text(json.dumps(courses, ensure_ascii=False, indent=1))
    print('courses.json 갱신')

print(f"\n작성한 코스 {len(authored)}개 | 경유지 좌표 확보 {stats['ok']} · "
      f"지오코딩 실패 {stats['nogeo']} · 범위 밖 {stats['toofar']}")
for line in report:
    print(line)
