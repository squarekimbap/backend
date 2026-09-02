#!/usr/bin/env python3
"""경유지 좌표 전수 점검 — 이름은 맞는데 '엉뚱한 가게'가 찍힌 것을 찾는다.

기존 fill_missing_coords.py 는 후보의 **이름·업종을 확인하지 않고** 기존 지점에서
km*1000 반경 안이면 채택했다. 그래서 반포 코스의 '한남대교'가 9.9km 떨어진
송파구 방이동 술집(술집>요리주점)으로 박혔다. 네이버는 같은 이름으로
진짜 다리(도로시설>교량명)와 동명 업소를 함께 준다.

여기서는 두 가지를 같이 본다.
  ① 업종 — 경유지(다리·공원·산·호수)가 음식점·술집·숙박으로 잡혔으면 오답
  ② 거리 — 같은 코스의 다른 경유지 무리에서 얼마나 떨어졌는가

    python3 audit_coords.py            # 점검만(보고서 출력, 파일 수정 없음)
    python3 audit_coords.py --apply    # 확정 수정안(FIX)만 반영
"""
import glob, json, math, pathlib, re, subprocess, sys, time, urllib.parse

ROOT = pathlib.Path(__file__).parent
APPLY = '--apply' in sys.argv

# 경유지로 쓸 수 없는 업종 — 이 접두사로 시작하면 동명 업소로 본다
BIZ = ('음식점', '술집', '카페', '숙박', '쇼핑', '생활', '학원', '교육', '의료', '병원',
       '부동산', '미용', '자동차', '기업', '회사', '금융', '종교', '스포츠>운동시설')
# 지리 지물로 인정하는 업종
GEO = ('도로시설', '지명', '여행', '관광', '공원', '레저', '스포츠', '문화', '교통')


def env_key(name):
    for line in (ROOT.parent / '.env').read_text().splitlines():
        if line.startswith(name + '='):
            return line.split('=', 1)[1].strip()
    raise SystemExit(f'.env에 {name} 없음')


NID, NSEC = env_key('NAVER_CLIENT_ID'), env_key('NAVER_CLIENT_SECRET')


def hav(a, b):
    R = 6371000
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    x = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(x), math.sqrt(1 - x))


def norm(s):
    return re.sub(r'[\s()\[\]·,._-]', '', s or '').lower()


def naver(query):
    """네이버 지역검색 → [(title, category, addr, lat, lng)]"""
    url = ('https://naverapihub.apigw.ntruss.com/search/v1/local?query='
           + urllib.parse.quote(query) + '&display=5')
    try:
        out = subprocess.run(['curl', '-s', '-m', '15', url,
                              '-H', f'X-NCP-APIGW-API-KEY-ID: {NID}',
                              '-H', f'X-NCP-APIGW-API-KEY: {NSEC}'],
                             capture_output=True, timeout=20).stdout
        d = json.loads(out or b'{}')
    except Exception:
        return []
    res = []
    for it in d.get('items', []):
        try:
            lng, lat = int(it['mapx']) / 1e7, int(it['mapy']) / 1e7
        except Exception:
            continue
        if not (124 <= lng <= 132 and 33 <= lat <= 39):
            continue
        res.append((re.sub(r'<[^>]+>', '', it.get('title', '')),
                    it.get('category', ''),
                    it.get('roadAddress') or it.get('address') or '',
                    lat, lng))
    time.sleep(0.15)
    return res


def load():
    files = {}
    for path in sorted(glob.glob(str(ROOT / 'data' / '*.json'))):
        raw = json.load(open(path))
        files[path] = raw
    return files


def courses_of(raw):
    for reg in ([raw] if isinstance(raw, dict) else raw):
        for c in reg['courses']:
            yield reg, c


def audit():
    reports = []
    for path, raw in load().items():
        for reg, c in courses_of(raw):
            places = [p for p in c.get('poi', []) + c.get('landmarks', []) if p.get('lat')]
            if not places:
                continue
            # 경로 트리거가 도로 위로 스냅된 경우에도 실제 장소 중심을 점검한다.
            pts = [(p.get('placeLat', p['lat']), p.get('placeLng', p['lng'])) for p in places]
            for p in places:
                here = (p.get('placeLat', p['lat']), p.get('placeLng', p['lng']))
                # 같은 코스의 다른 경유지까지 최단 거리 — 무리에서 떨어졌는가
                others = [q for q in pts if q != here]
                near = min((hav(here, q) for q in others), default=0.0)
                cands = naver(p['n']) + naver(f"{reg['city']} {p['n']}")
                wanted = norm(p['n'])
                exact = [x for x in cands
                         if wanted == norm(x[0]) or wanted in norm(x[0]) or norm(x[0]) in wanted]
                # 저장된 좌표가 어떤 후보인지 (150m 안이면 그 후보로 본다)
                mine = next((x for x in cands if hav(here, (x[3], x[4])) < 150), None)
                mycat = mine[1] if mine else ''
                geo = [x for x in exact if x[1].startswith(GEO)]
                biz_hit = bool(mycat) and mycat.startswith(BIZ)
                # 지리 지물 후보 중 코스 무리에 가장 가까운 것
                best = None
                if geo and others:
                    best = min(geo, key=lambda x: min(hav((x[3], x[4]), q) for q in others))
                elif geo:
                    best = geo[0]
                moved = hav(here, (best[3], best[4])) if best else 0.0
                if biz_hit or (best and moved > 400) or near > 4000:
                    reports.append(dict(
                        course=c['id'], city=reg['city'], km=c.get('km'), name=p['n'],
                        cur=here, cur_cat=mycat, cur_addr=p.get('addr'),
                        near_m=round(near), biz=biz_hit,
                        best=(best[0], best[1], best[2], best[3], best[4]) if best else None,
                        moved_m=round(moved)))
    return reports


if __name__ == '__main__':
    rs = audit()
    print(f'\n의심 경유지 {len(rs)}건\n' + '=' * 100)
    for r in sorted(rs, key=lambda r: -r['moved_m']):
        flag = '업종오답' if r['biz'] else ('멀리떨어짐' if r['near_m'] > 4000 else '위치차이')
        print(f"\n[{flag}] {r['course']} ({r['km']}km) — {r['name']}")
        print(f"   현재 {r['cur'][0]:.5f},{r['cur'][1]:.5f}  업종={r['cur_cat'] or '?'}  "
              f"주소={r['cur_addr']}  코스무리까지 {r['near_m']}m")
        if r['best']:
            b = r['best']
            print(f"   제안 {b[3]:.5f},{b[4]:.5f}  업종={b[1]}  주소={b[2]}  (이동 {r['moved_m']}m)")
