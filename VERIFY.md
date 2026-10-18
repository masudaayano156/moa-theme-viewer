# 설치 파일 확인 방법

**APK의 이름만 보지 말고 버전·해시·서명·검사 범위를 함께 확인하세요.** 소스 공개와 검사 결과는 판단의 근거이며 ‘100% 안전’ 인증은 아닙니다.

## 1. 공식 배포 위치 확인

[이 저장소의 Releases](https://github.com/masudaayano156/moa-theme-viewer/releases)에서 APK와 `SHA256SUMS.txt`를 받습니다. [버전별 검사 자료](verification/README.md)에서 같은 버전의 정보를 선택합니다. 다른 사람이 전달한 파일은 파일명이 같아도 별도로 확인해야 합니다.

## 2. 파일 해시 확인

Windows PowerShell에서 파일을 받은 폴더를 열고 실행합니다.

```powershell
Get-FileHash .\moa-0.9.0.apk -Algorithm SHA256
```

macOS/Linux에서는 다음과 같이 확인합니다.

```sh
shasum -a 256 moa-0.9.0.apk
```

출력값이 **같은 버전**의 `SHA256SUMS.txt`와 검사 자료의 값에 모두 일치해야 합니다. 이 검사는 파일이 공개한 것과 같은지 확인하며, 파일 내용의 안전성을 판정하는 검사는 아닙니다.

## 3. 제작자 서명 확인

현재 배포 인증서의 SHA-256 지문:

```text
d425e1a47b90276aefb8d8ae1617fd44b0528113be25712faea20079b1211993
```

Android SDK의 `apksigner verify --verbose --print-certs 파일.apk` 결과에서 서명이 유효한지, 인증서 지문이 일치하는지 확인합니다. 공개 인증서·지문은 개인키가 아닙니다. 개인키나 비밀번호를 제공받을 필요가 없습니다.

Android는 서명이 다른 앱으로 기존 설치를 업데이트하는 것을 제한합니다. 서명은 제작자·파일의 일관성 확인 수단이며 Google의 악성코드 검사나 공식 인증을 뜻하지 않습니다.

## 4. 검사 결과의 종류 확인

| 자료 | 확인하는 것 | 보장하지 않는 것 |
|---|---|---|
| 해시·서명 검사 | 배포 파일의 동일성과 인증서 | 악성 동작이 전혀 없음 |
| 권한·소스 검토 | 선언·실제 사용 경로와 확인한 위험 | 모든 실행 경로·모든 제3자 서비스의 안전성 |
| CodeQL·Android lint 등 자동 분석 | 도구가 지원하는 오류·취약점 패턴 | 백도어 부재에 대한 인증 |
| 재빌드 비교 | 지정 소스·환경에서 나온 코드와 APK의 일치 | 그 소스 자체가 안전함 |
| 외부 다중 백신 보고서 | 해당 파일에 대한 검사 업체들의 탐지 | 미탐지 악성 동작·오탐 없음 |

실행하지 않은 검사는 ‘미실시’, 차이가 있으면 차이 내용을 표시합니다. [보안 검토 기록](verification/source-review.md)은 AI 지원 코드 검토이며 전문 기관의 독립 보안 감사로 표현하지 않습니다.

## 빌드 출처와 재현 검증

공개 검증 도구와 Actions는 서명 개인키·서버 키를 받지 않습니다. 0.9.0은 Firebase·서버 설정 없이 재빌드하며 APK에서 클라이언트 설정을 추출하지 않습니다. 이전 Firebase 버전의 재빌드는 해당 과거 소스의 허용된 클라이언트 리소스만 임시 추출하고 로그·저장소에 값을 출력하지 않습니다.

소스 commit, APK 내부 VCS 정보, DEX·Manifest·리소스·자산과 ZIP 항목의 차이를 함께 기록합니다. 출처 메타데이터까지 일치하지 않는 파일을 완전한 비트 단위 재현 성공으로 표시하지 않습니다. 검증·재빌드 출처 증명도 실제 작업 범위를 명시하며, 로컬에서 만든 기존 APK를 GitHub Actions가 직접 만든 APK라고 주장하지 않습니다.

`verification/releases/v버전.json`의 `source_commit`으로 **새로 복제한 checkout**을 준비하고, APK 파일과 결과 폴더는 checkout 밖에 둡니다. Python 3.10 이상, JDK 17, Android SDK platform 35·build-tools 35.0.0·cmdline-tools가 필요합니다.

```sh
python verification/reproduce_apk.py build \
  --apk /path/to/moa-VERSION.apk \
  --source-root /path/to/fresh-source-checkout \
  --expected-source-commit SOURCE_COMMIT \
  --expected-apk-sha256 APK_SHA256 \
  --sdk "$ANDROID_HOME" --java-home "$JAVA_HOME" \
  --output /path/to/new-comparison-folder
```

`VERSION`, `SOURCE_COMMIT`, `APK_SHA256`과 경로를 실제 버전 자료로 바꿉니다. 기본 Gradle 사용자 홈도 결과 폴더에 새로 만듭니다. 별도 캐시를 지정하면 해당 캐시에 추가 init 스크립트나 Gradle 속성이 없어야 합니다. 재시도할 때는 새 checkout과 새 결과 폴더를 사용합니다. `comparison.json`은 모든 ZIP 항목과 서명 블록만 제거한 바이트를 비교하며, 차이가 있으면 종료 코드가 0이 아닙니다. 서명 키는 필요하지 않습니다.

일반 CI의 소스 검사는 `verification/audit_source.py`, APK 검사는 `verification/verify_apk.py`에 공개했습니다. 의존성 목록은 `verification/dependency_inventory.gradle`로 생성하고 `verification/scan_dependencies.py`로 OSV의 알려진 Maven 취약점과 대조합니다. OSV에는 공개 라이브러리 이름·버전만 보내며 사용자 자료나 APK는 전송하지 않습니다. 의존성 데이터베이스의 범위 밖이거나 새로 발견된 문제는 이 검사로 보장하지 못합니다.

## 공개 검증 실행과 증명 확인

[Release APK verification](https://github.com/masudaayano156/moa-theme-viewer/actions/workflows/verify-release.yml)에서 해당 버전의 성공한 실행을 선택합니다. 실행 기록의 source commit·APK 해시·비교 결과를 확인하고, 정리된 JSON 결과를 받을 수 있습니다. 사용자 자료, 임시 클라이언트 설정, 빌드 로그와 개인키는 결과에 포함하지 않습니다.

이 워크플로의 증명은 **배포 파일을 내려받아 검사·재빌드 비교했다는 custom verification attestation**입니다. 로컬에서 서명한 APK를 GitHub가 최초 제작했다는 build provenance가 아닙니다. GitHub CLI가 있으면 다음과 같이 같은 저장소에서 발행한 증명을 검증할 수 있습니다.

```sh
gh attestation verify moa-VERSION.apk --repo masudaayano156/moa-theme-viewer \
  --predicate-type https://github.com/masudaayano156/moa-theme-viewer/verification/apk/v1
```

증명에 적힌 검사 범위와 결과를 함께 읽습니다. 실행이 실패했거나 증명이 없으면 있다고 간주하지 않습니다. 같은 저장소의 운영 권한까지 손상된 경우를 증명 하나로 해결하지는 못합니다.

증명에서 `isBitExactReproduction`도 확인하세요. `false`이면 모든 ZIP 항목의 내용이 같아도 파일 포장 바이트에는 차이가 남아 있다는 뜻입니다. 0.8.1과 0.8.2는 이 경우이며, 원시 비교의 차이를 숨기거나 완전한 파일 재현 성공으로 표시하지 않습니다.

## Play 프로텍트 경고

이 자료를 갖추어도 설치 시 Play 프로텍트의 미검사 안내가 사라지는 것은 아닙니다. 앱 검사를 허용하고, 유해 앱 판정이 나오면 해당 버전의 보고서와 함께 원인을 확인해야 합니다. 사용자에게 보호 기능을 끄도록 요구하지 않습니다.

- [Android 앱 서명](https://developer.android.com/studio/publish/app-signing)
- [GitHub 출처 증명](https://docs.github.com/en/actions/concepts/security/artifact-attestations)
- [Android 재현 빌드 설명](https://f-droid.org/docs/Reproducible_Builds/)
