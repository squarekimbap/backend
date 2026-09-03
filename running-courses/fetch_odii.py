#!/usr/bin/env python3
"""한국관광공사 오디(Odii) 이야기 전체를 언어별로 받아 캐시한다.

    TOUR_API_KEY=... python3 running-courses/fetch_odii.py

⚠️ 오디오 주소는 `storyBasedList` 에만 들어 있다. 위치 기반인 `storyLocationBasedList`
는 audioUrl 이 항상 빈 문자열이라 여기서는 쓰지 않는다 — 전체를 받아 좌표로 직접 고른다.

언어 코드는 오디 규격 그대로 ko / en / jp 다(ja 는 0건). 같은 이야기는 언어가 달라도
`stid` 가 같아서 그걸로 묶는다(stlid 는 언어별로 다르다).

산출물은 .cache/ 에 두고 깃에 넣지 않는다. 커밋되는 건 이걸로 만든 courses.json 뿐이다.
"""

import json
import os
import subprocess
import sys
import urllib.parse
from pathlib import Path

BASE = "https://apis.data.go.kr/B551011/Odii/storyBasedList"
LANGS = ("ko", "en", "jp")
PAGE = 1000
MAX_PAGES = 20
CACHE = Path(__file__).resolve().parent / ".cache"


def fetch(key, lang):
    items, page = [], 1
    while page <= MAX_PAGES:
        params = {"serviceKey": key, "MobileOS": "ETC", "MobileApp": "tour-api",
                  "_type": "json", "langCode": lang,
                  "numOfRows": str(PAGE), "pageNo": str(page)}
        # 이 환경의 python urllib 은 인증서 검증에 실패해서 curl 로 부른다
        out = subprocess.run(["curl", "-s", "--max-time", "60",
                              BASE + "?" + urllib.parse.urlencode(params)],
                             capture_output=True, text=True).stdout
        body = json.loads(out)["response"]["body"]
        got = body.get("items")
        got = (got.get("item", []) if isinstance(got, dict) else []) if got else []
        if not got:
            break
        items += got
        if len(items) >= int(body["totalCount"]):
            break
        page += 1
    return items


def main():
    key = os.environ.get("TOUR_API_KEY")
    if not key:
        sys.exit("TOUR_API_KEY 가 필요하다 (공공데이터포털 디코딩 키)")
    CACHE.mkdir(exist_ok=True)
    for lang in LANGS:
        items = fetch(key, lang)
        withUrl = sum(1 for i in items if i.get("audioUrl"))
        (CACHE / f"odii_{lang}.json").write_text(
            json.dumps(items, ensure_ascii=False), encoding="utf-8")
        print(f"{lang}: {len(items)}건 (오디오 있는 것 {withUrl}건)")
    print(f"→ {CACHE}")


if __name__ == "__main__":
    main()
