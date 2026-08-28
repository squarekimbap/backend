#!/usr/bin/env python3
"""코스 데이터 정리 (일회성).

1) 무의미한 구간거리(nextM) 제거
   수집본 42개 코스의 poi는 '경로상 경유지'가 아니라 그 도시의 관련 스팟 추천이
   섞여 있다(d 설명이 "야경 코스로 전환"·"다음날 이지런" 식). 순서가 경로가 아니므로
   두 지점 사이 도보거리는 오해만 부른다 — 경로형 poi가 우세한 코스만 남긴다.

2) 코스 대표 좌표(lat/lng) 부여
   지금은 '좌표 있는 마지막 poi'를 기준으로 주변 맛집을 찾는데, 온천천 코스의
   마지막 poi가 광안리라 엉뚱한 동네가 잡힌다. 코스 자체의 좌표를 박아 고정한다.
   우선순위: 코스명과 이름이 겹치는 poi > TourAPI 지오코딩 > poi 중앙값(medoid)

    python3 fix_course_anchor.py --dry
    python3 fix_course_anchor.py
"""
import glob, json, math, pathlib, re, subprocess, sys, time, urllib.parse

ROOT = pathlib.Path(__file__).parent
DRY = '--dry' in sys.argv

ROUTE = re.compile(r'구간|종점|출발|반환|마무리|통과|시작|입구|정상|대표 지점|기준점|보급')
REL = re.compile(r'전환|연장|확장|연결|대체|회복런|이지런|다음날|장거리$|코스$')
# 지명 비교에서 걸러낼 일반명사 — 이게 없으면 '해운대 코스'가 '광안리해수욕장'에 걸린다
GENERIC = {'해수욕장', '공원', '산책로', '자전거길', '둘레길', '트레일', '카페거리', '유원지',
           '호수공원', '해변', '시민공원', '생태공원', '수변공원', '데크', '광장', '전망대'}


def key(name):
    for g in GENERIC:
        name = name.replace(g, ' ')
    return {t for t in re.findall(r'[가-힣]{2,}', name)}


def hav(a, b):
    R = 6371000
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    x = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(x), math.sqrt(1 - x))


def tour_key():
    for line in (ROOT.parent / '.env').read_text().splitlines():
        if line.startswith('TOUR_API_KEY='):
            return line.split('=', 1)[1].strip()
    raise SystemExit('.env에 TOUR_API_KEY 없음')


TOUR = tour_key()


def geocode(keyword, city_token):
    q = urllib.parse.urlencode({'serviceKey': TOUR, 'MobileOS': 'ETC', 'MobileApp': 'tour-api',
                                '_type': 'json', 'numOfRows': 10, 'pageNo': 1, 'keyword': keyword})
    out = subprocess.run(['curl', '-s', '-m', '15',
                          'https://apis.data.go.kr/B551011/KorService2/searchKeyword2?' + q],
                         capture_output=True).stdout
    try:
        items = json.loads(out)['response']['body'].get('items') or {}
        items = items.get('item') or []
        if isinstance(items, dict):
            items = [items]
    except Exception:
        return None
    for it in items:
        if city_token in it.get('addr1', ''):
            try:
                return float(it['mapy']), float(it['mapx'])
            except Exception:
                return None
    return None


CITY_TOKEN = {'광주': '광주광역시', '고양': '고양', '수원': '수원'}

dropped_nextm, anchored = [], []

for path in sorted(glob.glob(str(ROOT / 'data' / '*.json'))):
    raw = json.load(open(path))
    was_dict = isinstance(raw, dict)
    regions = [raw] if was_dict else raw
    for reg in regions:
        city = reg['city']
        token = CITY_TOKEN.get(city, city)
        for c in reg['courses']:
            pois = c.get('poi', [])
            located = [p for p in pois if p.get('lat') and p.get('lng')]

            # ── 1) 경로가 아닌 코스의 nextM 제거 ──
            route = sum(1 for p in pois if ROUTE.search(p.get('d') or ''))
            rel = sum(1 for p in pois if REL.search(p.get('d') or ''))
            span = max((hav((a['lat'], a['lng']), (b['lat'], b['lng']))
                        for i, a in enumerate(located) for b in located[i + 1:]), default=0)
            incoherent = rel > route or span > float(c.get('km') or 0) * 1000
            if incoherent and any(p.get('nextM') for p in pois):
                for p in pois:
                    p.pop('nextM', None)
                dropped_nextm.append(c['id'])

            # ── 2) 코스 대표 좌표 ──
            # 틀린 좌표는 없는 것보다 나쁘다(엉뚱한 동네 맛집이 뜬다). 근거가 약하면 비워 둔다.
            c.pop('lat', None)
            c.pop('lng', None)
            names = key(c['n']) | key(c.get('headline', ''))
            hit = next((p for p in located if key(p['n']) & names), None)
            src = None
            if hit:
                c['lat'], c['lng'], src = hit['lat'], hit['lng'], f"poi:{hit['n']}"
            elif not DRY:
                # 코스명 그대로 → 도시 붙여서 → 일반명사 뺀 핵심 지명 순으로 시도
                core = ' '.join(sorted(key(c['n']), key=len, reverse=True)[:2])
                geo = None
                for kw in (c['n'], f"{city} {c['n']}", core, f"{city} {core}"):
                    if kw.strip():
                        geo = geocode(kw, token)
                        time.sleep(0.1)
                        if geo:
                            break
                if geo:
                    c['lat'], c['lng'], src = geo[0], geo[1], 'TourAPI'
                # medoid 폴백은 쓰지 않는다 — poi 자체가 도시 전역 추천이라
                # 중앙값이 엉뚱한 곳(공지천 코스에 남이섬)을 가리킨다. 근거 없으면 비워 둔다.
            if src:
                anchored.append((c['id'], src))

    if not DRY:
        out = regions[0] if was_dict else regions
        pathlib.Path(path).write_text(json.dumps(out, ensure_ascii=False, indent=1))

# courses.json 반영
if not DRY:
    cj = ROOT / 'courses.json'
    courses = json.load(open(cj))
    src_by_id = {}
    for path in glob.glob(str(ROOT / 'data' / '*.json')):
        raw = json.load(open(path))
        for reg in ([raw] if isinstance(raw, dict) else raw):
            for c in reg['courses']:
                src_by_id[c['id']] = c
    for c in courses:
        s = src_by_id.get(c['id'])
        if not s:
            continue
        if s.get('lat'):
            c['lat'], c['lng'] = s['lat'], s['lng']
        if c['id'] in dropped_nextm:
            for p in c.get('poi', []):
                p.pop('nextM', None)
    cj.write_text(json.dumps(courses, ensure_ascii=False, indent=1))
    print('courses.json 갱신')

print(f"\n무의미한 구간거리 제거: {len(dropped_nextm)}개 코스 {dropped_nextm}")
print(f"대표 좌표 부여: {len(anchored)}개")
for cid, s in anchored[:15]:
    print(f"  {cid:28} ← {s}")
