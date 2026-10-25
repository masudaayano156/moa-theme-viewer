# CodeQL 경고와 검토 결과

CodeQL의 작업 성공은 분석이 실행됐다는 뜻이며 **경고가 0개라는 뜻이 아닙니다.** 이 문서는 AI 지원 코드 검토이며 독립 기관의 보안 인증이 아닙니다. 경고를 일괄 숨기거나 무시 처리하지 않습니다.

## 0.9.2 현재 결과와 경고별 검토

소스 커밋 [`bb006ed7f30ed48e50f86606fc679e14112c6f8f`](https://github.com/masudaayano156/moa-theme-viewer/commit/bb006ed7f30ed48e50f86606fc679e14112c6f8f)의 [CodeQL 실행](https://github.com/masudaayano156/moa-theme-viewer/actions/runs/40191390758)은 성공했습니다. 2026-10-25 12:12 UTC에 조회한 **현재 열린 경고는 10개**이며, 검토 후에도 GitHub의 `open` 상태를 변경하지 않았습니다. [조회 결과와 경고별 근거 JSON](reports/v0.9.2-codeql.json)에 원본 경고 ID·심각도·위치·커밋과 AI 지원 검토 내용을 함께 기록했습니다. `CodeQL 경고 0개`로 안내하지 않습니다.

아래 위치는 위 커밋 기준입니다. 검토한 호출·입력 경계에서 추가 조치할 취약점을 확인하지 못했지만, 자동 분석 경고를 해소했다거나 앱 전체에 취약점이 없다는 결론은 아닙니다.

| 열린 경고 ID·규칙 | 현재 소스 위치 | 확인한 흐름·남는 경계 |
|---|---|---|
| [#12](https://github.com/masudaayano156/moa-theme-viewer/security/code-scanning/12) `java/user-controlled-bypass` | `ImageContextMenu.java:31`, `readDocument` | `isDcHttps`·`isAuthenticationPage`는 URL 분류입니다. 반환값은 이미지 메뉴와 읽기 화면 당겨서 새로고침의 허용 여부에 사용되며, 사이트 로그인·권한을 부여하지 않습니다. 사이트의 실제 인증·권한 검사는 서버에 의존합니다. |
| [#11](https://github.com/masudaayano156/moa-theme-viewer/security/code-scanning/11) `java/comparison-with-wider-type` | `MediaImageOps.java:50`, `sampleSize` | 현재 카운터와 반올림·곱셈은 `long`입니다. 너비·높이 1~100000, 양수 edge·pixels 가드에서 sample이 131072가 되면 두 축이 모두 1이므로 그 전에 종료합니다. 이 범위에서 정수 overflow 경로를 확인하지 못했습니다. 이미지 디코더·문서 제공자의 안전성까지 검증한 것은 아닙니다. |
| [#10](https://github.com/masudaayano156/moa-theme-viewer/security/code-scanning/10) `java/comparison-with-wider-type` | `MediaImageOps.java:50`, `sampleSize` | #11과 같은 현재 루프의 두 번째 경고입니다. 같은 입력 가드·`long` 계산·종료 상한을 확인했습니다. 두 경고 모두 열린 상태입니다. |
| [#8](https://github.com/masudaayano156/moa-theme-viewer/security/code-scanning/8) `java/android/websettings-javascript-enabled` | `BrowserController.java:403`, `configure` | 커뮤니티 기능을 위해 JavaScript·DOM 저장소를 사용합니다. 파일·content 접근과 파일 URL의 교차 출처 접근, 혼합 콘텐츠를 제한하고 SSL 오류를 거부합니다. 원본 사이트 스크립트는 같은 문서에서 실행되므로 별도 격리나 안전성 인증을 제공하지 않습니다. |
| [#7](https://github.com/masudaayano156/moa-theme-viewer/security/code-scanning/7) `java/android/intent-redirection` | `BrowserController.java:1455`, `openExternal` | 웹 탐색은 사용자 제스처가 있어야 진입합니다. `intent:` 경로는 component·selector·flags·extras·ClipData를 비우고 ACTION_VIEW·BROWSABLE을 설정하며 금지 scheme을 거부한 뒤 확인창에서 실행합니다. 원래 package 선택과 다른 custom scheme은 남을 수 있어 Android의 exported·권한 검사와 대상 앱의 URI 처리·사용자 선택을 신뢰합니다. |
| [#1](https://github.com/masudaayano156/moa-theme-viewer/security/code-scanning/1) `java/xss` | `BrowserController.java:1167`, `copyPendingText` | 앱에 포함된 `drafts.js` 뒤에 URL을 `JSONObject.quote`로 넣고, 현재 문서·계정인지 확인한 콜백에서 텍스트를 복사합니다. 원본 사이트는 같은 문서의 `MoaDrafts`를 바꿀 수 있다는 신뢰 경계가 남습니다. |
| [#2](https://github.com/masudaayano156/moa-theme-viewer/security/code-scanning/2) `java/xss` | `BrowserController.java:1811`, `guardNavigation` | 앱에 포함된 스크립트와 `JSONObject.quote`로 인용한 URL을 사용합니다. 문서·계정이 바뀐 응답은 거부합니다. 원본 사이트 스크립트가 작성 상태 응답에 영향을 줄 수 있으므로 이 기능을 서버 인증 경계로 보지 않습니다. |
| [#3](https://github.com/masudaayano156/moa-theme-viewer/security/code-scanning/3) `java/xss` | `BrowserController.java:1901`, `snapshotDraft` | URL을 인용하고 현재 문서·계정을 확인합니다. 반환값의 `fields`를 `JSONArray`로 파싱한 다음 `fields.toString()`으로 직렬화해 `MODE_PRIVATE` 초안 저장소에 기록합니다. 원본 사이트 응답·앱 프로세스·전용 저장소에 대한 신뢰는 남습니다. |
| [#4](https://github.com/masudaayano156/moa-theme-viewer/security/code-scanning/4) `java/xss` | `BrowserController.java:1932`, `offerDraftRestore`의 복원 가능 여부 확인 | 위 쓰기 경로가 만든 JSON 배열을 계정·문서 키로 읽어 JS 데이터로 전달합니다. `new JSONArray(stored)`는 구문 검사이며 임의 문자열을 정화하는 기능이 아닙니다. 읽은 원문을 사용하는 이 경로가 전용 저장소 변조까지 격리한다고 주장하지 않습니다. |
| [#5](https://github.com/masudaayano156/moa-theme-viewer/security/code-scanning/5) `java/xss` | `BrowserController.java:1941`, `offerDraftRestore`의 사용자 복원 동작 | 사용자가 복원을 선택하고 현재 문서·계정인지 확인한 뒤 같은 JSON 배열을 전달합니다. `drafts.js:212~235`는 허용된 선택자·편집 문맥을 확인하고 `innerText`·`value`로 텍스트를 복원합니다. `editorSnapshot`의 `innerHTML`은 비교용 서명에만 쓰며 복원하지 않습니다. 같은 출처의 스크립트·앱 프로세스·전용 저장소 변조에 대한 한계는 #4와 같습니다. |

## 과거 0.8.1 경고와 0.8.2 조치

0.8.1을 분석한 공개 Java 결과에는 12개 경고가 있었습니다. 아래 표와 합성 입력 검사 수치는 당시 검토 기록이며, 현재 열린 10개 경고의 수나 새 APK의 외부 검사 결과를 대신하지 않습니다.

| 경고 | 검토 결과 | 조치·한계 |
|---|---|---|
| `java/polynomial-redos` 1개 | 실제 문제. 이미지 메뉴의 현재 페이지 검사에서 긴 주소와 인코딩된 줄바꿈을 조합하면 정규식 비용이 커짐 | 0.8.2에서 주소 길이·제어 문자 제한과 선형 경로 분류로 수정. 개인자료 탈취나 백도어 발견을 뜻하지 않음 |
| `java/comparison-with-wider-type` 2개 | 이미지 축소 반복의 카운터. 현재 입력 가드에서는 최대 131072여서 정수 overflow가 재현되지 않음 | 방어 목적으로 0.8.2에서 카운터를 long으로 계산. 입력 크기 제한 유지 |
| `java/user-controlled-bypass` 1개 | `isAuthenticationPage`는 로그인 실행이나 권한 부여가 아닌 순수 URL 분류 함수. 조건에 따라 건너뛰는 것은 이미지 메뉴 노출 검사 | 이 경로에서 인증 우회를 확인하지 못함. 사이트 인증·로그인 자체의 안전성을 보증하지는 않음 |
| `java/xss` 5개 | URL을 JS 식에 넣는 곳은 `JSONObject.quote`를 사용. 초안은 앱이 파싱한 배열을 JSON으로 직렬화해 저장·복원 | 문자열을 코드에 그대로 붙여 넣는 경로와 구분해 검토. 원본 사이트와 같은 JS/DOM을 신뢰한다는 한계는 그대로 남으며, 저장소·프로세스 손상까지 격리하지 않음 |
| `java/android/intent-redirection` 1개 | 명시적인 외부 앱 열기 기능. component·selector·extras·ClipData·flags를 제거하고 ACTION_VIEW·BROWSABLE로 제한. 금지 scheme 거부·사용자 확인 사용 | Android의 exported/권한 검사에도 의존. 임의 입력을 내부 관리 기능으로 전달하는 범용 Intent 브리지로 쓰지 않음 |
| `java/android/websettings-javascript-enabled` 1개 | 커뮤니티의 로그인·글·댓글·테마 동작에 JavaScript 필요 | 기능상 남는 신뢰 경계. 파일·혼합 콘텐츠 접근 제한과 SSL 오류 거부를 유지해도 원본 사이트 스크립트 자체를 격리하지는 못함 |
| `java/android/missing-certificate-pinning` 1개 | 과거 푸시 경로의 경고. 당시 HTTPS·OS 기본 인증서 검증을 사용하며 별도 인증서 pinning은 구현하지 않음 | 해당 FCM 푸시 경로는 0.9.0부터 제거됐으며 0.9.2의 현재 열린 10개 목록에 이 경고는 없음. 과거 버전의 서버·시스템 CA 신뢰 경계를 소급 해소했다는 뜻은 아님 |

정규식 비용은 합성 주소를 사용한 JDK 17 검사로 확인했습니다. 원본 44.8KB 경로에서 약 0.9초가 관찰됐으며 휴대폰의 실제 지연 시간을 측정한 것은 아닙니다. 수정에서는 긴 입력을 일찍 거부하고 경로 조각을 한 번씩 확인합니다. 이미지 크기 카운터는 5,082개 경계 조합에서 현재 입력 가드 아래 종료·용량 조건을 확인했습니다.

현재 앱의 원본 웹사이트·OS·WebView·앱 전용 저장소에 대한 신뢰와 모든 제3자 코드의 안전성은 별개입니다. 검토한 경계를 넘어 ‘취약점이 전혀 없음’으로 확대 해석하지 마세요. [소스 보안 검토 범위](source-review.md), [0.9.2 CodeQL 결과](reports/v0.9.2-codeql.json), [공개 CodeQL 경고](https://github.com/masudaayano156/moa-theme-viewer/security/code-scanning)를 함께 확인하세요.
