#!/usr/bin/env python3
"""코스 상세 화면 목업 — 01-design-system.md 토큰 + 03-screens.md 실측 좌표"""
import json, pathlib, sys
from PIL import Image, ImageDraw, ImageFont

R=pathlib.Path('.'); IMG=R/'images'
BG1=(16,15,14); BG2=(22,21,20); SUNKEN=(31,30,28); SUNKEN2=(40,39,37)
INK=(244,243,239); MUTED=(139,139,131); FAINT=(92,92,85); ONINK=(16,15,14)
W=375; S=2   # 2x 렌더 후 축소

def F(sz,w='R'):
    fp='/System/Library/Fonts/AppleSDGothicNeo.ttc'
    idx={'R':0,'M':1,'SB':2,'B':3}.get(w,0)
    try: return ImageFont.truetype(fp, int(sz*S), index=idx)
    except Exception: return ImageFont.truetype(fp, int(sz*S))

def wrap(d,text,font,maxw):
    out=[];line=''
    for ch in text:
        t=line+ch
        if d.textlength(t,font=font)>maxw and line:
            out.append(line); line=ch
        else: line=t
    if line: out.append(line)
    return out

def rr(d,box,r,fill):
    d.rounded_rectangle([b*S for b in box], radius=r*S, fill=fill)

def screen(c):
    H=1500
    im=Image.new('RGB',(W*S,H*S),BG1); d=ImageDraw.Draw(im)
    # 히어로 375x300
    hero=Image.open(IMG/f"{c['id']}_hero.png").resize((W*S,300*S),Image.LANCZOS)
    im.paste(hero,(0,0))
    # 시트 y=276 radius 22
    rr(d,[0,276,W,H],22,BG1)
    y=276
    # 상단 바 아이콘 자리
    d.ellipse([14*S,52*S,42*S,80*S], fill=(0,0,0)); d.text((23*S,58*S),'‹',fill=INK,font=F(18,'B'))
    for i,ic in enumerate(['↗','♡']):
        x=W-46-i*36
        d.ellipse([x*S,52*S,(x+28)*S,80*S], fill=(0,0,0))
        d.text(((x+8)*S,59*S),ic,fill=INK,font=F(13))
    y=306
    # 라벨
    d.text((20*S,y*S), f"러닝 코스 · {c['city']}", fill=(214,86,64), font=F(12.5,'SB')); y+=24
    # 제목 24px/700
    for ln in wrap(d,c['headline'],F(24,'B'),335*S):
        d.text((20*S,y*S),ln,fill=INK,font=F(24,'B')); y+=32
    y+=8
    # 리드문
    for ln in wrap(d,c['subhead'],F(13.5),335*S):
        d.text((20*S,y*S),ln,fill=MUTED,font=F(13.5)); y+=23
    y+=14
    # 칩
    x=20
    for t in [f"{c['km']}km", f"{c['min']}분", c['lv'], c['mood']]:
        wpx=d.textlength(t,font=F(11,'SB'))/S+22
        rr(d,[x,y,x+wpx,y+26],13,SUNKEN)
        d.text(((x+11)*S,(y+7)*S),t,fill=INK,font=F(11,'SB')); x+=wpx+7
    y+=42
    # 3분할 수치 카드 335x64 radius14
    rr(d,[20,y,355,y+64],14,SUNKEN)
    for i,(k,v) in enumerate([('거리',f"{c['km']}km"),('시간',f"{c['min']}분"),('이야기',f"{len(c.get('poi',[]))}곳")]):
        cx=20+111*i+55
        d.text((cx*S,(y+14)*S),v,fill=INK,font=F(17,'SB'),anchor='ma')
        d.text((cx*S,(y+38)*S),k,fill=FAINT,font=F(11),anchor='ma')
        if i<2: d.line([((20+111*(i+1))*S,(y+16)*S),((20+111*(i+1))*S,(y+48)*S)],fill=SUNKEN2,width=S)
    y+=80
    # CTA 335x52 radius14
    rr(d,[20,y,355,y+52],14,INK)
    d.text((187*S,(y+18)*S),'코스 정보만 볼 수 있어요',fill=ONINK,font=F(15.5,'SB'),anchor='ma')
    y+=68
    # 어떤 길인지
    d.text((20*S,y*S),'어떤 길인지',fill=INK,font=F(19,'B')); y+=32
    for para in c['body'][:2]:
        for ln in wrap(d,para,F(13.5),335*S):
            d.text((20*S,y*S),ln,fill=(214,213,208),font=F(13.5)); y+=23
        y+=12
    y+=6
    # 가기 전에 알아두면 좋아요
    d.text((20*S,y*S),'가기 전에 알아두면 좋아요',fill=INK,font=F(19,'B')); y+=32
    for op in c['ops'][:2]:
        d.ellipse([(22)*S,(y+7)*S,(26)*S,(y+11)*S],fill=(214,86,64))
        for j,ln in enumerate(wrap(d,op,F(13.5),320*S)):
            d.text((34*S,y*S),ln,fill=(214,213,208),font=F(13.5)); y+=23
        y+=8
    y+=8
    # 아직 확인 중인 것
    if c.get('unsure'):
        rr(d,[20,y,355,y+38+len(c['unsure'])*20],14,BG2)
        d.text((34*S,(y+14)*S),'아직 확인 중인 것',fill=MUTED,font=F(12.5,'SB')); yy=y+38
        for u in c['unsure']:
            d.text((34*S,yy*S),f"· {u}",fill=FAINT,font=F(12)); yy+=20
        y=yy+22
    # 지나는 곳
    d.text((20*S,y*S),'지나는 곳',fill=INK,font=F(19,'B')); y+=30
    for i,p in enumerate(c.get('poi',[])):
        rr(d,[20,y,355,y+68],14,SUNKEN)
        tp=IMG/f"{c['id']}-poi{i+1}_thumb.png"
        if tp.exists():
            t=Image.open(tp).convert('RGBA').resize((46*S,46*S),Image.LANCZOS)
            im.paste(t,(33*S,(y+11)*S),t)
        else:
            rr(d,[33,y+11,79,y+57],10,SUNKEN2)
        d.text((93*S,(y+16)*S),p['n'],fill=INK,font=F(14.5,'SB'))
        for ln in wrap(d,p['d'],F(12),240*S)[:1]:
            d.text((93*S,(y+38)*S),ln,fill=FAINT,font=F(12))
        y+=76
    y+=10
    d.text((20*S,y*S),'이미지는 관광공사 사진의 색을 참고해 직접 그린 것입니다',fill=FAINT,font=F(11))
    y+=34
    return im.crop((0,0,W*S,min(H,y)*S)).resize((W,min(H,y)),Image.LANCZOS)

if __name__=='__main__':
    cs={c['id']:c for c in json.loads((R/'courses.json').read_text())}
    ids=sys.argv[1:] or ['seoul-banpo-10k','daejeon-gyejoksan','busan-gwangalli-night']
    shots=[screen(cs[i]) for i in ids]
    HH=max(s.height for s in shots); gap=22
    sheet=Image.new('RGB',(len(shots)*(W+gap)+gap, HH+gap*2),(8,8,9))
    for i,s in enumerate(shots): sheet.paste(s,(gap+i*(W+gap),gap))
    sheet.save('/tmp/kto/mock.png'); print('/tmp/kto/mock.png', sheet.size)
