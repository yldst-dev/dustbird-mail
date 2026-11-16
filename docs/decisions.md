# 기술 결정 기록

조사 시점은 2026년 11월 16일입니다. 확인하지 못한 항목은 가정이라고 표시했습니다.

## 1. 모듈 구성

- `:core`는 JVM 전용 모듈입니다. 서비스 프로필, 계정과 발신 주소 규칙, 수신 주소 판별, 답장 규칙, MIME 조립과 해석, 폴더 역할 매핑, 발송 상태 기계, 폴더 동기화 알고리즘, HTML 정제, IMAP과 SMTP 게이트웨이가 들어 있습니다. Android 클래스에 의존하지 않아서 JVM 단위 테스트와 통합 테스트로 검증합니다.
- `:app`은 Android 모듈입니다. Room 저장소, 자격 증명 보관, 리포지토리, 동기화와 발송 서비스, 알림, 백그라운드 작업, 화면이 들어 있습니다.
- 서비스 간 차이는 `ProviderProfile` 데이터로만 표현하고 서비스 이름으로 분기하는 코드는 없습니다.

## 2. 메일 라이브러리

| 후보 | 결과 |
| --- | --- |
| `com.sun.mail:android-mail` 1.6.8 (2025-07-23) | 채택. Android 전용 배포판이고, 사용하는 JDK 클래스가 모두 안드로이드 플랫폼에 있습니다. |
| `org.eclipse.angus:angus-mail` 2.0.5 | 제외. 클래스 상수를 검사한 결과 `java.awt.Image`, `java.awt.Toolkit`, `javax.security.sasl.*`를 참조하는데 안드로이드 플랫폼 jar에는 없습니다. 지연 로딩이라 당장 죽지는 않을 수 있지만 검증된 경로가 아닙니다. |
| 직접 구현 | 금지 사항입니다. |

정정: 처음에는 Angus의 `java.awt.datatransfer` 의존이 문제라고 적었으나 `jakarta.activation-api` 2.1.4는 `java/awt` 참조가 0건이었습니다. 실제 사유는 위 표의 `java.awt.Image`, `java.awt.Toolkit`, `javax.security.sasl`입니다.

- 라이선스: 이 jar의 `META-INF/NOTICE.md`는 Eclipse Public License 2.0과 GPL 2.0 클래스패스 예외를 선언합니다. 전문은 `third_party/licenses/`와 앱의 오픈소스 고지 화면에 있습니다.
- IDLE는 `IMAPFolder.idle`, 토큰 인증은 `SMTPTransport$OAuth2Authenticator`와 IMAP XOAUTH2 클래스가 jar에 있습니다. 이번 범위에서 토큰 인증은 동작시키지 않았습니다.
- 알려진 취약점 CVE-2025-7962(SMTP 주입)는 `com.sun.mail:jakarta.mail`이 1.6.8에서 수정된 것으로 공개되어 있습니다. android-mail 1.6.8의 공식 확인 문서는 찾지 못했습니다. 대신 1.6.7과 1.6.8의 클래스 해시를 비교해 `SMTPTransport`를 포함한 변경 클래스 집합이 `jakarta.mail` 1.6.8과 완전히 같음을 확인했습니다(가정: 같은 수정이 들어 있음). 추가 방어로 `mail.mime.allowutf8`를 끄고, 주소와 헤더 값의 줄바꿈과 NUL을 입력 경계에서 거부하며 테스트로 고정했습니다.

## 3. TLS 설정

- 1.6.8의 기본값은 `ssl.checkserveridentity=false`, `starttls.required=false`입니다(바이트코드로 확인). 그래서 모든 프로토콜에 명시적으로 설정합니다. 테스트가 속성 값과 실제 동작(다른 호스트 인증서 거부, STARTTLS 미제공 서버 거부, IP 주소 접속 거부)을 둘 다 확인합니다.
- 인증서 신뢰 우회 설정은 제품 코드에 없습니다. 테스트는 JVM 신뢰 저장소 속성으로, 기기 테스트는 디버그 빌드의 도메인 한정 설정으로 테스트용 인증서만 신뢰합니다.
- 이 라이브러리는 인증서의 DNS 항목만 비교하고 IP 항목은 무시합니다. 실제 메일 서버는 이름으로 접속하므로 문제가 없고, 에뮬레이터 테스트용 인증서는 IP를 이름(CN)에 넣었습니다.
- 개발 중 발견한 결함: 프로토콜 이름이 없는 `URLName("smtp")`로 전송 객체를 만들면 모든 `mail.smtp.*` 설정이 무시되어 암호가 평문으로 나갈 수 있습니다. `SessionConfig.smtpUrl`로 고치고 STARTTLS 테스트(`tlsUsed`)와 단위 테스트로 막았습니다.

## 4. Material 3 Expressive

- 지시문은 Compose Material3 안정판 1.4.0을 전제했습니다. 확인 결과 1.4.0 안정판 jar에는 `ButtonGroup`, `LoadingIndicator`, `ToggleButton`, `SplitButton`, `FloatingToolbar`가 없고 1.5.0 계열에만 있습니다(BOM 2026.09.00은 1.4.0에 매핑).
- 결정: `androidx.compose.material3:material3:1.5.0-rc01`(2026-11-04, 릴리스 후보)을 사용합니다. 나머지 Compose는 BOM 2026.09.00(ui, foundation 1.12.1)입니다. 1.5.0 안정판이 나오면 버전 한 줄만 올리면 됩니다.
- 테마는 `MaterialExpressiveTheme`와 `MotionScheme.expressive()`를 씁니다.

## 5. 자격 증명 저장

- `androidx.security:security-crypto`와 `EncryptedSharedPreferences`는 지원 중단되어 쓰지 않습니다.
- 계정마다 Android Keystore의 AES-256-GCM 키를 따로 만들고(StrongBox 우선, 없으면 일반 Keystore), 암호문은 `noBackupFilesDir/credentials/<계정>.bin`에 저장합니다. 추가 인증 데이터(AAD)에 계정 번호를 묶어서 다른 계정의 파일로 바꿔치기해도 풀리지 않습니다. 계정을 지우면 키도 지워집니다.
- 키를 잃었거나 복원으로 키가 없어진 경우 읽기는 조용히 `null`을 돌려주고, 그 계정은 재인증 상태가 되어 알림으로 안내합니다.
- 대안으로 Tink의 AndroidKeysetManager를 검토했으나 추가 의존성이 필요하고 이 용도에는 직접 구현이 40줄 정도라 채택하지 않았습니다.
- 앱은 `allowBackup=false`이고 데이터 추출 규칙과 전체 백업 규칙이 모든 도메인을 클라우드 백업과 기기 간 전송에서 제외합니다.

## 6. 통합 테스트 서버

- GreenMail 2.1.15를 씁니다. GreenMail은 jakarta 이름공간이고 android-mail은 javax 이름공간이라 같은 클래스로더에 두면 `META-INF/javamail.default.providers`가 충돌합니다. 그래서 격리된 클래스로더로 띄웁니다.
- GreenMail에는 STARTTLS가 없습니다(클래스 문자열 검사). iCloud SMTP가 쓰는 587 경로, STARTTLS 구간의 인증서 이름 불일치, 암호화 제거 시도, DATA 전송 중 연결 끊김은 직접 만든 스크립트 소켓 서버(`ScriptedSmtpServer`)로 검증합니다.
- 테스트 서버 검증 결과는 실제 서비스 검증이 아닙니다.

## 7. SDK 버전

- minSdk 31: Material You 동적 색상이 31부터 제공됩니다. 갤럭시 기기의 Android 12 이상을 전제합니다(가정: 대상 기기가 Android 12 이상).
- targetSdk 36: 설치된 에뮬레이터 이미지(Android 36)와 같아서 실제로 실행 검증이 가능합니다.
- compileSdk 37: `androidx.core:core-ktx` 1.19.1이 compileSdk 37을 요구합니다.
- AGP 9.5.1(내장 Kotlin), Kotlin 2.4.21, KSP 2.3.14, Gradle 9.7.0, JDK 21.

## 8. 서체

Pretendard 1.3.9(2023-11-05) 공식 배포본의 `PretendardVariable.ttf`(6.7MB)를 `res/font`에 내장합니다. SIL OFL 1.1이며 라이선스 파일은 `third_party/pretendard/`와 앱 오픈소스 고지에 들어 있습니다. 가변 글꼴 가중치 축은 API 26부터 쓸 수 있고 minSdk가 31이라 별도 대체 구성이 필요 없습니다. 줄 높이는 `LineHeightStyle(Center, Trim.None)`과 `includeFontPadding=false`로 맞춥니다.

## 9. 동기화 방식 비교

| 방식 | 지연 | 비용과 제약 |
| --- | --- | --- |
| 수동 | 새로고침한 때 | 비용 없음 |
| 주기 작업(WorkManager) | 최소 15분, 기기가 더 늦출 수 있음 | 계정이 늘어도 한 번에 묶어 실행. 분 단위 정확도는 약속할 수 없음 |
| 실시간(IMAP IDLE, 사용자가 켜는 포그라운드 서비스) | 거의 즉시 | 계정마다 연결 1개를 항상 유지하고 상시 알림이 보임. 계정이 늘수록 배터리와 서버 연결이 늘어남 |

- 계정별로 방식을 고릅니다. 서버가 IDLE을 광고하지 않으면(네이버는 로그인 전 응답에서 광고하지 않음) 실시간 계정도 5분 간격 확인으로 대신합니다.
- 실시간 서비스는 `specialUse` 유형입니다. 공식 문서상 시간 제한(24시간 중 6시간)은 `dataSync`와 `mediaProcessing`에만 적용되므로 상시 연결에는 `specialUse`를 선언하고 사용 목적 속성을 적었습니다.
- 포그라운드 서비스는 앱이 화면에 있을 때만 시작할 수 있어서, 기기를 다시 켜거나 강제 종료한 뒤에는 앱을 한 번 열어야 다시 시작됩니다.
- 한 연결의 IDLE는 25분마다 NOOP으로 갱신합니다(서버가 30분 무활동 연결을 끊는 경우 대비).
- 배터리 최적화 예외는 설정 화면에서 이유를 설명하고 사용자가 시스템 화면에서 직접 선택하게 합니다.

## 10. 발송

- 발송 대기로 들어가는 시점에 Message-ID를 만들어 저장합니다. 도메인은 보내는 주소의 도메인입니다.
- 상태: 초안, 발송 대기, 전송 중, 서버 수락, 보낸 편지함 저장 완료. 예외 상태: 발송 실패, 결과 불명, 저장 실패.
- SMTP 단계를 추적하는 전송 객체로 DATA 이후의 연결 끊김만 결과 불명으로 분류합니다. 결과 불명은 자동으로 재전송하지 않고 보낸 편지함에서 Message-ID를 조회해 확인한 뒤 사용자가 선택합니다.
- 보낸 편지함에 넣기 전에 같은 Message-ID가 있는지 항상 검색합니다. 서버가 자동 저장한다고 프로필에 적힌 서비스는 직접 저장하지 않고 몇 차례 확인만 하며, 확인되지 않으면 저장 실패로 표시해서 사용자가 직접 저장을 고를 수 있게 합니다.
- 숨은 참조는 서버로 가는 사본에서 빼고 보낸 편지함 사본에만 남깁니다.
- 앱이 전송 중에 종료되면 다음 실행 때 결과 불명으로 복구합니다.

## 11. 폴더 역할

서버가 알려 주는 특수 용도 속성을 우선합니다. 속성이 없으면 알려진 이름(한국어 포함)을 후보로만 제시하고 사용자가 확정해야 지정됩니다. 이름 추측으로 자동 지정하지 않습니다. 매핑은 계정마다 저장합니다.

## 12. 수신 주소 판별

받는 사람과 참조에 그 계정의 발신 주소가 있으면 모두 표시하고, 없으면 서버가 넣는 전달 관련 헤더(`Delivered-To`, `X-Original-To`, 프로필 데이터)를 보며, 둘 다 없으면 미확인입니다. 라벨은 저장하지 않고 표시할 때 계산하므로 나중에 추가한 주소가 과거 메일에도 반영됩니다. 숨은 참조와 메일링 리스트로 온 메일은 판별되지 않을 수 있습니다.

## 13. HTML 메일

jsoup의 허용 목록으로 정제하고(스크립트, 이벤트 속성, 위험한 주소 형식, 폼, 프레임, 스타일 블록 제거, 인라인 스타일은 허용 속성만), 외부 이미지는 기본적으로 막습니다. 표시는 JavaScript를 끈 WebView와 콘텐츠 보안 정책, 모든 하위 요청 차단으로 한 번 더 막습니다. 링크는 실제 주소를 보여 주는 확인 창을 거쳐 외부 브라우저로만 엽니다.

## 14. 사서함 중복

호스트 이름과 로그인 이름(대소문자 무시)이 같으면 같은 사서함으로 보고 거부합니다. Gmail의 점과 `+` 별칭처럼 서비스가 같은 사서함으로 취급하는 변형은 알지 못하므로 막지 못합니다(알려진 제약).
