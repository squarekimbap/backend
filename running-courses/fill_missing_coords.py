#!/usr/bin/env python3
"""좌표를 못 붙인 경유지를 채운다.

두 가지 이유로 실패했었다.
  ① 설명형 이름("궁도장 오르막", "치동천 합류부")이라 검색이 안 됨 → SEARCH에 검색어를 따로 지정
  ② 도시 경계를 넘는 지점(현충사=아산, 방아머리해변=안산)이라 도시명 필터에 걸림
     → 도시 필터 대신 '같은 코스의 다른 경유지와 가까운가'로 검증한다

표시 이름은 그대로 두고 좌표만 채운다.

    python3 fill_missing_coords.py --dry
    python3 fill_missing_coords.py
"""
import glob, json, math, pathlib, re, subprocess, sys, time, urllib.parse

ROOT = pathlib.Path(__file__).parent
DRY = '--dry' in sys.argv

# 표시명 → 실제 검색어 (없으면 표시명 그대로 검색)
SEARCH = {
    '삼락강변축구장': '삼락생태공원 축구장',
    '임도삼거리': '계족산 임도',
    '곡교천 은행나무길': '아산 은행나무길',
    '현충사': '아산 현충사',
    '진밭골자연휴양림': '진밭골',
    '장복하늘마루길': '장복산',
    '광주천': '광주 광주천',
    '광주천 자전거길': '광주 광주천',
    '광주천 합류부': '광주 광주천',
    '양동시장': '광주 양동시장',
    '무등경기장': '광주 무등경기장',
    '순천왕지천': '순천 왕지동',
    '자유공원 약수터': '안양 자유공원',
    '궁도장 오르막': '안양 자유공원',
    '백운호수 방면': '의왕 백운호수',
    '백운호수 데크길': '의왕 백운호수',
    '학의천 진입로': '안양 학의천',
    '안양천 합수부': '안양천',
    '신갈천 합류부': '용인 신갈천',
    '오산천 둔치': '오산 오산천',
    '치동천 합류부': '화성 치동천',
    '루나쇼 분수': '동탄호수공원',
    '제부도 바닷길 입구': '제부도',
    '시화나래휴게소': '시화나래휴게소',
    '방아머리해변': '방아머리해수욕장',
    '남매지 데크길': '경산 남매지',
    '영남대 방면': '영남대학교',
    '승촌보': '승촌보',
    '구 덕양역': '여수 덕양',
    '기찻길 터널 구간': '여수 만성리해수욕장',
}


def env_key(name):
    for line in (ROOT.parent / '.env').read_text().splitlines():
        if line.startswith(name + '='):
            return line.split('=', 1)[1].strip()
    raise SystemExit(f'.env에 {name} 없음')


TOUR, NID, NSEC = env_key('TOUR_API_KEY'), env_key('NAVER_CLIENT_ID'), env_key('NAVER_CLIENT_SECRET')


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


def candidates(query):
    """여러 지오코더의 후보 좌표를 모아 반환 — 도시 필터는 걸지 않는다(거리로 검증)."""
    out = []
    try:
        d = get_json('https://naverapihub.apigw.ntruss.com/search/v1/local?query='
                     + urllib.parse.quote(query) + '&display=5',
                     {'X-NCP-APIGW-API-KEY-ID': NID, 'X-NCP-APIGW-API-KEY': NSEC})
        for it in d.get('items', []):
            try:
                lng, lat = int(it['mapx']) / 1e7, int(it['mapy']) / 1e7
            except Exception:
                continue
            if 124 <= lng <= 132 and 33 <= lat <= 39:
                out.append((it.get('roadAddress') or it.get('address') or None, lat, lng))
    except Exception:
        pass
    time.sleep(0.1)

    q = urllib.parse.urlencode({'serviceKey': TOUR, 'MobileOS': 'ETC', 'MobileApp': 'tour-api',
                                '_type': 'json', 'numOfRows': 10, 'pageNo': 1,
                                'keyword': re.sub(r'^\S+\s+', '', query)})
    try:
        d = get_json('https://apis.data.go.kr/B551011/KorService2/searchKeyword2?' + q)
        items = d['response']['body'].get('items') or {}
        items = items.get('item') or []
        if isinstance(items, dict):
            items = [items]
        for it in items:
            try:
                out.append((it.get('addr1'), float(it['mapy']), float(it['mapx'])))
            except Exception:
                continue
    except Exception:
        pass
    time.sleep(0.1)

    try:
        d = get_json('https://nominatim.openstreetmap.org/search?' + urllib.parse.urlencode(
            {'q': query, 'format': 'json', 'limit': 5, 'countrycodes': 'kr'}),
            {'User-Agent': 'eodi-run-data/1.0 (course waypoints)'})
        for it in d if isinstance(d, list) else []:
            out.append((None, float(it['lat']), float(it['lon'])))
    except Exception:
        pass
    time.sleep(1.1)
    return out


filled, failed = [], []

for path in sorted(glob.glob(str(ROOT / 'data' / '*.json'))):
    raw = json.load(open(path))
    was_dict = isinstance(raw, dict)
    regions = [raw] if was_dict else raw
    touched = False
    for reg in regions:
        for c in reg['courses']:
            missing = [p for p in c.get('poi', []) if not p.get('lat')]
            if not missing:
                continue
            limit = max(2500.0, float(c.get('km') or 0) * 1000)  # 편도 코스 허용
            known = [(p['lat'], p['lng']) for p in c.get('poi', []) if p.get('lat')]
            if not known and c.get('lat'):
                known = [(c['lat'], c['lng'])]

            for p in missing:
                query = SEARCH.get(p['n'], f"{reg['city']} {p['n']}")
                cands = candidates(query)
                pick = None
                if known:
                    # 이미 아는 지점들과 가장 가까운 후보를 고르고, 반경을 넘으면 버린다
                    scored = [(min(hav(k, (la, ln)) for k in known), a, la, ln) for a, la, ln in cands]
                    scored.sort()
                    if scored and scored[0][0] <= limit:
                        pick = scored[0][1:]
                elif cands:
                    pick = cands[0]  # 아직 기준점이 없으면 첫 후보를 씨앗으로 삼는다
                if pick:
                    addr, la, ln = pick
                    if addr:
                        p['addr'] = addr
                    p['lat'], p['lng'] = la, ln
                    known.append((la, ln))
                    if not c.get('lat'):
                        c['lat'], c['lng'] = la, ln
                    filled.append(f"{c['id']}/{p['n']}")
                    touched = True
                else:
                    failed.append(f"{c['id']}/{p['n']} (후보 {len(cands)}개, 전부 반경 밖)")
    if touched and not DRY:
        out = regions[0] if was_dict else regions
        pathlib.Path(path).write_text(json.dumps(out, ensure_ascii=False, indent=1))

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
        if not s:
            continue
        c['poi'] = s.get('poi', c.get('poi'))
        if s.get('lat'):
            c['lat'], c['lng'] = s['lat'], s['lng']
    cj.write_text(json.dumps(courses, ensure_ascii=False, indent=1))
    print('data/*.json · courses.json 갱신')

print(f"\n좌표 채움 {len(filled)}개 / 실패 {len(failed)}개")
for f in failed:
    print('  ✗', f)
