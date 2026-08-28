#!/usr/bin/env python3
"""poi에 섞여 있는 '연관 추천'을 related[]로 분리한다 (일회성 데이터 정리).

수집 원본의 poi에는 실제 경유지와 "거리 확장 연결"·"야경 코스로 전환" 같은
연관 추천이 함께 들어 있다. 그대로 두면 8km 코스인데 20km 떨어진 장소가
'지나는 곳'으로 뜨고, 구간 거리(nextM)도 경로가 아닌 두 지점 사이 거리가 된다.

판정: 코스 이름과 이름이 겹치는 poi를 앵커로 삼고(없으면 medoid),
      앵커에서 코스 길이 기반 반경을 벗어나면 연관 추천으로 본다.
좌표가 없는 poi는 판정할 수 없으므로 경유지로 남긴다.

    python3 split_related_poi.py --dry   # 미리보기
    python3 split_related_poi.py         # data/*.json + courses.json 갱신
"""
import glob, json, math, pathlib, re, sys

ROOT = pathlib.Path(__file__).parent
DRY = '--dry' in sys.argv


def hav(a, b):
    R = 6371000
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    x = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(x), math.sqrt(1 - x))


def tokens(text):
    """지명 비교용 2글자 이상 한글 토막."""
    return {t for t in re.findall(r'[가-힣]{2,}', text or '')}


def split(course):
    """(경유지, 연관추천) 로 나눈다. 판정 불가면 원본 그대로."""
    pois = course.get('poi', [])
    located = [p for p in pois if p.get('lat') and p.get('lng')]
    if len(located) < 2:
        return pois, []

    km = float(course.get('km') or 0)
    radius = max(2000.0, km * 1000 * 0.7)

    # 앵커: 코스 이름·헤드라인과 지명이 겹치는 poi 우선
    name_tokens = tokens(course.get('n', '')) | tokens(course.get('headline', ''))
    anchor = next((p for p in located
                   if tokens(p['n']) & name_tokens), None)
    if anchor is None:  # medoid — 나머지와의 거리 합이 가장 작은 지점
        anchor = min(located, key=lambda p: sum(
            hav((p['lat'], p['lng']), (q['lat'], q['lng'])) for q in located))

    base = (anchor['lat'], anchor['lng'])
    keep, related = [], []
    for p in pois:
        if not (p.get('lat') and p.get('lng')):
            keep.append(p)  # 좌표가 없으면 판단 불가 — 경유지로 남긴다
            continue
        (keep if hav(base, (p['lat'], p['lng'])) <= radius else related).append(p)
    return keep, related


changed = []

for path in sorted(glob.glob(str(ROOT / 'data' / '*.json'))):
    raw = json.load(open(path))
    was_dict = isinstance(raw, dict)
    regions = [raw] if was_dict else raw
    touched = False
    for reg in regions:
        for c in reg['courses']:
            keep, related = split(c)
            if not related:
                continue
            # 순서가 바뀌었으므로 구간 거리는 무효 — 다시 계산 전까지 비운다
            for p in keep:
                p.pop('nextM', None)
            for p in related:
                p.pop('nextM', None)
            c['poi'] = keep
            c['related'] = related
            changed.append((c['id'], [p['n'] for p in keep], [p['n'] for p in related]))
            touched = True
    if touched and not DRY:
        out = regions[0] if was_dict else regions
        pathlib.Path(path).write_text(json.dumps(out, ensure_ascii=False, indent=1))
        print(f'{pathlib.Path(path).name} 갱신')

# courses.json 도 동일하게 반영 (_img 등 기존 필드 보존)
if not DRY:
    cj = ROOT / 'courses.json'
    courses = json.load(open(cj))
    by_id = {cid: (keep, rel) for cid, keep, rel in
             [(c[0], c[1], c[2]) for c in changed]}
    for c in courses:
        if c['id'] not in by_id:
            continue
        keep_names, rel_names = by_id[c['id']]
        pois = c.get('poi', [])
        c['poi'] = [p for p in pois if p['n'] in keep_names]
        c['related'] = [p for p in pois if p['n'] in rel_names]
        for p in c['poi'] + c['related']:
            p.pop('nextM', None)
    cj.write_text(json.dumps(courses, ensure_ascii=False, indent=1))
    print('courses.json 갱신')

print(f'\n연관 추천을 분리한 코스: {len(changed)}개')
for cid, keep, rel in changed:
    print(f'  {cid}')
    print(f'     경유지: {", ".join(keep) or "(없음)"}')
    print(f'     연관  : {", ".join(rel)}')
