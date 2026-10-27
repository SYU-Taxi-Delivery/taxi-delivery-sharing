# 실행 및 학교 인증 연결

갱신일: 2026-10-12. 대상 저장소: SYU-Taxi-Delivery/taxi-delivery-sharing.

기존 Gradle 9.7.1 / Spring Boot 4.1.1 / Java 17 구조에 모집글과 Participation API를 통합했다. 사용자 엔티티, 역할, 저장소는 `com.school.party.domain.user`의 기존 구현을 재사용한다. `com.school.party.domain.community`에 모집글·참여 모듈을 배치했고 앱의 컴포넌트/엔티티/저장소 스캔에 두 도메인을 포함했다.

## 빌드 및 검증

Windows는 `gradlew.bat`, Linux/macOS는 `./gradlew`를 사용한다.

```powershell
./gradlew.bat test bootJar
java -jar build/libs/taxi-delivery-sharing-0.0.1-SNAPSHOT.jar --spring.profiles.active=demo
```

demo는 일회성 H2 DB를 사용하며 학교 인증 대신 author, student1, student2, student3, admin 계정과 `demo-pass` 비밀번호를 제공한다. 이 프로필은 개발 확인용이다.

```powershell
./scripts/smoke-post-demo.ps1
./scripts/smoke-demo.ps1
```

실제 DB는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경변수로 연결한다. 기본 설정은 MySQL, UTC DATETIME 저장, Flyway 활성화, Hibernate validate다. 사용자 이메일 발송은 기존 application.properties의 SMTP 설정을 환경변수 `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`로 설정한다.

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

`GET /api/csrf` 응답의 `token`은 마스킹된 값이므로 쿠키 값을 그대로 헤더에 복사하는 대신 반환된 `headerName`과 `token`을 사용한다. 로그인 후 기존 세션 ID가 교체되고, 기존 세션이 없으면 새 세션을 발급한다. Basic 인증 필터와 demo 계정은 demo가 활성화된 경우에만 제공한다. 모집글·참여 API는 기존 세션 사용자도 매 요청 DB에서 ACTIVE 상태인지 확인하므로 SUSPENDED/WITHDRAWN 계정은 접근할 수 없다. [실제 HTTP 인증·CSRF 재검증](revalidation/authentication-csrf-2026-10-15.md)을 참고한다.

로그인 실패 횟수가 예외로 롤백되던 부분을 보정하고 credential 행 잠금으로 동시 실패 횟수를 관리한다. 기존 5회 실패/30분 잠금 정책을 유지한다. ID를 지정하는 헤더나 요청 필드로 로그인할 수 없다. 별도 학교 SSO 공급자와 JWT/RefreshToken 발급은 이번 세션 연결 범위에 포함하지 않는다.

## DB 적용

새 DB에서는 Flyway V1이 기존 팀 사용자 테이블 users/user_auth/local_credential/refresh_token을 만들고, V2가 post/participation과 USERS FK를 만든다. 앱은 Hibernate validate로 매핑을 확인한다.

이미 사용자 데이터가 있는 팀 DB는 먼저 백업하고 실제 테이블·컬럼·타입과 V1을 대조한다. 기본 Hibernate naming 전략의 물리 이름은 소문자 snake_case다. 대문자 물리 테이블을 쓰는 Linux DB나 native ENUM 컬럼이 있다면 팀 스키마에 맞춰 별도 전환 계획을 세운다. User.role/status와 UserAuth.provider 매핑은 이 스키마에 맞춰 VARCHAR로 명시했다.

V1과 동일한 기존 스키마임을 확인한 경우에만 담당자가 Flyway CLI에서 version 1로 baseline한 뒤 앱을 실행해 V2를 적용한다. `baseline-on-migrate`를 자동으로 켜거나 공유 DB를 초기화하지 않는다. 이전 taxi-delivery-user-backend의 DB는 별도 이력을 가지므로 이 baseline 절차의 대상이 아니다.

2026-10-15 현재 실제 팀 사용자 DB의 DDL/접속 정보는 미확보다. 신규 DB 검증과 모의 기존 사용자 DB의 baseline/V2 리허설은 수행했으며, 실제 사용자 DB의 구조 대조·복제본 리허설·적용은 자료 확보 후 별도로 확인해야 한다. 모의 JPA 스키마에서는 refresh_token.revoked의 BIT(1)와 V1 BOOLEAN(TINYINT(1)) 차이가 발견됐다. [실제 DB 조사 및 적용 절차](db-migration-existing-users.md)와 [이번 재검증 결과](revalidation/db-migration-2026-10-15.md)를 참고한다.

## PR 및 브랜치

main 기준 `codex/post-api`를 사용한다. 이전 제출 때 승인받은 브랜치명이며 현재 저장소에는 README와 PR 템플릿 외에 별도 브랜치 명명 규칙이 없다. 기존 사용자 코드를 기반으로 제출하고 이슈가 없으면 템플릿 관련 Issue에 해당 없음을 명시한다. 테스트 결과는 [verification.md](verification.md), 명세는 [post-api.md](post-api.md), [participation-api.md](participation-api.md)를 참고한다.
