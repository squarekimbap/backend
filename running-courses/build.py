#!/usr/bin/env python3
"""data/*.json → 이미지 생성 + 지역별 마크다운 + 앱용 courses.json
단일 원본: data/<region>.json
"""
import json, pathlib, sys, re
sys.path.insert(0, '/tmp/kto')
import art

ROOT = pathlib.Path(__file__).parent
DATA = ROOT/'data'; IMG = ROOT/'images'; IMG.mkdir(exist_ok=True)

# POI 이름으로 씬 추정 (05-data.md 도형 어휘)
KW = [
    (('해변','해수욕장','바다','포구','항','섬','앞바다'), 'coast'),
    (('호수','호','저수지','습지','연못','댐'), 'lake'),
    (('천','강','하천','수변','둔치','보행교','다리','대교'), 'river'),
    (('산','봉','고개','성곽','산성','능선','전망대','타워'), 'hill'),
    (('숲','수목원','임도','둘레길','생태','공원길','가로수','나무'), 'trail'),
]
def guess_scene(name, fallback):
    for keys, sc in KW:
        if any(k in name for k in keys): return sc
    return fallback

def slugify(s):
    return re.sub(r'[^a-z0-9]+','-', s.lower()).strip('-')

def main(only=None):
    regions=[]
    for f in sorted(DATA.glob('*.json')):
        if only and f.stem!=only: continue
        j=json.loads(f.read_text())
        regions.extend(j if isinstance(j,list) else [j])
    made=0; skipped=[]
    for reg in regions:
        for c in reg['courses']:
            # 코스 대표 이미지
            if c.get('photo'):
                try:
                    art.build(c['id'], c['scene'], c['photo'], c.get('time'))
                    made+=1
                except Exception as e:
                    skipped.append((c['id'], str(e)[:60]))
            # 경유지 썸네일
            for i,p in enumerate(c.get('poi',[])):
                pid=f"{c['id']}-poi{i+1}"
                sc=guess_scene(p['n'], c['scene'])
                nightish = c.get('time') if c.get('time')=='night' else None
                try:
                    # 사진이 있으면 그 색을, 없으면 씬 기본 팔레트를 쓴다. 어느 쪽이든 썸네일은 아이콘 렌더.
                    art.build_thumb(pid, sc, p.get('photo'), nightish)
                    p['_img']=pid; made+=1
                except Exception as e:
                    skipped.append((pid, str(e)[:60]))
    return regions, made, skipped

def md(reg):
    L=[f"# {reg['city']} 러닝 코스", ""]
    for c in reg['courses']:
        L += [ "---", "",
               f"![{c['n']}](images/{c['id']}_hero.png)", "",
               f"`러닝 코스 · {reg['city']}`", "",
               f"# {c['headline']}", "",
               c['subhead'], "",
               f"`{c['km']}km` `{c['min']}분` `{c['lv']}` `{c['mood']}`", "",
               f"[**지도에서 출발점 열기**  →](https://map.kakao.com/?q={c['n'].split()[0]})", "",
               "### 어떤 길인지", "" ]
        L += [p+"\n" for p in c['body']]
        L += ["### 더 알아두면 좋은 것", ""] + [p+"\n" for p in c['deep']]
        L += ["### 가기 전에 알아두면 좋아요", ""] + [f"- {p}" for p in c['ops']] + [""]
        if c.get('unsure'):
            L += ["### 아직 확인 중인 것", ""] + [f"- {p}" for p in c['unsure']] + [""]
        L += ["### 지나는 곳", "", "| | 장소 |", "|---|---|"]
        for p in c.get('poi',[]):
            img = f'<img src="images/{p["_img"]}_thumb.png" width="52">' if p.get('_img') else '—'
            L.append(f"| {img} | **{p['n']}** — {p['d']} |")
        L += ["", f"<sub>이미지는 한국관광공사 사진의 색을 참고해 직접 그린 것입니다 · 원본 {c.get('photoTitle','')}</sub>", ""]
    return "\n".join(L)

if __name__=='__main__':
    only = sys.argv[1] if len(sys.argv)>1 else None
    regions, made, skipped = main(only)
    for reg in regions:
        out = ROOT/f"{reg['cityId']}.md"
        out.write_text(md(reg))
        print(f"  {out.name}  코스 {len(reg['courses'])}개")
    allc=[dict(c, city=r['city'], cityId=r['cityId'], region=r['region']) for r in regions for c in r['courses']]
    (ROOT/'courses.json').write_text(json.dumps(allc, ensure_ascii=False, indent=1))
    print(f"\n이미지 {made}장 생성 · courses.json 코스 {len(allc)}개")
    if skipped:
        print("실패:", *[f"\n  {a}: {b}" for a,b in skipped])
