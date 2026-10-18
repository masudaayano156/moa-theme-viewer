# 버전별 검증 자료

이 페이지는 검사를 종류별로 구분합니다. **‘모든 악성코드·백도어가 없다는 인증’으로 해석하지 마세요.** 검사 대상 APK의 버전과 SHA-256이 본인이 받은 파일에 일치해야 합니다.

## 바로 확인

- [파일 해시·서명 확인 순서](../VERIFY.md)
- [휴대폰 권한과 이유](../PERMISSIONS.md)
- [자료 저장·전송 범위](../PRIVACY.md)
- [발견 사항과 수정 기록](source-review.md)
- [공개 자동 검사 실행 기록](https://github.com/masudaayano156/moa-theme-viewer/actions)

## 최신 0.9.0 검사 결과

백그라운드 푸시·Firebase SDK·기기 등록·글별 추적과 알림 권한을 제거했습니다. 앱 이름·테마 이름·색상·아이콘은 그대로입니다. 운영자 요청으로 구버전 릴리스와 APK를 삭제하며, 아래 과거 보고서는 기록으로만 남깁니다. 구버전 APK 다운로드는 제공하지 않습니다.

| 검사 | 실제 결과와 범위 |
|---|---|
| APK | 전용 인증서 v2/v3 서명 유효·디버깅 꺼짐, 약 1.15MB |
| 실제 권한 | 인터넷, Android 8~9 이미지 저장 쓰기·암묵적 읽기 3개. 알림·재부팅·Wake lock·FCM 수신 권한 없음 |
| 공개 소스 재빌드 | VCS·DEX·Manifest·리소스·자산을 포함해 **109/109 항목 내용 동일** |
| 파일 전체 재현 | 서명 블록만 제거해도 ZIP 포장 차이가 남아 **완전한 비트 단위 일치 아님** |
| 의존성 | 공개 Maven 15개, OSV 조회 완료·알려진 취약점 일치 없음. Firebase·Google Play services 런타임 제거 |
| 개발 검사 | Android JVM 513개·웹 224개 통과, 실패 0·lint 오류 0. 테스트 코드와 개인 자료는 공개하지 않음 |
| 팝업 | 7개 테마·밝음/어두움·강조색, 한 화면의 90초 주기, 비활성화·중복 방지 검사. 실제 휴대폰 계정의 새 알림 수신은 미검증 |
| 외부 다중 백신 | 새 APK 미제출. 0.8.1·0.8.2 VirusTotal 결과를 이 버전에 적용하지 않음 |
| GitHub 자동 검사 | [워크플로 실제 실행 상태](https://github.com/masudaayano156/moa-theme-viewer/actions) 확인. 완료 기록은 별도로 갱신 |

- [0.9.0 APK 다운로드](https://github.com/masudaayano156/moa-theme-viewer/releases/download/v0.9.0/moa-0.9.0.apk) · [릴리스와 SHA-256](https://github.com/masudaayano156/moa-theme-viewer/releases/tag/v0.9.0)
- [버전·해시·권한](releases/v0.9.0.json) · [APK 검사](reports/v0.9.0-apk.json) · [재빌드 비교](reports/v0.9.0-rebuild.json)
- [검토·개발 검사 범위](reports/v0.9.0-review.json) · [의존성 목록](reports/v0.9.0-dependencies.cdx.json) · [OSV 결과](reports/v0.9.0-dependencies-osv.json)

0.9.0 재현에는 Firebase 설정이나 APK 클라이언트 값 추출이 필요하지 않습니다. 키·계정 정보 없이 빌드합니다. 기존 서버의 조회·발송 예약과 DB 연결을 종료했으며, 서버 기록은 명시적 선택에 따라 보존합니다.

## 이전 0.8.2 검사 결과

**완료된 실제 실행:** [소스·lint·CodeQL·OSV](https://github.com/masudaayano156/moa-theme-viewer/actions/runs/39096346205) · [APK 재빌드·서명·검증 증명 확인](https://github.com/masudaayano156/moa-theme-viewer/actions/runs/39096883369) · [정리된 CI 기록](reports/v0.8.2-ci.json)

| 검사 | 결과와 한계 |
|---|---|
| APK 해시·서명·권한 | 전용 인증서와 v2/v3 서명 유효, 디버깅 꺼짐. 권한 9개(구형 OS의 암묵적 읽기 1개 포함). 카메라·마이크 없음 |
| 소스 내용 재빌드 | DEX·Manifest·리소스·자산·내장 Git 정보 등 **189/189 항목 바이트 동일** |
| 파일 전체 재현 | **완전 일치 아님.** 서명 블록만 제거해도 ZIP 헤더·정렬 등의 포장 바이트는 다름 |
| VirusTotal | **68개 엔진 미탐지, 7개 형식 미지원**, 2026-10-18 13:19:21 KST 기준. 무악성 인증이 아님 |
| 의존성 | 68개 해석 항목의 라이브러리·버전·해시 공개. 중복을 합친 Maven 66개의 OSV 알려진 취약점 일치 없음 |
| 개발 검증 | Android JVM 755회 통과·실패/오류/스킵 0, lint 오류 0. 별도 검증 도구 합성 입력 45회 통과. 전문 보안 감사가 아님 |
| 공개 자동 검사 | [소스 검사·lint·CodeQL·OSV 실행](https://github.com/masudaayano156/moa-theme-viewer/actions/workflows/security.yml), [APK 검증 실행](https://github.com/masudaayano156/moa-theme-viewer/actions/workflows/verify-release.yml)의 실제 상태 확인 |

- 해당 구버전 릴리스·APK 배포는 종료했습니다. 아래 검사 자료는 당시 기록입니다.
- [VirusTotal 원본 보고서](https://www.virustotal.com/gui/file/862c0b7f81ab9a75d5d96d74e1b0bec771a0ae22aac6c24b29fb7ed29db5e9e9) · [검사 시점 요약](reports/v0.8.2-virustotal.json)
- [버전 정보](releases/v0.8.2.json) · [APK 검사](reports/v0.8.2-apk.json) · [재빌드 상세 비교](reports/v0.8.2-rebuild.json)
- [검토·개발 검증 범위](reports/v0.8.2-review.json) · [실제 비밀값 제외 대조 범위](reports/v0.8.2-secret-exclusion.json)

빌드 소스 커밋은 `c4161725a690310665c9a2505e746ccf39874c43`이며 APK 내장 revision도 같습니다. 이후 문서·검증 워크플로 수정과 구분합니다. 로컬의 깨끗한 소스에서 만든 비서명 APK 두 개는 파일 전체가 같았지만, 배포 서명 과정에서는 ZIP 헤더·정렬도 바뀝니다. 따라서 배포 파일의 완전한 비트 단위 재현 성공으로 표시하지 않습니다.

공개 custom attestation은 배포 파일의 해시·서명·권한과 **모든 ZIP 항목 내용의 소스 대조 결과**를 명시합니다. `isBitExactReproduction: false`, `rawApkBytesExcludingSigningBlock: different`이면 포장 차이가 남아 있다는 뜻입니다. 원본 APK를 Actions가 최초 제작했다는 증명도 아닙니다.

0.8.2에서는 긴 주소 처리의 정규식 성능 문제를 수정했고, [공개 소스 검사 실행](https://github.com/masudaayano156/moa-theme-viewer/actions/runs/39096346205)이 완료됐습니다. CodeQL의 해당 경고는 해소됐으며, 남은 11개 경고는 숨기지 않고 [항목별 검토 근거](codeql-review.md)와 [현재 분석 기록](reports/v0.8.2-codeql.json)에 구분했습니다. 자동 분석 성공은 무경고·무악성 인증이 아닙니다.

## 이전 0.8.1 검사 결과

| 검사 | 결과와 한계 |
|---|---|
| APK 해시·서명·권한 | 전용 인증서와 v2/v3 서명 유효, 디버깅 꺼짐. 권한 9개(구형 OS의 암묵적 읽기 1개 포함). 카메라·마이크 없음 |
| 소스 내용 재빌드 | DEX·Manifest·리소스·자산·내장 Git 정보 등 **189/189 항목 바이트 동일** |
| 파일 전체 재현 | **완전 일치 아님.** 서명 블록만 제거해도 ZIP 헤더·정렬 등의 포장 바이트는 다름 |
| VirusTotal | **68개 엔진 미탐지, 7개 형식 미지원**, 2026-10-18 12:58:56 KST 기준. 무악성 인증이 아님 |
| 의존성 | 68개 해석 항목의 라이브러리·버전·해시 공개. 중복을 합친 Maven 66개의 OSV 알려진 취약점 일치 없음 |
| 개발 검증 | Android JVM 747회 통과·실패/오류/스킵 0, lint 오류 0. 별도 검증 도구 합성 입력 44회 통과. 전문 보안 감사가 아님 |
| 공개 자동 검사 | [소스 검사·lint·CodeQL·OSV 실행](https://github.com/masudaayano156/moa-theme-viewer/actions/workflows/security.yml), [APK 검증 실행](https://github.com/masudaayano156/moa-theme-viewer/actions/workflows/verify-release.yml)의 실제 상태 확인 |

- 해당 구버전 릴리스·APK 배포는 종료했습니다. 아래 검사 자료는 당시 기록입니다.
- [VirusTotal 원본 보고서](https://www.virustotal.com/gui/file/4923612ab3bdf0106816befcf27350731ceb16039aa6ae3675247e2476e4e9ed) · [검사 시점 요약](reports/v0.8.1-virustotal.json)
- [버전 정보](releases/v0.8.1.json) · [APK 검사](reports/v0.8.1-apk.json) · [재빌드 상세 비교](reports/v0.8.1-rebuild.json)
- [검토·개발 검증 범위](reports/v0.8.1-review.json) · [실제 비밀값 제외 대조 범위](reports/v0.8.1-secret-exclusion.json)

빌드 소스 커밋은 `c790884f9ff0ead51ca47981c3646ee3cc75135c`이며 APK 내장 revision도 같습니다. 이후 문서·검증 워크플로 수정과 구분합니다. 로컬의 깨끗한 소스에서 만든 비서명 APK 두 개는 파일 전체가 같았지만, 배포 서명 과정에서는 ZIP 헤더·정렬도 바뀝니다. 따라서 배포 파일의 완전한 비트 단위 재현 성공으로 표시하지 않습니다.

공개 custom attestation은 배포 파일의 해시·서명·권한과 **모든 ZIP 항목 내용의 소스 대조 결과**를 명시합니다. `isBitExactReproduction: false`, `rawApkBytesExcludingSigningBlock: different`이면 포장 차이가 남아 있다는 뜻입니다. 원본 APK를 Actions가 최초 제작했다는 증명도 아닙니다.

## 이전 0.8.0 기준 자료

| 검사 | 결과와 한계 |
|---|---|
| 배포 파일 동일성 | GitHub 다운로드와 로컬 배포 파일의 SHA-256 일치 |
| APK 서명 | 전용 인증서, v2/v3 서명 유효, 디버깅 비활성화 |
| 권한 | 실제 APK의 명시·암묵 권한을 별도 기록. 0.8.0에는 카메라·마이크 선언이 있음 |
| 소스 재빌드 | 189개 ZIP 항목 중 188개 동일. 유일한 차이는 `META-INF/version-control-info.textproto` |
| 내장 소스 정보 | 배포 전 commit 시점 차이로 내장 revision과 발행 source commit이 다름. **완전한 비트 단위 재현 성공이 아님** |
| VirusTotal | 해시 조회 당시 공개 검사 기록을 찾지 못함. 미제출이며 무탐지 판정을 주장하지 않음 |
| 전문 보안 감사 | 미실시. AI 지원 소스 검토·자동 분석·기능 검사를 구분함 |

[0.8.0 기계 판독용 정보](releases/v0.8.0.json), [APK 검사 결과](reports/v0.8.0-apk.json), [재빌드 항목 비교](reports/v0.8.0-rebuild.json)를 제공합니다. 새 버전에는 해당 파일과 실제 실행 기록을 별도로 추가합니다. 알려진 0.8.0 출처 정보 차이를 덮어쓰거나 과거 APK를 바꾸지 않습니다.

## 0.8.1 준비 단계에서 실행한 의존성 검사

[실제 해석된 의존성 68개 항목](reports/v0.8.1-dependencies.cdx.json)과 [OSV 조회 결과](reports/v0.8.1-dependencies-osv.json)를 제공합니다. 중복 좌표를 합친 Maven 패키지 66개를 조회했으며, 해당 조회 시점에 알려진 취약점과 일치하는 항목은 없었습니다. 데이터베이스 범위·시점 제한이 있으며 Android OS·WebView·SDK·원격 서버까지 검사한 결과가 아닙니다. 최종 APK 비교와 CI 실행 결과는 릴리스 단계에서 별도로 추가합니다.

## 누구나 다시 확인하기

Python 3와 Android SDK Build Tools 35.0.0, Java가 준비된 환경에서 실행합니다. 이 도구는 APK를 설치하거나 실행하지 않으며 개인키를 요구하지 않습니다.

```sh
python verification/verify_apk.py --apk moa-0.9.0.apk --manifest verification/releases/v0.9.0.json --sdk "$ANDROID_HOME" --report apk-report.json
```

재현 빌드는 지정 source commit의 깨끗한 checkout에서 실행해야 합니다. 서명 블록 외의 차이를 숨기지 않습니다. 개인 빌드 로그나 추출한 임시 설정을 이슈·PR·검사 사이트에 올리지 마세요.

## 자동 검사와 서명 키

공개 Actions에는 서명 개인키·서버 개인키·관리자 비밀값을 등록하지 않습니다. 앱을 직접 서명하는 과정은 로컬에 남겨 둡니다. 공개 자동 빌드는 비서명 결과와 배포 파일을 비교하고, 출처 증명을 만들 때도 실제로 실행한 빌드·검증의 범위를 명시합니다. 다운로드 파일을 검사만 한 작업을 ‘그 APK를 만든 빌드’라고 표현하지 않습니다.

보안 경고·취약점 업데이트와 비공개 신고는 GitHub의 무료 기능을 사용합니다. 같은 저장소의 해시와 보고서를 공격자가 함께 바꿀 수 있다는 한계가 있으므로, 공개 인증서 지문·출처 증명·다른 사람의 검토 등 여러 근거를 함께 확인하는 편이 좋습니다.
