# 인증·권한·사용자 DB 테스트 실행 결과 (2026-11-12)

이 문서는 테스트를 **실제로 실행한 결과**만 PASS로 적는다. 테스트 케이스만 정의했거나 코드는 있지만 실행하지 않은 항목은 `미실행`으로 따로 적는다.

## 1. 실행 정보

| 항목 | 내용 |
|---|---|
| 실행일 | 2026-11-12 (UTC 01:27~01:30) |
| 테스트 대상 코드 | 브랜치 `fix/auth-week5-verification`, 커밋 `cd50fcc` (이 문서를 추가한 커밋은 문서·결과 파일만 바꾼다) |
| 기준 브랜치 | `main` `b79ca2a` |
| 실행 환경 | OpenJDK 21.0.12 (컴파일 대상 Java 17), Gradle 9.7.1, Spring Boot 4.1.1, Hibernate 7.4.5, Flyway 12.4.0, MySQL Connector/J 9.7.0, Testcontainers 2.0.5 |
| H2 | 인메모리 H2, `MODE=MySQL` (기본 `test` 프로필) |
| MySQL | MySQL 8.4 Docker 컨테이너 (Testcontainers `mysql:8.4`, 별도 컨테이너는 8.4.13, `lower_case_table_names=0`, `utf8mb4_0900_ai_ci`). 실행 후 삭제한 일회용 DB이며 팀 DB나 실제 사용자 데이터에는 접속하지 않음 |
| 결과 원본 | 이 폴더의 `junit-h2/`, `junit-mysql84/` (Gradle JUnit XML. 실행 로그와 호스트 이름만 제거) |

### 실행 명령

```bash
# 1) 기본 테스트 전체 (H2). 캐시를 쓰지 않고 다시 실행
./gradlew test --rerun-tasks

# 2) 인증 계약 테스트를 MySQL 8.4에서 실행 (Docker 필요)
./gradlew mysqlTest --tests '*AuthMySqlIT' --rerun-tasks

# 3) 사용자 DB 마이그레이션 리허설을 별도 MySQL 8.4 컨테이너에서 실행
MIGRATION_REHEARSAL_ADMIN_URL='jdbc:mysql://127.0.0.1:<포트>/mysql?allowPublicKeyRetrieval=true&useSSL=false' \
MIGRATION_REHEARSAL_USERNAME=root MIGRATION_REHEARSAL_PASSWORD=<일회용 비밀번호> \
./gradlew test --tests '*UserDatabaseMigrationTest' --rerun-tasks
```

## 2. 실행 결과 요약

| 실행 | DB | 실행 | PASS | FAIL | 실행 안 함(skip) |
|---|---|---:|---:|---:|---:|
| 1) `./gradlew test` 전체 | H2 | 192 | 192 | 0 | 123 |
| 1) 중 인증·권한 관련 6개 클래스 | H2 | 48 | 48 | 0 | 0 |
| 2) `AuthMySqlIT` | MySQL 8.4 | 25 | 25 | 0 | 0 |
| 3) `UserDatabaseMigrationTest` | MySQL 8.4.13 | 7 | 7 | 0 | 0 |

1)의 skip 123건은 외부 DB 접속 정보가 있어야 실행되는 테스트다. 모집글·참여 외부 DB 계약(`PostExternalDatabaseTest` 54건, `ExternalDatabaseIntegrationTest` 63건)과 `UserDatabaseMigrationTest` 6건이다(건너뛴 매개변수 테스트는 1건으로 집계). 실행하지 않았으므로 PASS에 포함하지 않았다. `UserDatabaseMigrationTest`는 3)에서 MySQL 8.4로 따로 실행했고, 이때는 매개변수 2건이 각각 집계돼 7건이다. 모집글·참여 외부 DB 계약은 인증 범위 밖이라 이번에 실행하지 않았다.

## 3. 작업지시서 항목별 결과

| 번호 | 항목 | 결과 | 근거 |
|---|---|---|---|
| 3 | 기존 사용자 DB 구조 및 마이그레이션 검토 결과 정리 | 문서 정리 완료. 모의 기존 DB 리허설 실행 PASS. **실제 팀 DB 대조는 미실행** | [기존 사용자 DB 적용 절차](../../db-migration-existing-users.md), 표 4-7, 5절 |
| 4 | MySQL 8.4 연결 상태 및 데이터 정합성 최종 검증 | PASS (MySQL 8.4 실행) | `AuthMySqlIT` 25건, `UserDatabaseMigrationTest` 7건 |
| 5 | USERS·USER_AUTH·LOCAL_CREDENTIAL 관계 및 트랜잭션 | PASS (H2, MySQL 8.4) | 표 4-2, 4-7 |
| 6 | 학교 이메일 인증·자체 로그인·비밀번호 재설정 예외 | PASS (H2, MySQL 8.4) | 표 4-1, 4-3, 4-4 |
| 7 | 로그인 실패 횟수, 5회째 잠금, 30분 만료 | PASS (H2, MySQL 8.4) | 표 4-3 |
| 8 | 인증·권한 테스트 코드 및 실행 결과 정리 | 이 문서와 결과 원본 | 표 4, 6 |
| 9 | 트랜잭션 동시성 제어 및 예외 상황 | PASS (H2, MySQL 8.4) | 표 4-6 |

## 4. 테스트 케이스별 결과

표의 열 의미:
- **케이스 작성**: 무엇을 검증할지 정의했는가
- **코드 구현**: 실제 테스트 코드가 있는가
- **H2 / MySQL 8.4**: 이번에 실제로 실행한 결과. 해당 DB에서 실행하지 않았으면 `미실행`

`AuthContract`는 `src/test/java/com/school/party/domain/user/AuthContract.java`이며, H2에서는 `AuthIntegrationTest`, MySQL 8.4에서는 `AuthMySqlIT`가 같은 테스트를 실행한다.

### 4-1. 학교 이메일 인증

| TC | 검증 내용 | 테스트 코드 | 케이스 작성 | 코드 구현 | H2 | MySQL 8.4 |
|---|---|---|---|---|---|---|
| EMAIL01 | 학교 도메인(`syuin.ac.kr`, `syu.ac.kr`)만 코드 발송. 타 도메인·하위 도메인·`@` 2개·빈 값·알 수 없는 필드는 400, 메일 미발송 | `AuthContract.EMAIL01_…` | O | O | PASS | PASS |
| EMAIL02 | 공백·대소문자가 달라도 같은 계정으로 처리. 대소문자만 다른 주소로 재가입 시 409 | `AuthContract.EMAIL02_…` | O | O | PASS | PASS |
| EMAIL03 | 인증 코드 5분 1초 뒤 만료, 가입 거절, USERS 0건 | `AuthContract.EMAIL03_…` | O | O | PASS | PASS |
| EMAIL04 | 틀린 코드 4회는 400, 5회째 429 후 코드 폐기, 이후 올바른 코드도 거절 | `AuthContract.EMAIL04_…` | O | O | PASS | PASS |
| EMAIL05 | 60초 안 재발송 429, 60초 뒤 재발송 가능, 새 코드만 유효 | `AuthContract.EMAIL05_…` | O | O | PASS | PASS |
| EMAIL06 | 메일 발송 실패 시 503, 코드 폐기 후 즉시 재요청 가능 | `AuthContract.EMAIL06_…` | O | O | PASS | PASS |
| EMAIL07 | 인증 완료 상태 30분 뒤 만료, 다시 인증 후 1회 가입 | `AuthContract.EMAIL07_…` | O | O | PASS | PASS |
| TC-AUTH-01·02 | 틀린 인증 번호 거절 (단위 테스트) | `AuthAndUserTest.verifySchoolEmailTest` | O | O | PASS | 미실행 (DB 미사용 단위 테스트) |
| TC-AUTH-03 | 허용되지 않은 도메인 거절, 메일 미발송 (단위 테스트) | `AuthAndUserTest.invalidEmailDomainTest` | O | O | PASS | 미실행 (DB 미사용) |
| TC-USER-01·02 | 미인증 이메일 가입 거절 (단위 테스트) | `AuthAndUserTest.registerWithUnverifiedEmailTest` | O | O | PASS | 미실행 (DB 미사용) |

### 4-2. 회원가입과 세 테이블 관계·트랜잭션

| TC | 검증 내용 | 테스트 코드 | 케이스 작성 | 코드 구현 | H2 | MySQL 8.4 |
|---|---|---|---|---|---|---|
| JOIN01 | 가입 후 USERS·USER_AUTH·LOCAL_CREDENTIAL이 같은 user_id로 1행씩 연결, 역할 STUDENT, 비밀번호는 BCrypt 해시, 그 계정으로 로그인 | `AuthContract.JOIN01_…` | O | O | PASS | PASS |
| JOIN02 | LOCAL_CREDENTIAL 저장 실패 시 세 테이블 모두 0행(롤백), 같은 인증으로 재시도 성공 | `AuthContract.JOIN02_…` | O | O | PASS | PASS |
| JOIN03 | 학번 중복 409, 세 테이블 각 1행 유지 | `AuthContract.JOIN03_…` | O | O | PASS | PASS |
| JOIN04 | 요청에 `role: ADMIN`을 넣으면 400, 짧은 비밀번호·72바이트 초과 비밀번호 400, 가입 시 역할은 STUDENT | `AuthContract.JOIN04_…` | O | O | PASS | PASS |
| 통합 흐름 | 이메일 인증 → 가입 → 로그인 → 같은 user_id로 모집글 작성 | `LocalSessionIntegrationTest.schoolRegistrationLoginAndPostCreationShareTeamUserId` | O | O | PASS | 미실행 (H2 전용 테스트) |

### 4-3. 자체 로그인, 5회 잠금, 30분 만료

| TC | 검증 내용 | 테스트 코드 | 케이스 작성 | 코드 구현 | H2 | MySQL 8.4 |
|---|---|---|---|---|---|---|
| LOGIN01 | 실패 1~4회는 잠금 없음, 정확히 5회째 잠금. 잠금 중 올바른 비밀번호 401·횟수 변화 없음, 29분 59초 뒤에도 401, 30분 뒤 로그인 성공·횟수 0 | `AuthContract.LOGIN01_…` | O | O | PASS | PASS |
| LOGIN02 | 잠금이 풀린 뒤 1회 오입력은 횟수 1, 다시 잠기지 않음 | `AuthContract.LOGIN02_…` | O | O | PASS | PASS |
| LOGIN03 | 성공 시 실패 횟수 0, 마지막 로그인 시각 기록, 이메일로도 로그인, 존재하지 않는 사용자 401, 빈 비밀번호 400 | `AuthContract.LOGIN03_…` | O | O | PASS | PASS |
| 기존 | 실패 횟수 1~5 누적 저장, 5회 후 올바른 비밀번호도 401 | `LocalSessionIntegrationTest.wrongPasswordsPersistFailureCountAndLockAfterFiveAttempts` | O | O | PASS | 미실행 (H2 전용) |
| 기존 | 정지(SUSPENDED) 계정 로그인 401 | `LocalSessionIntegrationTest.inactiveAccountCannotEstablishSession` | O | O | PASS | 미실행 (H2 전용) |
| 기존 | 빈 입력 400, 요청 본문 `userId`·`X-User-Id` 헤더로 다른 사용자 지정 불가 | `LocalSessionIntegrationTest.blankCredentialsAndInjectedUserIdAreRejected` | O | O | PASS | 미실행 (H2 전용) |
| 기존 | 로그인 시 세션 ID 변경, 로그아웃 후 세션 무효 | `LocalSessionIntegrationTest.sessionIdChangesOnLoginAndLogoutInvalidatesSession` | O | O | PASS | 미실행 (H2 전용) |
| 기존 | 로그인·변경 요청에 CSRF 토큰 필수 | `LocalSessionIntegrationTest.csrfIsRequiredForLoginAndAnonymousCsrfEndpointWorks` | O | O | PASS | 미실행 (H2 전용) |
| 기존 (실제 HTTP) | 세션 고정 방지, CSRF 토큰 교체, 로그아웃, 비활성 계정 차단 등 9건 | `SessionSecurityHttpTest` | O | O | PASS 9건 | 미실행 (H2 전용) |
| 기존 (실제 HTTP) | demo 프로필에서만 Basic 인증 허용 등 3건 | `DemoBasicSecurityHttpTest` | O | O | PASS 3건 | 미실행 (H2 전용) |

### 4-4. 비밀번호 재설정

| TC | 검증 내용 | 테스트 코드 | 케이스 작성 | 코드 구현 | H2 | MySQL 8.4 |
|---|---|---|---|---|---|---|
| RESET01 | 잠긴 계정도 메일 코드로 재설정 가능. 실패 횟수 0, 잠금 해제, `password_updated_at` 갱신, 예전 비밀번호 401, 새 비밀번호 성공, 같은 코드 재사용 400 | `AuthContract.RESET01_…` | O | O | PASS | PASS |
| RESET02 | 없는 이메일·정지 계정도 같은 200 응답, 메일 미발송. 타 도메인 400, 발급되지 않은 코드 400 | `AuthContract.RESET02_…` | O | O | PASS | PASS |
| RESET03 | 약한 새 비밀번호 400, 틀린 코드 400, 5분 지난 코드 400. 모두 기존 비밀번호 유지 | `AuthContract.RESET03_…` | O | O | PASS | PASS |
| RESET04 | 재설정 코드로 가입 인증 불가 | `AuthContract.RESET04_…` | O | O | PASS | PASS |

### 4-5. 학생·관리자 권한

| TC | 검증 내용 | 테스트 코드 | 케이스 작성 | 코드 구현 | H2 | MySQL 8.4 |
|---|---|---|---|---|---|---|
| ROLE01 | `/api/admin/**`: 비로그인 401, 학생 403, 관리자 200. 일반 API는 둘 다 200. 모집글 관리자 삭제는 학생 거절·관리자 성공 | `AuthContract.ROLE01_…` | O | O | PASS | PASS |
| 기존 | 로그인 중 계정이 정지되면 API 접근 401 | `LocalSessionIntegrationTest.existingSessionCannotAccessApisAfterAccountIsSuspended` | O | O | PASS | 미실행 (H2 전용) |

`/api/admin/role-probe`는 권한 규칙 확인용 테스트 전용 경로이며 실행 파일에는 들어가지 않는다. 현재 운영 코드에는 관리자 전용 HTTP API가 아직 없다.

### 4-6. 동시 요청

| TC | 검증 내용 | 테스트 코드 | 케이스 작성 | 코드 구현 | H2 | MySQL 8.4 |
|---|---|---|---|---|---|---|
| LOGIN04 | 같은 계정에 틀린 비밀번호 4건 동시 → 실패 횟수 정확히 4, 잠금 없음. 12건 동시 → 정확히 5에서 잠금, 이후 올바른 비밀번호도 거절 | `AuthContract.LOGIN04_…` | O | O | PASS | PASS |
| LOGIN05 | 실패 4회 상태에서 올바른 비밀번호 1건 + 틀린 비밀번호 3건 동시 → 실패 횟수 5 초과 없음 | `AuthContract.LOGIN05_…` | O | O | PASS | PASS |
| RACE01 | 인증 1번으로 가입 6건 동시 → 계정 1개, 세 테이블 각 1행, 나머지 4xx | `AuthContract.RACE01_…` | O | O | PASS | PASS |
| RACE02 | 다른 이메일·같은 학번으로 6건 동시 → 1건 성공, 나머지 409, 세 테이블 각 1행 | `AuthContract.RACE02_…` | O | O | PASS | PASS |
| RACE03 | 틀린 인증 코드 12건 동시 → 불일치 응답 정확히 4건, 5번째에서 코드 폐기 | `AuthContract.RACE03_…` | O | O | PASS | PASS |

### 4-7. DB 정합성과 마이그레이션

| TC | 검증 내용 | 테스트 코드 | 케이스 작성 | 코드 구현 | H2 | MySQL 8.4 |
|---|---|---|---|---|---|---|
| DB01 | 없는 user_id로 LOCAL_CREDENTIAL·USER_AUTH 저장 거절, 인증 정보가 있는 사용자 삭제 거절 | `AuthContract.DB01_…` | O | O | PASS | PASS |
| Flyway·validate | 빈 MySQL 8.4에 V1→V2 적용, 이력 검증, 모든 엔티티 Hibernate validate | `AuthMySqlIT` 컨텍스트 시작 | O | O | 미실행 (H2는 Flyway 미사용) | PASS |
| MIG-1 | 신규 DB: V1·V2 SQL 실행, 이력 `1:SQL`·`2:SQL`, 재실행 0건 | `UserDatabaseMigrationTest.newDatabaseExecutesBothSqlMigrationsWithoutBaseline` | O | O | 미실행 (skip) | PASS |
| MIG-2 | 모의 기존 DB: 구조 대조·정합화 → baseline 1 → V2. 사용자·인증 데이터, ID, AUTO_INCREMENT 보존 | `UserDatabaseMigrationTest.existingJpaUserSchemaCanBeComparedBaselinedAndMigratedWithoutLosingData` | O | O | 미실행 (skip) | PASS |
| MIG-3 | 데이터가 있는 DB에 baseline 없이 migrate하면 실패, 기존 행 유지 | `UserDatabaseMigrationTest.existingNonemptySchemaIsNotAutomaticallyBaselined` | O | O | 미실행 (skip) | PASS |
| MIG-4 | native ENUM·컬럼 길이 차이 탐지 (2건) | `UserDatabaseMigrationTest.schemaComparisonDetectsLegacyEnumAndColumnLengthDifferences` | O | O | 미실행 (skip) | PASS 2건 |
| MIG-5 | JPA 생성 BIT(1)와 V1 BOOLEAN 차이 탐지 | `UserDatabaseMigrationTest.independentlyGeneratedJpaSchemaExposesBooleanStorageDifference` | O | O | 미실행 (skip) | PASS |
| MIG-6 | 테이블이 빠져도 baseline은 통과하지만 Hibernate validate가 잡아냄 | `UserDatabaseMigrationTest.baselineDoesNotProveThatAnIncompleteSchemaMatchesV1` | O | O | 미실행 (skip) | PASS |
| 앱 시작 | 애플리케이션 컨텍스트 로드 | `TaxiDeliverySharingApplicationTests.contextLoads` | O | O | PASS | 미실행 |

## 5. 실행하지 않은 항목 (PASS 아님)

> 2026-11-13 갱신: 아래 첫 두 항목(실제 팀 사용자 DB 구조 대조, 기존 데이터의 대문자 이메일 확인)은 보존해야 할 기존 팀 사용자 DB가 없어 **대상 없음으로 종료**했다. 실행하지 않았으므로 PASS가 아니다. 근거는 [DB 검증 재실행 결과 2026-11-13](../db-2026-11-13/README.md) 3절에 있다. 이 표의 나머지 내용은 2026-11-12 당시 기록이다.

| 항목 | 상태 | 이유 |
|---|---|---|
| 실제 팀 사용자 DB 구조 대조·복제본 리허설 | 케이스 작성 O, 코드 구현 X, 실행 X | 실제 DB DDL·조사 결과 미확보. 절차와 조사 SQL(`scripts/inspect-user-schema.sql`)만 준비 |
| 기존 데이터의 대문자 이메일 확인 | 케이스 작성 O, 실행 X | 실제 DB 필요. 확인 SQL은 [적용 절차](../../db-migration-existing-users.md)에 있음 |
| 실제 SMTP 메일 발송 | 케이스 작성 O, 실행 X | 메일 계정 미설정. 모든 테스트에서 메일 발송은 mock |
| 위 표의 H2 전용 테스트를 MySQL 8.4에서 실행 | 실행 X | 해당 테스트 클래스가 H2로만 구성됨 |
| GitHub CI에서 MySQL 테스트 실행 | 실행 X | CI(`backend-test.yml`)는 기본 `test`(H2)만 실행. MySQL 결과는 위 로컬 컨테이너 실행 결과 |
| 학교 SSO 연동 | 해당 없음 | 확정 근거가 없어 도입하지 않음 |
| 프론트엔드 연동 | 실행 X | 이번 범위의 실행 대상 아님 |

## 6. 테스트 케이스 초안(이슈 #9)과 실제 테스트 대응

| 이슈 #9 케이스 | 실제 테스트 | 실행 결과 |
|---|---|---|
| 정상적인 학교 이메일 인증 | JOIN01, EMAIL05, EMAIL07 | PASS (H2, MySQL 8.4) |
| 잘못된 인증 정보 | EMAIL04, LOGIN01, TC-AUTH-01·02 | PASS (H2, MySQL 8.4) |
| 인증되지 않은 이메일 | EMAIL03, EMAIL07, TC-USER-01·02 | PASS (H2, MySQL 8.4) |
| 인증 완료 후 User 생성 | JOIN01 | PASS (H2, MySQL 8.4) |
| 정상 로그인 | JOIN01, LOGIN03 | PASS (H2, MySQL 8.4) |
| 로그인 실패 | LOGIN01, LOGIN02 | PASS (H2, MySQL 8.4) |
| 존재하지 않는 사용자 | LOGIN03 | PASS (H2, MySQL 8.4) |
| Student 권한 검증 | ROLE01 | PASS (H2, MySQL 8.4) |
| Admin 권한 검증 | ROLE01 | PASS (H2, MySQL 8.4) |
| 인증되지 않은 사용자의 보호 API 접근 | ROLE01, `LocalSessionIntegrationTest.blankCredentialsAndInjectedUserIdAreRejected` | PASS (ROLE01은 H2·MySQL 8.4, 기존 테스트는 H2) |
| 중복 사용자 처리 | JOIN03, EMAIL02, RACE01, RACE02 | PASS (H2, MySQL 8.4) |

## 7. 관련 문서

- 수정 전 결함 재현과 수정 내용: [5주차 점검 보고](../../revalidation/auth-week5-2026-11-09.md)
- 기존 사용자 DB 구조 차이, 데이터 호환성, 적용 순서: [기존 사용자 DB 적용 절차](../../db-migration-existing-users.md)
