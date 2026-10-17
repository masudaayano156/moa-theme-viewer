# 모아 푸시 연결과 공개 글 댓글 확인

이 Worker는 설치한 Android 앱을 관리자 코드 없이 연결하고, 연결 확인 알림 및 사용자가 선택한 공개 글의 댓글 수 증가 알림을 FCM으로 보냅니다. 기기 연결은 해당 FCM 토큰으로 전달한 일회용 확인값을 앱이 되돌려 보내는 방식입니다. 댓글 확인은 주기적인 공개 페이지 조회이며 공식 서비스의 즉시 댓글 이벤트와 연동하는 방식이 아닙니다. 디시 계정·쿠키·비밀번호는 받지 않습니다. 댓글 확인에 동의한 글의 URL은 서버에 저장되며, 로그인하지 않은 상태에서 읽을 수 있는 글만 확인합니다.

## 배포 준비

- Cloudflare Workers와 D1 데이터베이스를 사용합니다. 예제 `wrangler.jsonc`의 프로젝트 ID와 데이터베이스 ID는 자리표시자입니다. 실제 계정 값은 별도 비공개 구성에 넣습니다.
- D1에 `migrations/`의 SQL을 번호 순서대로 적용합니다. 공개 기기 등록은 `0003_public_registration.sql`, 공개 댓글 대기열은 `0004_public_watch_queue.sql`, 테스트 발송 진단은 `0005_notification_diagnostics.sql`, 새 글 최초 댓글 감지와 개수 제한 없는 구독 인덱스는 `0006_new_post_watch.sql`, 글별 3일 기한 전환은 `0007_three_day_watches.sql`이 필요합니다. 기존 배포에서는 아직 적용하지 않은 마이그레이션만 추가한 다음 서버 코드를 배포합니다.
- 공개 예제의 Cron은 1분 간격입니다. 실행할 작업이 있을 때 조회 최대 6개·기기별 발송 최대 6개·구독자 배분 최대 2개를 별도 내부 HTTP 요청으로 나눕니다. 각 요청은 한 개의 제한된 작업만 수행하며, 여러 HTML 파싱을 하나의 CPU 실행 시간에 몰아 넣지 않습니다. 등록한 글이 없으면 디시를 조회하지 않습니다.
- Worker Secret `ADMIN_SECRET`: 운영자가 생성하는 32바이트 이상의 무작위 값을 base64url로 인코딩한 문자열(43–128자)입니다. 서버 내부 작업 인증과 요청 제한에 사용하며 APK·저장소·채팅에 넣지 않습니다. 일반 이용자에게 이 값이나 관리자 연결 코드를 요구하지 않습니다.
- Worker Secret `FCM_SERVICE_ACCOUNT_JSON`: 해당 Firebase 프로젝트에서 FCM 관련 권한을 부여한 전용 서비스 계정의 JSON 키입니다. `roles/firebasecloudmessaging.admin`을 필요한 프로젝트에만 부여하며, Owner/Editor 역할을 사용하지 않습니다. 앱용 `google-services.json`과 다른 파일입니다.
- 일반 환경 변수 `FIREBASE_PROJECT_ID`: 서비스 계정 JSON의 `project_id`와 정확히 일치해야 합니다.
- 일반 환경 변수 `RELAY_ORIGIN`: 배포한 이 Worker 자신의 정확한 HTTPS `workers.dev` origin입니다. 공개 템플릿은 비워 두었습니다. 이 값이 없으면 정기 댓글 작업을 발송하지 않습니다. `global_fetch_strictly_public` 호환성 플래그도 필요합니다.
- Android에는 검토한 정확한 HTTPS Worker origin만 설정합니다. 이 서버는 CORS를 열지 않으며 브라우저 UI를 제공하지 않습니다.

실제 계정·개인키를 포함하는 파일은 저장소에 커밋하지 않습니다. 플랫폼 로그/요청 본문 캡처를 켜지 않습니다. 애플리케이션은 요청, 토큰, 오류의 원문을 로그에 남기지 않고, Google 오류도 정해진 오류 코드로만 반환합니다.

## API

공통 응답은 JSON이며 `Cache-Control: no-store`입니다. 본문은 UTF-8 JSON 최대 8 KiB, 알 수 없는 필드는 거부합니다. 쿼리 문자열과 HTTP 요청은 거부합니다. DELETE는 본문이 필요 없습니다.

| 요청 | 인증·본문 | 성공 응답 |
|---|---|---|
| `GET /health` | 없음 | `{ok:true,service:"moa-push-relay",protocol:1}` |
| `POST /v1/registrations` | `{registrationId,installationId,installationSecret,fcmToken}` | `{registrationId,installationId,state,expiresAt}`; 202. 이미 같은 기기·토큰이면 `paired:true`와 200 |
| `GET /v1/registrations/{registrationId}` | 설치 비밀값 Bearer | `{registrationId,installationId,state,expiresAt}`; 200. 완료 시 `paired:true` |
| `POST /v1/registrations/{registrationId}/confirm` | 설치 비밀값 Bearer, `{challenge}` | `{paired:true,installationId}`; 200 |
| `POST /v1/admin/pairing-codes` | 관리자 Bearer, `{}` | `{code,expiresAt}`; 201 |
| `POST /v1/pair` | `{code,installationId,installationSecret,fcmToken}` | `{paired:true,installationId}`; 201 |
| `PUT /v1/devices/{installationId}/token` | 설치 비밀값 Bearer, `{fcmToken}` | `{updated:true}`; 200 |
| `POST /v1/devices/{installationId}/test` | 설치 비밀값 Bearer, `{}` | `{queued:true,eventId}`; 202 |
| `GET /v1/devices/{installationId}/watches` | 설치 비밀값 Bearer | `{watches:[...],intervalSeconds:300,listLimit:20,maxWatches:null,expiryDays:3,automaticRenewal:false,bestEffort:true,queueStatus,retryAfterSeconds}`; 200 |
| `PUT /v1/devices/{installationId}/watches` | 설치 비밀값 Bearer, `{url,newPost?,publishedAt?}` | `{watch:{...}}` 또는 만료된 자동 요청의 `{expired:true}`; 200 |
| `DELETE /v1/devices/{installationId}/watches/{watchId}` | 설치 비밀값 Bearer | `{removed:true}`; 200 |
| `DELETE /v1/devices/{installationId}` | 설치 비밀값 Bearer | `{unpaired:true}`; 200 |

`expiresAt`은 Unix 초입니다. Android가 `installationId`(base64url 16–80자)와 `installationSecret`(SecureRandom 최소 32바이트, base64url 43–128자)을 생성해서 앱 내부에 저장합니다. 서버에는 설치 비밀값의 SHA-256 해시만 보관합니다. FCM 토큰은 32–4096자의 영숫자 및 `_:.-`를 허용합니다.

앱은 요청 전에 설치 자격 증명과 UUID v4 `registrationId`, 요청 토큰의 SHA-256 base64url 해시를 저장해야 합니다. 서버는 토큰에 `kind:"registration"`, `registration_id`, `installation_id`, `challenge`, `token_hash`만 담은 제어 메시지를 NORMAL 우선순위·300초 TTL로 보냅니다. 설치 비밀값은 FCM에 넣지 않습니다. 앱은 자신의 현재 대기 등록 ID·설치 ID·토큰 해시와 일치하고 사용자가 알림을 허용한 경우에만 확인합니다. 받았다는 이유만으로 임의의 등록을 승인하면 안 됩니다.

확인값은 무작위 32바이트이며 서버 DB에는 해시만 저장합니다. 등록 기한은 5분입니다. 같은 등록 ID·설치·비밀값·토큰으로 재요청하면 기존 상태만 돌려주고 확인 메시지를 다시 보내지 않습니다. 새로운 시도는 새 등록 ID를 사용하며, 같은 설치의 이전 대기 시도는 폐기합니다. 확인 성공은 원자적으로 기기를 생성하거나 동일 소유자의 토큰만 교체한 뒤 확인값을 소비합니다. 기존 댓글 구독은 유지됩니다. 이미 확인된 기기·비밀값·토큰이 일치하는 POST는 추가 메시지 없이 200 완료 상태를 반환하므로 확인 응답이 유실되거나 대기 기록이 만료된 뒤에도 복구할 수 있습니다.

초기 202 응답보다 FCM 확인과 등록 완료가 먼저 도착할 수 있습니다. 앱은 늦은 대기 응답으로 완료 상태를 되돌리지 않아야 합니다. 백그라운드 확인 요청은 Android의 유효한 작업 수명 안에서 실행하고, 만료·새 시도·알림 해제 시 오래된 확인 작업을 버립니다. 서버 상태는 `pending`, `confirmed`, `failed`이며, 교체된 시도는 409 `registration_superseded`, 만료된 기록은 410 `registration_expired`를 반환합니다. 이미 청소된 기록이나 소유권·확인값이 맞지 않는 요청은 401입니다.

토큰 회전에도 새 확인 절차가 필요합니다. 기존 `PUT /token`은 현재 토큰과 정확히 일치하는 복구 요청만 200으로 승인하고, 다른 토큰은 409 `token_verification_required`로 거부합니다. 유효한 확인값 없이 설치 비밀값만으로 다른 기기의 FCM 토큰에 알림을 돌릴 수 없습니다.

공개 등록 메시지는 전역 UTC 하루 2,000회, IP별 시간당 20회, 설치별·토큰별 각각 시간당 6회로 제한합니다. IP는 서버 비밀값으로 섞은 해시로만 제한 기록에 남습니다. 모든 발송 예산은 등록 대기 행 생성과 함께 원자적으로 차감하며, 초과 시 429와 `Retry-After`를 반환합니다. 동시에 확인 중인 시도는 2,000개까지입니다. 이 제한은 공개 서비스의 무료 처리량을 보호하며, FCM 수신 증명이 공식 APK의 진위나 사람이 이용한다는 증명은 아닙니다.

알림 해제의 `DELETE /devices/{installationId}`는 아직 확인 전인 대기 시도도 같은 설치 비밀값으로 취소합니다. 기기·대기 등록·구독·대기 알림을 정리하고, 설치 ID와 비밀값 해시만 가진 10분 취소 기록으로 늦게 도착한 이전 요청의 재생성을 막습니다. 일반 취소 기록 상한은 4,000개이며 실제 소유 기기의 해제는 우선 처리합니다. 앱이 다시 연결할 때는 이전 정리를 확인한 뒤 새로운 설치 자격 증명을 사용합니다.

`POST /v1/admin/pairing-codes`와 `/v1/pair`는 기존 운영자 시험판의 호환 경로입니다. 일반 공개 앱은 사용하지 않습니다. 관리자가 발급한 연결 코드는 160비트 무작위 값이고 15분에 한 번만 사용할 수 있으며 시간당 발급 상한은 10개입니다. 운영자 자격 증명은 계속 서버 외부의 비공개 관리 도구에만 둡니다.

테스트 알림은 자기 설치에만 보낼 수 있습니다. 임의 메시지, 토픽, 대상 토큰을 지정하는 발송 API는 없습니다. 테스트 FCM 데이터는 `{kind:"test",event_id:"<UUID>"}`, 댓글 확인 데이터는 `{kind:"comment",event_id:"<UUID>",url:"<등록한 공개 글 주소>"}`입니다. `notification` 페이로드는 사용하지 않으며 앱에서 미리 정한 문구와 아이콘으로 표시합니다. 패키지명은 `app.moa.client`, TTL은 테스트 60초·댓글 확인 24시간(86400초)입니다. 휴대폰이 오프라인이면 FCM은 댓글 확인 알림을 이 기간 안에서 보관할 수 있습니다. 202 응답이나 FCM 요청 성공은 휴대폰 표시 완료를 뜻하지 않습니다.

인증한 기기의 `GET /v1/devices/{installationId}/test`는 최근 테스트의 이벤트 ID·요청/FCM 수락 시각·제한된 결과 코드만 반환합니다. 본문·토큰·상위 서버 오류 내용을 기록하지 않습니다. Android는 기기 수신 시각과 OS 알림 요청 결과를 따로 저장하므로 서버 수락과 기기 수신을 구분할 수 있습니다.

기기당 테스트는 60초에 한 번, 첫 요청부터 1시간에 5번입니다. 실패한 전송 시도도 제한에 포함됩니다. 인증 전 요청도 주소별 분당 30회로 제한합니다. 원본 주소는 저장하지 않고 관리자 비밀값과 날짜를 포함한 해시로 제한 기록을 만듭니다. 이 제한은 기본적인 남용 방지이며 대규모 공격 차단이나 별도 Cloudflare 보안 설정을 대신하지 않습니다.

FCM에서 토큰이 만료되었다고 확인되면 서버의 해당 토큰을 비우고 그 기기만 구독하는 글의 확인을 멈춥니다. 토큰 갱신 API로 복구할 수 있습니다. 연결 해제는 설치 행·FCM 토큰·해당 기기의 구독·대기 알림을 삭제합니다. 다른 기기가 구독하지 않는 URL도 삭제합니다. 이미 FCM에 전달한 알림은 취소하지 못합니다. D1 플랫폼 백업의 보존 기간은 Cloudflare 설정을 따릅니다. FCM 토큰과 구독 URL은 D1에 저장하며, 플랫폼 저장 시 암호화 외 별도 애플리케이션 암호화는 사용하지 않습니다.

## 공개 글 댓글 확인

앱은 등록 전에 해당 공개 글 주소가 Cloudflare 서버에 전달된다는 점과 주기 확인의 한계를 안내해야 합니다. 앱의 첫 알림 동의는 앞으로 앱에서 직접 작성한 새 글의 자동 등록까지 포함하며, 글마다 추가 동의를 요구하지 않습니다. 다른 기존 글은 사용자가 직접 추가할 수 있습니다. 자동 등록에 동의했더라도 실제 새 글 등록 성공을 확인한 경우에만 호출하고 로그인·단순 열람·과거 작성 이력으로는 호출하지 않습니다. 기기 식별자와 구독 URL을 결합하면 이용 관심사를 알 수 있으므로 URL도 사용자 데이터로 취급하며 APK·공개 소스·로그에 포함하지 않습니다.

등록 URL은 정확한 `https://m.dcinside.com/{board|mini|person}/{게시판 ID}/{양의 글 번호}` 형식만 허용합니다. 외부 호스트·사용자 정보·명시적 포트·쿼리·해시·인코딩 경로·쓰기/로그인 주소는 거부합니다. 등록 API에서는 원본 페이지를 조회하지 않습니다. 직접 추가한 기존 글은 다음 예약 실행의 댓글 수를 기준값으로 저장하고 이후 증가부터 알립니다. 등록 성공을 확인한 새 글은 PUT 본문의 `newPost:true`로 구분하며 첫 확인에 이미 댓글이 있으면 알립니다. 이는 클라이언트의 새 글 등록 표시이며 서버가 원본 글 소유권을 검증한다는 뜻은 아닙니다. 최초 `0`도 유효하며 이후 증가 때 알립니다. PUT 재시도는 최초 알림을 반복 생성하지 않습니다. 수가 줄어들면 알림 없이 기준을 갱신합니다.

기기당 구독 저장 개수 제한은 없습니다. GET은 인덱스를 이용해 최근 20개만 반환하고 `listLimit:20,maxWatches:null`로 표시 제한임을 구분합니다. PUT은 최근 목록에 없는 기존 구독도 정확한 URL과 소유 기기로 찾습니다. 같은 URL은 함께 조회합니다. 글마다 최소 5분 간격이며 대기열·오류·플랫폼 사정으로 더 늦어질 수 있습니다. 자동 새 글은 `publishedAt`(등록 성공 시각, Unix 초)부터 3일간만 확인합니다. 전송이 지연되어도 서버 등록 시점부터 다시 3일을 부여하지 않습니다. 이미 만료된 자동 등록 요청은 `200 {expired:true}`로 처리하고 저장하지 않습니다. `newPost:true` 요청에는 `publishedAt`이 필수입니다. 수동 연장한 글은 최근 관리 목록에도 다시 나타납니다. 수동 추가·명시적 연장은 요청 시점부터 3일입니다. 자동 등록 재시도·목록 조회·새 댓글·다른 글 등록은 기존 기한을 연장하지 않습니다. 기한이 지난 구독은 조회·알림 배분·발송에서 제외하고 작은 묶음으로 정리합니다. 기존 기기 전체 유지 기한은 더 이상 구독 기한 판단에 사용하지 않습니다. 등록/갱신은 기기당 시간당 60회로 제한합니다.

댓글 수가 그대로일 때는 공통 조회 상태만 갱신하고 구독자 행은 갱신하지 않습니다. 댓글 수가 늘면 변경 번호를 부여하고, 한 번에 16개 구독씩 나누어 대기 알림을 만듭니다. 배분 중 새 변경이 와도 진행 중인 위치를 처음으로 돌리지 않습니다. 기기별 전송 순서를 두어 한 기기에 많은 글이 있어도 다른 기기가 기다릴 기회를 보장하며, 한 기기의 발송 사이에는 최소 1분 간격을 둡니다. 전송 중 더 새 변경이 들어온 경우 이전 전송 완료가 새 대기 알림을 지우지 않습니다.

기본 백그라운드 예산은 UTC 하루 내부 작업 20,000회·D1 쓰기 70,000행·읽기 3,000,000행입니다. 실행 전에 예산을 원자적으로 예약하고 D1 반환 메타데이터로 사용량을 보정합니다. 제한에 도달하면 작업을 대기시키고 다음 예산 기간에 재개합니다. `queueStatus:"quota_paused"`와 `retryAfterSeconds`로 앱에 대기를 알립니다. 이는 백그라운드 작업의 자체 예산이며 가입·조회·정리·같은 계정의 다른 서비스까지 포함한 플랫폼 전체 사용량 한도를 보장하지 않습니다.

`POST /internal/jobs`는 일반 앱 API가 아닙니다. origin·작업 종류·시간에 묶인 짧은 HMAC 증명을 검증하고, 조회·배분·발송 중 하나만 수행합니다. 관리자 비밀값 자체는 내부 HTTP 요청에도 넣지 않습니다. 리디렉션을 따르지 않고 동시에 최대 4개 요청만 시작하며 내부 작업에서 다시 작업을 분배하지 않습니다. 기기·글 주소·FCM 토큰은 작업 호출 본문에 넣지 않습니다.

`watch` 객체는 `{watchId,url,baselineCount,lastChecked,status,expiresAt}`입니다. `watchId`는 UUID v4이고 시간은 Unix 초입니다. `baselineCount`는 최초 성공 전 `null`입니다. 상태는 `pending`, `active`, `blocked`, `rate_limited`, `unavailable`, `parse_error`, `delivery_error`, `token_expired`입니다. `active`는 댓글 수를 읽었다는 뜻이며 실제 휴대폰 수신 확인이 아닙니다. 잘못된 URL은 400 `invalid_watch_url`입니다. 구독 개수 초과 오류는 반환하지 않으며 요청 빈도와 공유 작업 예산 제한은 유지합니다. 삭제는 자기 기기 소유의 구독에만 적용하며, 없거나 다른 기기의 ID를 지정해도 200을 반환해 소유권 정보를 노출하지 않습니다.

공개 페이지는 쿠키·인증 헤더 없이 GET으로만 가져옵니다. User-Agent는 `Moa-Public-Comment-Watch/1.0 (Android client; Mobile)`로 앱의 모바일 문서 요청임을 식별하며 다른 브라우저로 가장하지 않습니다. 리디렉션을 따르지 않고, 10초·512 KiB 제한과 HTML 형식을 확인합니다. 서버가 제공하는 댓글 영역의 수치만 읽고 본문·제목·닉네임·댓글 내용은 저장하지 않습니다. 403은 6시간, 429는 1시간 뒤 다시 확인합니다. 기타 실패는 점차 간격을 늘려 최대 6시간 뒤 재시도합니다. 차단·비공개·CAPTCHA·사이트 보호 장치를 우회하지 않습니다.

이 방식은 댓글 등록 이벤트 자체를 받지 않으므로 삭제와 추가가 겹쳐 총수가 같으면 놓칠 수 있습니다. 본인 댓글, 다른 사람 댓글, 복원된 댓글을 구분하지 않습니다. 본문 조회가 원본 사이트 조회수에 영향을 줄 수도 있습니다. 댓글 확인과 FCM 전송은 각각 원자적 작업 임대로 동시 중복 실행을 막습니다. 구독마다 대기 알림을 하나로 합치며 같은 변경의 통신 재시도에는 같은 이벤트 ID를 사용합니다. 새 변경이 오면 대기 내용을 갱신할 수 있습니다. FCM 수락 직후 응답이 유실되는 경우처럼 정확히 한 번 전송을 보장할 수 없는 상황은 남아 있습니다.

## 검증 범위와 비용

로컬 검증은 SQLite 기반 D1 어댑터 검사와 실제 workerd·HTMLRewriter·D1 런타임 검사를 사용합니다. 외부 Google·공개 페이지 응답은 모의 응답으로 대체하며 인증·소유권·동시 제한·JWT·토큰 만료·HTML 범위·스트림 크기·주기 작업 임대·삭제 정리를 확인합니다. 운영 Cloudflare에서 디시 접근이 허용되는지, 실제 CPU 한도 안에 드는지, 휴대폰에 알림이 도착하는지는 별도 확인해야 합니다.

Cloudflare Workers Free는 HTTP와 Cron 모두 실행당 CPU 10ms 제한이 있으므로 HTML 파싱·RS256 서명·JSON 처리까지 포함한 실제 Cloudflare CPU 측정이 필요합니다. OAuth 토큰은 Worker 인스턴스 메모리에 만료 1분 전까지 캐시하지만 콜드 시작에는 다시 서명합니다. Google 요청도 `manual` 리디렉션 모드에서 3xx를 거부합니다. 로컬 실행 시간은 Cloudflare CPU 측정값이 아닙니다. 무료 운영 가능이나 즉시 수신을 보장하지 않습니다.

참고: [D1 원자적 batch](https://developers.cloudflare.com/d1/worker-api/d1-database/), [FCM HTTP v1 인증](https://firebase.google.com/docs/cloud-messaging/send/v1-api), [Workers 실행 제한](https://developers.cloudflare.com/workers/platform/limits/).
