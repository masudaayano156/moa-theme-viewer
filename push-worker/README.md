# 모아 푸시 연결 기반

이 Worker는 Android 설치 한 대를 연결하고 고정된 연결 확인 알림을 FCM으로 보내는 서버입니다. 웹사이트나 댓글 수집 서버는 아닙니다. 디시 계정, 쿠키, 비밀번호, 게시글 주소 또는 본문을 받는 API가 없으며, 댓글 자동 알림은 아직 구현하지 않았습니다.

## 배포 준비

- Cloudflare Workers와 D1 데이터베이스를 사용합니다. 예제 `wrangler.jsonc`의 프로젝트 ID와 데이터베이스 ID는 자리표시자입니다. 실제 계정 값은 별도 비공개 구성에 넣습니다.
- D1에 `migrations/0001_init.sql`을 적용합니다. 공개 설정에는 하루 한 번 만료된 연결 코드와 요청 제한 기록을 정리하는 예약 작업만 있습니다. 게시판을 조회하는 예약 작업은 없습니다.
- Worker Secret `ADMIN_SECRET`: 운영자가 생성하는 32바이트 이상의 무작위 값을 base64url로 인코딩한 문자열(43–128자)입니다. APK, 저장소, 채팅에 넣지 않습니다.
- Worker Secret `FCM_SERVICE_ACCOUNT_JSON`: 해당 Firebase 프로젝트에서 FCM 관련 권한을 부여한 전용 서비스 계정의 JSON 키입니다. `roles/firebasecloudmessaging.admin`을 필요한 프로젝트에만 부여하며, Owner/Editor 역할을 사용하지 않습니다. 앱용 `google-services.json`과 다른 파일입니다.
- 일반 환경 변수 `FIREBASE_PROJECT_ID`: 서비스 계정 JSON의 `project_id`와 정확히 일치해야 합니다.
- Android에는 검토한 정확한 HTTPS Worker origin만 설정합니다. 이 서버는 CORS를 열지 않으며 브라우저 UI를 제공하지 않습니다.

실제 계정·개인키를 포함하는 파일은 저장소에 커밋하지 않습니다. 플랫폼 로그/요청 본문 캡처를 켜지 않습니다. 애플리케이션은 요청, 토큰, 오류의 원문을 로그에 남기지 않고, Google 오류도 정해진 오류 코드로만 반환합니다.

## API

공통 응답은 JSON이며 `Cache-Control: no-store`입니다. 본문은 UTF-8 JSON 최대 8 KiB, 알 수 없는 필드는 거부합니다. 쿼리 문자열과 HTTP 요청은 거부합니다. DELETE는 본문이 필요 없습니다.

| 요청 | 인증·본문 | 성공 응답 |
|---|---|---|
| `GET /health` | 없음 | `{ok:true,service:"moa-push-relay",protocol:1}` |
| `POST /v1/admin/pairing-codes` | 관리자 Bearer, `{}` | `{code,expiresAt}`; 201 |
| `POST /v1/pair` | `{code,installationId,installationSecret,fcmToken}` | `{paired:true,installationId}`; 201 |
| `PUT /v1/devices/{installationId}/token` | 설치 비밀값 Bearer, `{fcmToken}` | `{updated:true}`; 200 |
| `POST /v1/devices/{installationId}/test` | 설치 비밀값 Bearer, `{}` | `{queued:true,eventId}`; 202 |
| `DELETE /v1/devices/{installationId}` | 설치 비밀값 Bearer | `{unpaired:true}`; 200 |

연결 코드는 160비트 무작위 base64url 27자이며 15분 동안 한 번만 쓸 수 있습니다. `expiresAt`은 Unix 초입니다. 관리자는 시간당 10개까지 발급할 수 있습니다. Android가 `installationId`(base64url 16–80자)와 `installationSecret`(SecureRandom 최소 32바이트, base64url 43–128자)을 생성해서 앱 내부에 저장합니다. 서버에는 비밀값과 연결 코드의 SHA-256 해시만 보관합니다. FCM 토큰은 32–4096자의 영숫자 및 `_:.-`를 허용합니다.

앱은 연결 요청 전에 설치 자격 증명을 저장해야 합니다. 서버에서 성공한 뒤 응답이 유실된 경우, 같은 자격 증명으로 토큰 갱신 요청을 보내 200 응답을 받으면 연결을 복구할 수 있습니다. 인증 오류가 아닌 통신 실패 때 설치 자격 증명을 버리지 않습니다.

테스트 알림은 자기 설치에만 보낼 수 있습니다. 임의 메시지, URL, 토픽, 대상 토큰을 지정하는 발송 API는 없습니다. FCM 내용은 `{kind:"test",event_id:"<UUID>"}`뿐입니다. 앱은 미리 정한 안전한 문구로 알림을 표시합니다. 패키지명은 `app.moa.client`로 제한하고, TTL은 60초입니다. 202는 FCM이 요청을 받아들였다는 뜻이며 휴대폰 표시 완료를 뜻하지 않습니다.

기기당 테스트는 60초에 한 번, 첫 요청부터 1시간에 5번입니다. 실패한 전송 시도도 제한에 포함됩니다. 인증 전 요청도 주소별 분당 30회로 제한합니다. 원본 주소는 저장하지 않고 관리자 비밀값과 날짜를 포함한 해시로 제한 기록을 만듭니다. 이 제한은 기본적인 남용 방지이며 대규모 공격 차단이나 별도 Cloudflare 보안 설정을 대신하지 않습니다.

FCM에서 토큰이 만료되었다고 확인되면 서버의 해당 토큰을 비웁니다. 토큰 갱신 API로 복구할 수 있습니다. 연결 해제는 설치 행과 FCM 토큰을 삭제합니다. 이미 FCM에 전달한 알림은 취소하지 못합니다. D1 플랫폼 백업의 보존 기간은 Cloudflare 설정을 따릅니다. FCM 토큰은 다시 발송에 필요하므로 D1에 저장하며, 플랫폼 저장 시 암호화 외 별도 애플리케이션 암호화는 사용하지 않습니다.

## 검증 범위와 비용

로컬 테스트는 실제 SQLite를 사용하는 D1 어댑터와 모의 Google 응답으로 인증, 코드 일회성, 동시 발송 제한, JWT 서명, 토큰 만료, 오류 비노출을 확인합니다. 운영 서버나 실제 휴대폰의 푸시 성공을 대신하지 않습니다.

Cloudflare Workers Free는 실행당 CPU 10ms 제한이 있으므로 RS256 서명·JSON 처리까지 포함한 실제 Cloudflare CPU 측정이 필요합니다. OAuth 토큰은 Worker 인스턴스 메모리에 만료 1분 전까지 캐시하지만 콜드 시작에는 다시 서명합니다. 로컬 실행 시간은 Cloudflare CPU 측정값이 아닙니다. 무료 운영 가능이나 즉시 수신을 보장하지 않습니다.

참고: [D1 원자적 batch](https://developers.cloudflare.com/d1/worker-api/d1-database/), [FCM HTTP v1 인증](https://firebase.google.com/docs/cloud-messaging/send/v1-api), [Workers 실행 제한](https://developers.cloudflare.com/workers/platform/limits/).
