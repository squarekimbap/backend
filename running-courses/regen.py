#!/usr/bin/env python3
import subprocess, urllib.parse, pathlib, time, sys
IMG = pathlib.Path('images')
# sana는 네거티브 프롬프트를 지원하지 않는다. 부정어는 오히려 그 대상을 소환하므로
# 42장이 성공했던 원래 스켈레톤을 그대로 쓰고, 피하고 싶은 건 긍정 묘사로 덮는다.
SK = ("Screenprint poster illustration, bright pastel palette, 4 flat colors on cream background, "
      "bold simplified graphic shapes, sunny daylight, airy and light, editorial travel poster. "
      "Illustration only, no photograph, no dark tones, no text, no letters, no people, no watermark. Subject: ")

FIX = {'changwon-yongji': 'a small oval city lake in summer seen from its grassy shore, a reddish running track path circling the water, leafy green trees, apartment blocks far behind',
 'sokcho-yeongnangho': 'a wide calm green lake in summer, a walking path along the near shore, rounded green forested hills behind, pine trees at the water edge',
 'seoul-namsan-loop': 'a green forested hill in summer covered in leafy trees, a narrow paved road curving around the hillside, dense city rooftops at the base',
 'suwon-hwaseong-wall': 'a long low grey stone rampart running along the crest of a green grassy hill in summer, small fitted stone blocks, a flat walkway on top, leafy trees beside it',
 'yeosu-expo-9k': 'a calm green harbour bay in summer, a small round wooded island near the shore, a wide waterfront promenade in the foreground, leafy hills across the water',
 'pohang-yeongildae': 'a wide sandy summer beach, a long wooden boardwalk running along the sand in the foreground, calm turquoise sea, low breakwater far out',
 'daegu-jinbatgol-uphill': 'a narrow paved road climbing through dense leafy green summer forest in tight hairpin bends, layered green hillsides',
 'ulsan-taehwagang': 'a dense grove of tall green bamboo stalks filling the frame in summer, a straight earthen path running through the bamboo, warm sunlight between the stalks',
 'incheon-arabaetgil-lsd': 'a very wide straight green canal running to the horizon in summer, concrete embankments, a paved path along the near bank in the foreground, flat open farmland',
 'gangneung-gyeongpo': 'a large round green lake in summer, a wide walking path along the near shore in the foreground, leafy trees on the far bank, low green hills'}

def gen(cid, subj, seed=23):
    out = IMG/f'{cid}_hero.png'
    url = ("https://image.pollinations.ai/prompt/" + urllib.parse.quote(SK+subj) +
           f"?width=1024&height=819&nologo=true&model=sana&seed={seed}")
    for _ in range(3):
        subprocess.run(['curl','-s','-m','170','-o',str(out),url], timeout=200)
        if out.exists() and out.stat().st_size > 18000: return out.stat().st_size
        time.sleep(15)
    return 0

if __name__=='__main__':
    for i,(cid,subj) in enumerate(FIX.items(),1):
        sz=gen(cid,subj)
        print(f"[{i:2d}/{len(FIX)}] {cid:30s} {'OK '+str(sz)+'B' if sz else 'FAIL'}", flush=True)
        time.sleep(4)
