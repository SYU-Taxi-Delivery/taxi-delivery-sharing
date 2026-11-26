# 실행 및 학교 인증 연결

갱신일: 2026-10-12. 대상 저장소: SYU-Taxi-Delivery/taxi-delivery-sharing.

기존 Gradle 9.7.1 / Spring Boot 4.1.1 / Java 17 구조에 모집글과 Participation API를 통합했다. 사용자 엔티티, 역할, 저장소는 `com.school.party.domain.user`의 기존 구현을 재사용한다. `com.school.party.domain.community`에 모집글·참여 모듈을 배치했고 앱의 컴포넌트/엔티티/저장소 스캔에 두 도메인을 포함했다.

## 빌드 및 검증

Windows는 `gradlew.bat`, Linux/macOS는 `./gradlew`를 사용한다.

```powershell
./gradlew.bat test bootJar
java -jar build/libs/taxi-delivery-sharing-0.0.1-SNAPSHOT.jar --spring.profiles.active=demo
```

demo는 일회성 H2 DB를 사용하며 학교 인증 대신 author, student1, student2, student3, admin 계정과 `demo-pass` 비밀번호를 제공한다. 이 프로필은 개발 확인용이다. 각 계정은 HTTP Basic과 함께 실제 세션 로그인(`POST /api/auth/local/login`)도 쓸 수 있다. identifier는 학번 `demo-{이름}` 또는 학교 이메일 `demo-{이름}@syuin.ac.kr`이며, 프론트엔드 로그인 화면에서 이메일로 로그인해 FE·BE 통합 점검을 할 수 있다.

```powershell
./scripts/smoke-post-demo.ps1
./scripts/smoke-demo.ps1
```

실제 DB는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경변수로 연결한다. 기본 설정은 MySQL, UTC DATETIME 저장, Flyway 활성화, Hibernate validate다. 사용자 이메일 발송은 기존 application.properties의 SMTP 설정을 환경변수 `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`로 설정한다.

인증번호 메일은 두 가지 방식 중 하나로 보낸다 (#51).

| 방식 | 언제 쓰나 | 환경변수 |
|---|---|---|
| Brevo HTTPS API | `BREVO_API_KEY`가 있을 때. 배포 서버(Render)는 이 방식만 된다 | `BREVO_API_KEY`(Brevo API 키), `MAIL_FROM`(Brevo에서 인증한 보내는 주소, 필수), `MAIL_FROM_NAME`(보내는 사람 이름, 기본 `택시·배달 합승`) |
| SMTP | `BREVO_API_KEY`가 없을 때 (로컬 개발 기본값) | 위의 `SPRING_MAIL_*` |

Render 무료 웹 서비스는 메일 서버 포트(25, 465, 587)로 나가는 연결을 막기 때문에, 배포 서버에서 SMTP로 보내면 `Connect timed out` 후 503 `MAIL_DELIVERY_FAILED`가 된다. Brevo는 443 포트(HTTPS)를 써서 이 제한을 받지 않는다. 무료 요금제는 하루 300통까지다. `BREVO_API_KEY`가 있는데 `MAIL_FROM`이 비어 있으면 앱이 시작되지 않는다. 두 방식 모두 연결·응답을 5초까지만 기다리고, 발송에 실패하면 인증번호를 폐기해 바로 다시 요청할 수 있다.

전용 테스트 DB를 준비한 다음 `PARTICIPATION_TEST_DB_URL`, `PARTICIPATION_TEST_DB_USERNAME`, `PARTICIPATION_TEST_DB_PASSWORD`를 설정하고 아래 계약 테스트를 실행한다. 이 테스트는 데이터를 정리하므로 반드시 별도 테스트 DB를 사용한다.

동시성 재검증은 실제 락 대기도 관찰한다. 전용 MariaDB 테스트 계정에는 PROCESS 등 InnoDB 락 메타데이터 조회 권한이 필요하다. MySQL 컨테이너 테스트는 일회성 컨테이너 root를 사용한다. 운영 앱 계정에 이 검증용 권한을 부여할 필요는 없다.

```powershell
./gradlew.bat test --tests '*PostExternalDatabaseTest' --tests '*ExternalDatabaseIntegrationTest'
```

Docker가 있으면 `./gradlew.bat mysqlTest`로 MySQL 8.4 계약 테스트를 실행한다. 기본 test는 이 Docker 경로를 실행하지 않는다.

## 실제 학교 계정 로그인

1. `GET /api/csrf`에서 토큰과 쿠키를 받고, 변경 요청에 반환된 headerName/token을 전달한다.
2. 기존 `/api/auth/school-email/send-code`, `/verify-code`, `/register`로 이메일 인증과 회원가입을 완료한다. 모두 POST JSON 요청이다.
3. `POST /api/auth/local/login`에 `{"identifier":"학번 또는 학교 이메일","password":"가입한 비밀번호"}`를 보낸다. CSRF 토큰과 쿠키를 포함한다.
4. 서버는 기존 LocalAuthService/LOCAL_CREDENTIAL로 비밀번호·잠금을 검증하고 ACTIVE 계정의 사용자 ID를 세션에 저장한다. 응답은 `{"userId":"...","name":"...","role":"STUDENT"}`다.
5. 반환된 JSESSIONID 쿠키로 모집글·참여 API에 접근한다. 로그인 시 세션 ID와 CSRF 토큰이 갱신되므로 `GET /api/csrf`를 다시 호출한 뒤 쓰기 요청을 보낸다.
6. 로그아웃은 CSRF 토큰을 포함한 `POST /logout`이다. 세션을 무효화하고 CSRF 쿠키를 삭제하며 현재 응답은 302 리다이렉트다. 다음 로그인 전 `GET /api/csrf`를 다시 호출한다.

7. 비밀번호 재설정은 `POST /api/auth/password-reset/send-code` `{"schoolEmail":"..."}` → 메일의 6자리 코드로 `POST /api/auth/password-reset/confirm` `{"schoolEmail":"...","code":"123456","newPassword":"..."}`다. 둘 다 CSRF 토큰이 필요하다. 재설정이 끝나면 실패 횟수와 잠금이 풀린다.

학교 이메일은 공백 제거·소문자 변환 후 `syuin.ac.kr` 또는 `syu.ac.kr`만 허용한다(`app.auth.school-email.allowed-domains`로 변경). 인증 코드는 5분 유효, 5회 오입력 시 폐기, 60초 안 재요청 불가이며 인증 완료 후 30분 안에 가입해야 한다. 비밀번호는 8~64자, 72바이트 이하다. 인증 API 오류는 모집글 API와 같은 `{"error":{"code":...},"meta":...}` 형식이다. `/api/admin/**`는 ADMIN 역할만 접근할 수 있다. 자세한 내용은 [5주차 인증 점검](revalidation/auth-week5-2026-11-09.md)을 참고한다.

`GET /api/csrf` 응답의 `token`은 마스킹된 값이므로 쿠키 값을 그대로 헤더에 복사하는 대신 반환된 `headerName`과 `token`을 사용한다. 로그인 후 기존 세션 ID가 교체되고, 기존 세션이 없으면 새 세션을 발급한다. Basic 인증 필터와 demo 계정은 demo가 활성화된 경우에만 제공한다. 모집글·참여 API는 기존 세션 사용자도 매 요청 DB에서 ACTIVE 상태인지 확인하므로 SUSPENDED/WITHDRAWN 계정은 접근할 수 없다. [실제 HTTP 인증·CSRF 재검증](revalidation/authentication-csrf-2026-10-15.md)을 참고한다.

로그인 실패 횟수가 예외로 롤백되던 부분을 보정하고 credential 행 잠금으로 동시 실패 횟수를 관리한다. 기존 5회 실패/30분 잠금 정책을 유지한다. ID를 지정하는 헤더나 요청 필드로 로그인할 수 없다. 별도 학교 SSO 공급자와 JWT/RefreshToken 발급은 이번 세션 연결 범위에 포함하지 않는다.

## DB 적용

새 DB에서는 Flyway V1이 기존 팀 사용자 테이블 users/user_auth/local_credential/refresh_token을 만들고, V2가 post/participation과 USERS FK를 만든다. 앱은 Hibernate validate로 매핑을 확인한다.

이미 사용자 데이터가 있는 팀 DB는 먼저 백업하고 실제 테이블·컬럼·타입과 V1을 대조한다. 기본 Hibernate naming 전략의 물리 이름은 소문자 snake_case다. 대문자 물리 테이블을 쓰는 Linux DB나 native ENUM 컬럼이 있다면 팀 스키마에 맞춰 별도 전환 계획을 세운다. User.role/status와 UserAuth.provider 매핑은 이 스키마에 맞춰 VARCHAR로 명시했다.

V1과 동일한 기존 스키마임을 확인한 경우에만 담당자가 Flyway CLI에서 version 1로 baseline한 뒤 앱을 실행해 V2를 적용한다. `baseline-on-migrate`를 자동으로 켜거나 공유 DB를 초기화하지 않는다. 이전 taxi-delivery-user-backend의 DB는 별도 이력을 가지므로 이 baseline 절차의 대상이 아니다.

2026-11-13 PM 확인 결과, 데이터를 보존해야 하는 기존 팀 사용자 DB는 없다. 따라서 위 baseline 절차는 현재 적용 대상이 없다. 배포용 MySQL 8.4 DB는 2026-11-05 빈 DB에 Flyway V1→V2를 적용해 만들었다(이력 `1:SQL`, `2:SQL`). 신규 DB 검증과 모의 기존 사용자 DB의 baseline/V2 리허설은 2026-10-15에 수행했으며, 기존 사용자 DB가 새로 생기면 그때 구조 대조·복제본 리허설을 진행한다. 모의 JPA 스키마에서는 refresh_token.revoked의 BIT(1)와 V1 BOOLEAN(TINYINT(1)) 차이가 발견됐다. [실제 DB 조사 및 적용 절차](db-migration-existing-users.md)와 [이번 재검증 결과](revalidation/db-migration-2026-10-15.md)를 참고한다.

## 서버 메모리 상태와 시연 전 확인 (2026-11-18, #38)

아래 상태는 DB가 아니라 서버 메모리에만 있다. 서버가 재시작되면 모두 사라진다.

| 상태 | 위치 | 사라지면 |
|---|---|---|
| 로그인 세션(JSESSIONID) | 서블릿 세션 | 다시 로그인해야 한다 |
| 가입·재설정 인증번호 | `EmailVerificationStore` | 인증번호를 다시 받아야 한다 |
| 가입 인증 완료 상태(30분) | `EmailVerificationStore` | 이메일 인증부터 다시 해야 한다 |

배포 서버(Render 무료 플랜)는 15분 동안 요청이 없으면 잠들고, 다음 요청 때 새로 시작한다. 그래서 잠들었다 깨어나면 위 상태가 사라진다. 서버를 2대 이상으로 늘리면 요청마다 다른 서버로 가서 같은 문제가 생기므로 지금 구조는 1대 운영을 전제로 한다.

**결정:** 캡스톤 기간에는 이 동작을 허용한다. 사용자 수가 적고 1대로 운영하며, 세션을 DB로 옮기려면 테이블 추가와 배포 DB 변경이 필요하기 때문이다. 실제 서비스로 공개하거나 서버를 늘릴 때는 세션과 인증 상태를 DB 등 외부 저장소로 옮긴다.

### 시연 전 확인 절차

1. 시연 10분 전에 Aiven 콘솔에서 DB 서비스가 Running인지 확인한다. 꺼져 있으면 Power on 후 실행 중이 될 때까지 기다린다.
2. `https://taxi-delivery-sharing.onrender.com/api/csrf`를 열어 200 응답을 확인한다. 잠들어 있으면 첫 응답까지 1~2분 걸린다.
3. 시연용 계정으로 미리 로그인해 둔다. 시연 중 15분 넘게 요청이 없으면 다시 로그인해야 할 수 있다.
4. 가입·비밀번호 재설정을 시연하려면 배포 환경에서 학교 메일로 인증번호가 도착하는지 미리 확인한다. 수신이 안 되면 준비된 계정으로 로그인부터 시연한다. 인증번호를 받은 뒤에는 서버가 재시작되지 않도록 바로 이어서 진행한다.

## PR 및 브랜치

main 기준 `codex/post-api`를 사용한다. 이전 제출 때 승인받은 브랜치명이며 현재 저장소에는 README와 PR 템플릿 외에 별도 브랜치 명명 규칙이 없다. 기존 사용자 코드를 기반으로 제출하고 이슈가 없으면 템플릿 관련 Issue에 해당 없음을 명시한다. 테스트 결과는 [verification.md](verification.md), 명세는 [post-api.md](post-api.md), [participation-api.md](participation-api.md)를 참고한다.
