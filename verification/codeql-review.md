# CodeQL 경고와 검토 결과

CodeQL의 작업 성공은 분석이 실행됐다는 뜻이며 **경고가 0개라는 뜻이 아닙니다.** 0.8.1을 분석한 공개 Java 결과에는 12개 경고가 있었습니다. 경고를 일괄 숨기거나 무시 처리하지 않습니다. 이 문서는 AI 지원 코드 검토이며 독립 기관의 보안 인증이 아닙니다.

| 경고 | 검토 결과 | 조치·한계 |
|---|---|---|
| `java/polynomial-redos` 1개 | 실제 문제. 이미지 메뉴의 현재 페이지 검사에서 긴 주소와 인코딩된 줄바꿈을 조합하면 정규식 비용이 커짐 | 0.8.2에서 주소 길이·제어 문자 제한과 선형 경로 분류로 수정. 개인자료 탈취나 백도어 발견을 뜻하지 않음 |
| `java/comparison-with-wider-type` 2개 | 이미지 축소 반복의 카운터. 현재 입력 가드에서는 최대 131072여서 정수 overflow가 재현되지 않음 | 방어 목적으로 0.8.2에서 카운터를 long으로 계산. 입력 크기 제한 유지 |
| `java/user-controlled-bypass` 1개 | `isAuthenticationPage`는 로그인 실행이나 권한 부여가 아닌 순수 URL 분류 함수. 조건에 따라 건너뛰는 것은 이미지 메뉴 노출 검사 | 이 경로에서 인증 우회를 확인하지 못함. 사이트 인증·로그인 자체의 안전성을 보증하지는 않음 |
| `java/xss` 5개 | URL을 JS 식에 넣는 곳은 `JSONObject.quote`를 사용. 초안은 앱이 파싱한 배열을 JSON으로 직렬화해 저장·복원 | 문자열을 코드에 그대로 붙여 넣는 경로와 구분해 검토. 원본 사이트와 같은 JS/DOM을 신뢰한다는 한계는 그대로 남으며, 저장소·프로세스 손상까지 격리하지 않음 |
| `java/android/intent-redirection` 1개 | 명시적인 외부 앱 열기 기능. component·selector·extras·ClipData·flags를 제거하고 ACTION_VIEW·BROWSABLE로 제한. 금지 scheme 거부·사용자 확인 사용 | Android의 exported/권한 검사에도 의존. 임의 입력을 내부 관리 기능으로 전달하는 범용 Intent 브리지로 쓰지 않음 |
| `java/android/websettings-javascript-enabled` 1개 | 커뮤니티의 로그인·글·댓글·테마 동작에 JavaScript 필요 | 기능상 남는 신뢰 경계. 파일·혼합 콘텐츠 접근 제한과 SSL 오류 거부를 유지해도 원본 사이트 스크립트 자체를 격리하지는 못함 |
| `java/android/missing-certificate-pinning` 1개 | HTTPS·OS 기본 인증서 검증을 사용하며 별도 인증서 pinning은 구현하지 않음 | 정상적인 인증서 갱신·사용자가 지정한 서버를 지원하는 설계. 시스템 CA·서버 운영 신뢰가 필요하며 인증서 고정이 있다고 주장하지 않음 |

정규식 비용은 합성 주소를 사용한 JDK 17 검사로 확인했습니다. 원본 44.8KB 경로에서 약 0.9초가 관찰됐으며 휴대폰의 실제 지연 시간을 측정한 것은 아닙니다. 수정에서는 긴 입력을 일찍 거부하고 경로 조각을 한 번씩 확인합니다. 이미지 크기 카운터는 5,082개 경계 조합에서 현재 입력 가드 아래 종료·용량 조건을 확인했습니다.

앱의 원본 웹사이트·FCM 발송자·OS·WebView에 대한 신뢰와 모든 제3자 코드의 안전성은 별개입니다. 검토한 경계를 넘어 ‘취약점이 전혀 없음’으로 확대 해석하지 마세요. [소스 보안 검토 범위](source-review.md), [공개 CodeQL 경고](https://github.com/masudaayano156/moa-theme-viewer/security/code-scanning)를 함께 확인하세요.
