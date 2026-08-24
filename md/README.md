# 어디 뛰지 (Dali)

여행지에서 관광지를 지나는 러닝 코스를 만들고, 도착하면 그곳의 이야기가 열리는 iOS 앱.

## 구성

```
docs/                       개발 문서
├─ 00-overview.md           문서 안내
├─ 01-design-system.md      색·서체·크기 토큰
├─ 02-navigation.md         화면 스택과 전환
├─ 03-screens.md            화면 22개 명세 (스크린샷 포함)
├─ 04-animation.md          애니메이션 타이밍
├─ 05-data.md               데이터 모델·API·에셋
├─ 06-copy.md               화면 문구 매핑
├─ 07-open-questions.md     구현 전 결정할 것
├─ CLAUDE.md                AI 개발 가이드
└─ screens/                 화면 캡처 22장

prototype/
├─ eodi-run-prototype.html        동작하는 프로토타입 (다크)
└─ eodi-run-prototype-light.html  라이트 변형
```

## 시작하기

1. `docs/07-open-questions.md`의 앞 세 항목을 먼저 결정한다 (서버·데이터 위치·경로 계산 시점)
2. `prototype/eodi-run-prototype.html`을 브라우저에서 열어 전체 흐름을 확인한다
3. `docs/03-screens.md`를 화면별로 참고하며 SwiftUI로 옮긴다

## 기준

모든 수치는 프로토타입을 브라우저에서 렌더링해 실측한 값이다.
문서와 프로토타입이 어긋나면 **프로토타입을 따른다**.
