# 모아 푸시 연결과 공개 글 댓글 확인

이 Worker는 Android 기기를 연결하고 연결 확인 알림 및 사용자가 선택한 공개 글의 댓글 수 증가 알림을 FCM으로 보냅니다. 댓글 확인은 주기적인 공개 페이지 조회이며 공식 서비스의 즉시 댓글 이벤트와 연동하는 방식이 아닙니다. 디시 계정·쿠키·비밀번호는 받지 않습니다. 댓글 확인에 동의한 글의 URL은 서버에 저장되며, 로그인하지 않은 상태에서 읽을 수 있는 글만 확인합니다.

## 배포 준비

- Cloudflare Workers와 D1 데이터베이스를 사용합니다. 예제 `wrangler.jsonc`의 프로젝트 ID와 데이터베이스 ID는 자리표시자입니다. 실제 계정 값은 별도 비공개 구성에 넣습니다.
- D1에 `migrations/0001_init.sql`과 `migrations/0002_public_watches.sql`을 순서대로 적용합니다. 기존 배포는 두 번째 마이그레이션만 추가합니다. 두 번째 마이그레이션을 적용한 다음 서버 코드를 배포합니다.
- 공개 예제의 Cron은 1분 간격입니다. 한 번에 기한이 지난 공개 글 최대 1개와 대기 알림 최대 1개만 처리하고, 만료된 연결 코드·요청 제한·구독을 정리합니다. 등록한 글이 없으면 디시를 조회하지 않습니다.
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
| `GET /v1/devices/{installationId}/watches` | 설치 비밀값 Bearer | `{watches:[...],intervalSeconds:300,maxWatches:3}`; 200 |
| `PUT /v1/devices/{installationId}/watches` | 설치 비밀값 Bearer, `{url}` | `{watch:{...}}`; 200 |
| `DELETE /v1/devices/{installationId}/watches/{watchId}` | 설치 비밀값 Bearer | `{removed:true}`; 200 |
| `DELETE /v1/devices/{installationId}` | 설치 비밀값 Bearer | `{unpaired:true}`; 200 |

연결 코드는 160비트 무작위 base64url 27자이며 15분 동안 한 번만 쓸 수 있습니다. `expiresAt`은 Unix 초입니다. 관리자는 시간당 10개까지 발급할 수 있습니다. Android가 `installationId`(base64url 16–80자)와 `installationSecret`(SecureRandom 최소 32바이트, base64url 43–128자)을 생성해서 앱 내부에 저장합니다. 서버에는 비밀값과 연결 코드의 SHA-256 해시만 보관합니다. FCM 토큰은 32–4096자의 영숫자 및 `_:.-`를 허용합니다.

앱은 연결 요청 전에 설치 자격 증명을 저장해야 합니다. 서버에서 성공한 뒤 응답이 유실된 경우, 같은 자격 증명으로 토큰 갱신 요청을 보내 200 응답을 받으면 연결을 복구할 수 있습니다. 인증 오류가 아닌 통신 실패 때 설치 자격 증명을 버리지 않습니다.

테스트 알림은 자기 설치에만 보낼 수 있습니다. 임의 메시지, 토픽, 대상 토큰을 지정하는 발송 API는 없습니다. 테스트 FCM 데이터는 `{kind:"test",event_id:"<UUID>"}`, 댓글 확인 데이터는 `{kind:"comment",event_id:"<UUID>",url:"<등록한 공개 글 주소>"}`입니다. `notification` 페이로드는 사용하지 않으며 앱에서 미리 정한 문구와 아이콘으로 표시합니다. 패키지명은 `app.moa.client`, TTL은 테스트 60초·댓글 확인 24시간(86400초)입니다. 휴대폰이 오프라인이면 FCM은 댓글 확인 알림을 이 기간 안에서 보관할 수 있습니다. 202 응답이나 FCM 요청 성공은 휴대폰 표시 완료를 뜻하지 않습니다.

기기당 테스트는 60초에 한 번, 첫 요청부터 1시간에 5번입니다. 실패한 전송 시도도 제한에 포함됩니다. 인증 전 요청도 주소별 분당 30회로 제한합니다. 원본 주소는 저장하지 않고 관리자 비밀값과 날짜를 포함한 해시로 제한 기록을 만듭니다. 이 제한은 기본적인 남용 방지이며 대규모 공격 차단이나 별도 Cloudflare 보안 설정을 대신하지 않습니다.

FCM에서 토큰이 만료되었다고 확인되면 서버의 해당 토큰을 비우고 그 기기만 구독하는 글의 확인을 멈춥니다. 토큰 갱신 API로 복구할 수 있습니다. 연결 해제는 설치 행·FCM 토큰·해당 기기의 구독·대기 알림을 삭제합니다. 다른 기기가 구독하지 않는 URL도 삭제합니다. 이미 FCM에 전달한 알림은 취소하지 못합니다. D1 플랫폼 백업의 보존 기간은 Cloudflare 설정을 따릅니다. FCM 토큰과 구독 URL은 D1에 저장하며, 플랫폼 저장 시 암호화 외 별도 애플리케이션 암호화는 사용하지 않습니다.

## 공개 글 댓글 확인

앱은 등록 전에 해당 공개 글 주소가 Cloudflare 서버에 전달된다는 점과 주기 확인의 한계를 안내해야 합니다. 기본은 글별 수동 등록이며, 앞으로 직접 작성한 새 글의 자동 등록은 별도 명시적 동의가 있어야 합니다. 자동 등록에 동의했더라도 실제 새 글 등록 성공을 확인한 경우에만 호출하고 로그인·단순 열람·과거 작성 이력으로는 호출하지 않습니다. 기기 식별자와 구독 URL을 결합하면 이용 관심사를 알 수 있으므로 URL도 사용자 데이터로 취급하며 APK·공개 소스·로그에 포함하지 않습니다.

등록 URL은 정확한 `https://m.dcinside.com/{board|mini|person}/{게시판 ID}/{양의 글 번호}` 형식만 허용합니다. 외부 호스트·사용자 정보·명시적 포트·쿼리·해시·인코딩 경로·쓰기/로그인 주소는 거부합니다. 등록 API에서는 원본 페이지를 조회하지 않고 다음 예약 실행에서 최초 댓글 수를 기준값으로 저장합니다. 댓글이 없는 화면의 `0`도 유효한 기준입니다. 최초 확인에는 알림을 보내지 않고 이후 수가 증가할 때만 대기 알림을 만듭니다. 수가 줄어들면 알림 없이 기준을 갱신합니다.

소규모 무료 운영을 위한 초기 상한은 기기당 3개, 서버 전체의 서로 다른 글 5개, 같은 글을 구독하는 기기 3대입니다. 같은 URL은 한 번만 조회합니다. 글마다 최소 5분 간격이며 대기열·오류·플랫폼 사정으로 더 늦어질 수 있습니다. 구독은 7일 뒤 만료되고 같은 URL을 다시 등록하면 기한만 연장합니다. 등록/갱신은 기기당 시간당 10회로 제한합니다. 운영 규모를 늘리려면 접근 가능성·CPU·요청량을 먼저 검증하고 상한과 간격을 조정해야 합니다.

`watch` 객체는 `{watchId,url,baselineCount,lastChecked,status,expiresAt}`입니다. `watchId`는 UUID v4이고 시간은 Unix 초입니다. `baselineCount`는 최초 성공 전 `null`입니다. 상태는 `pending`, `active`, `blocked`, `rate_limited`, `unavailable`, `parse_error`, `delivery_error`, `token_expired`입니다. `active`는 댓글 수를 읽었다는 뜻이며 실제 휴대폰 수신 확인이 아닙니다. 상한 초과는 409 `watch_limit_reached`, 잘못된 URL은 400 `invalid_watch_url`입니다. 삭제는 자기 기기 소유의 구독에만 적용하며, 없거나 다른 기기의 ID를 지정해도 200을 반환해 소유권 정보를 노출하지 않습니다.

공개 페이지는 쿠키·인증 헤더 없이 GET으로만 가져옵니다. User-Agent는 `Moa-Public-Comment-Watch/1.0 (Android client; Mobile)`로 앱의 모바일 문서 요청임을 식별하며 다른 브라우저로 가장하지 않습니다. 리디렉션을 따르지 않고, 10초·512 KiB 제한과 HTML 형식을 확인합니다. 서버가 제공하는 댓글 영역의 수치만 읽고 본문·제목·닉네임·댓글 내용은 저장하지 않습니다. 403은 6시간, 429는 1시간 뒤 다시 확인합니다. 기타 실패는 점차 간격을 늘려 최대 6시간 뒤 재시도합니다. 차단·비공개·CAPTCHA·사이트 보호 장치를 우회하지 않습니다.

이 방식은 댓글 등록 이벤트 자체를 받지 않으므로 삭제와 추가가 겹쳐 총수가 같으면 놓칠 수 있습니다. 본인 댓글, 다른 사람 댓글, 복원된 댓글을 구분하지 않습니다. 본문 조회가 원본 사이트 조회수에 영향을 줄 수도 있습니다. 댓글 확인과 FCM 전송은 각각 원자적 작업 임대로 동시 중복 실행을 막습니다. 구독마다 대기 알림을 하나로 합치며 통신 실패 시 같은 이벤트 ID로 재시도합니다. FCM 수락 직후 응답이 유실되는 경우처럼 정확히 한 번 전송을 보장할 수 없는 상황은 남아 있습니다.

## 검증 범위와 비용

로컬 검증은 SQLite 기반 D1 어댑터 검사와 실제 workerd·HTMLRewriter·D1 런타임 검사를 사용합니다. 외부 Google·공개 페이지 응답은 모의 응답으로 대체하며 인증·소유권·동시 제한·JWT·토큰 만료·HTML 범위·스트림 크기·주기 작업 임대·삭제 정리를 확인합니다. 운영 Cloudflare에서 디시 접근이 허용되는지, 실제 CPU 한도 안에 드는지, 휴대폰에 알림이 도착하는지는 별도 확인해야 합니다.

Cloudflare Workers Free는 HTTP와 Cron 모두 실행당 CPU 10ms 제한이 있으므로 HTML 파싱·RS256 서명·JSON 처리까지 포함한 실제 Cloudflare CPU 측정이 필요합니다. OAuth 토큰은 Worker 인스턴스 메모리에 만료 1분 전까지 캐시하지만 콜드 시작에는 다시 서명합니다. Google 요청도 `manual` 리디렉션 모드에서 3xx를 거부합니다. 로컬 실행 시간은 Cloudflare CPU 측정값이 아닙니다. 무료 운영 가능이나 즉시 수신을 보장하지 않습니다.

참고: [D1 원자적 batch](https://developers.cloudflare.com/d1/worker-api/d1-database/), [FCM HTTP v1 인증](https://firebase.google.com/docs/cloud-messaging/send/v1-api), [Workers 실행 제한](https://developers.cloudflare.com/workers/platform/limits/).
