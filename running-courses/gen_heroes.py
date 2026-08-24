#!/usr/bin/env python3
"""Pollinations(sana)로 코스 히어로 생성 — 밝은 스크린프린트 톤
고유 형태 랜드마크(N서울타워 등)는 모델이 못 그리므로 지형 묘사로 대체한다.
"""
import json, pathlib, subprocess, urllib.parse, sys, time

R = pathlib.Path(__file__).parent
IMG = R/'images'; IMG.mkdir(exist_ok=True)
LOG = R/'gen_heroes.log'

SKELETON = ("Screenprint poster illustration, bright pastel palette, 4 flat colors on cream background, "
            "bold simplified graphic shapes, sunny daylight, airy and light, editorial travel poster. "
            "Illustration only, no photograph, no dark tones, no text, no letters, no people, no watermark. Subject: ")

# 씬별 기본 묘사 (개별 지정이 없을 때)
FALLBACK = {
 'lake':  "a calm round lake ringed by a walking path, low green hills behind, trees at the water edge",
 'river': "a wide city river with grassy banks and a walking path along the water, low skyline in the distance",
 'coast': "a long sandy beach curving along turquoise sea, low headland at one end",
 'city':  "a green city park with a wide curving path, low buildings behind the trees",
 'trail': "a forest trail curving between tall trees, dappled sunlight on the path",
 'hill':  "a forested mountain with a winding road climbing the slope, valley below",
}

SUBJECTS = {
 # 서울
 'seoul-banpo-10k':"a wide river at dusk with a long low bridge, fountain jets arcing from the bridge deck into the water, city skyline behind",
 'seoul-yeouido-5k':"a straight riverside path lined with cherry blossom trees, wide river on one side, tall buildings across the water",
 'seoul-namsan-loop':"a forested mountain rising above a dense city, a narrow road switchbacking up through the trees",
 'seoul-olympic-loop':"a big green park with a lone broad tree on a grassy mound, wide curving path, earthen rampart behind",
 'seoul-ttukseom-7k':"a red running track path along a wide river, grassy bank, distant city towers",
 'seoul-gyeongui-morning':"a long straight park built on an old railway line, rails still in the grass, trees on both sides, low brick buildings",
 # 인천·경기
 'incheon-songdo-loop':"a canal running between tall glass towers, wide waterside walkway, boats on the water",
 'incheon-arabaetgil-lsd':"a perfectly straight wide canal stretching to the horizon, flat paths on both banks, low hills far away",
 'goyang-ilsan-lake':"a large oval lake in a city park, wide walking loop around it, willow trees, apartment towers behind",
 'suwon-gwanggyo-lake':"two lakes joined by a boardwalk, wooded shore, tall apartment towers on the far side",
 'suwon-hwaseong-wall':"an old stone fortress wall with a tiled gate pavilion, the wall running over a green hill",
 'seongnam-tancheon':"a long straight stream through a city, grass banks, separated bike and walking paths, bridges crossing",
 # 강원
 'chuncheon-uiamho':"a broad calm lake ringed by round green mountains, a tree lined path along the shore",
 'chuncheon-gongjicheon':"a small stream with a waterside park, willow trees, benches, low bridge",
 'gangneung-gyeongpo':"a round lake with a walking loop, pine trees and cherry blossoms on the shore, sea beyond",
 'gangneung-anmok-sunrise':"a wide sandy beach at sunrise, calm sea, low cafes along the shore",
 'sokcho-yeongnangho':"a lake with a walking loop, jagged rocky mountain range on the horizon",
 # 충청
 'daejeon-gapcheon':"a broad shallow river through a city, wide grassy floodplain paths, low bridges",
 'daejeon-gyejoksan':"a red clay forest trail curving between tall pine trees, dappled sunlight",
 'sejong-eung-bridge':"a circular pedestrian bridge forming a ring over a wide river, city towers behind",
 'cheongju-musimcheon':"a city stream lined with cherry blossom trees in full bloom, paths on both banks",
 'cheonan-gokgyocheon':"a stream lined with tall golden ginkgo trees forming a tunnel over the path, autumn",
 # 전라
 'gwangju-gwangjucheon':"a city stream with wide separated paths, low trees, apartment buildings behind",
 'jeonju-jeonjucheon-dawn':"a shallow stream beside a village of traditional tiled roof houses, stepping stones, early morning",
 'jeonju-deokjin':"a lotus covered pond in a park, wooden deck path over the water, pavilion",
 'mokpo-pyeonghwa-20k':"a long straight seaside promenade beside calm water, low breakwater, distant hills",
 'yeosu-expo-9k':"a harbour promenade with a small wooded island and lighthouse offshore, calm southern sea",
 'suncheon-dongcheon':"a stream with reed beds and a wide green garden beyond, wooden footbridge",
 # 경상
 'daegu-sincheon':"a stream cutting through a dense city, flower beds along the bank paths, bridges overhead",
 'daegu-jinbatgol-uphill':"a steep forest road climbing a mountain in tight switchbacks, dense trees",
 'ulsan-taehwagang':"a dense green bamboo grove beside a wide calm river, a path running through the bamboo",
 'pohang-yeongildae':"a beach with a wooden deck path, a traditional pavilion standing out on the water",
 'changwon-yongji':"a small round lake in a city park, red rubber track path circling it, towers behind",
 'changwon-jinhae-dreamroad':"a ridge trail through pine forest, sea and islands visible far below",
 'gyeongju-bomunho':"a large lake ringed by a path, low wooded hills, cherry blossoms along the shore",
 # 부산·제주
 'busan-haeundae':"a long crescent beach with a cluster of tall towers behind it, turquoise sea, wooded headland at one end",
 'busan-gwangalli-night':"a long two deck suspension bridge over turquoise sea, two tall H shaped towers, main cables draping between them, city on the far shore",
 'busan-oncheoncheon':"a city stream lined with cherry blossoms and yellow canola flowers, paths on both banks",
 'busan-samnak-lsd':"a long straight avenue of tall metasequoia trees beside a wide river",
 'busan-songdo-cloud':"a curving walkway built out over the sea along a rocky cliff coast",
 'jeju-yongdam-coast':"a black volcanic rock coastline with a seaside path, turquoise water, low stone walls",
 'jeju-saryeoni':"a straight forest path between tall cedar trees, wooden boardwalk, soft light",
}

def gen(cid, subject, retries=3):
    out = IMG/f'{cid}_hero.png'
    p = urllib.parse.quote(SKELETON + subject)
    url = f"https://image.pollinations.ai/prompt/{p}?width=1024&height=819&nologo=true&model=sana&seed=3"
    for i in range(retries):
        subprocess.run(['curl','-s','-m','170','-o',str(out),url], timeout=200)
        if out.exists() and out.stat().st_size > 18000:
            return out.stat().st_size
        time.sleep(15)
    return 0

if __name__=='__main__':
    cs = json.loads((R/'courses.json').read_text())
    only = sys.argv[1:] or None
    log = open(LOG,'a')
    ok=fail=0
    for i,c in enumerate(cs,1):
        cid=c['id']
        if only and cid not in only: continue
        subj = SUBJECTS.get(cid) or FALLBACK[c['scene']]
        sz = gen(cid, subj)
        line=f"[{i:2d}/{len(cs)}] {cid:34s} {'OK  '+str(sz)+'B' if sz else 'FAIL'}"
        print(line, flush=True); log.write(line+'\n'); log.flush()
        ok += 1 if sz else 0; fail += 0 if sz else 1
        time.sleep(4)
    line=f"완료 — 성공 {ok} / 실패 {fail}"
    print(line); log.write(line+'\n'); log.close()
