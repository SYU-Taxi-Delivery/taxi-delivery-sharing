# MySQL 8.4 CHECK 제약 위반 예외 분류 수정

작성일: 2026-11-09. 기준: `main` b79ca2a. 작업 브랜치: `fix/mysql-check-constraint-translation`.

## 문제

`./gradlew mysqlTest`(Testcontainers MySQL 8.4)에서 `ParticipationMySqlIT` 72건 중 6건이 실패했다. 수정 전 `main`에서도 같은 6건이 실패했다.

- `TXN_databaseForeignKeyAndStateCheckRejectInvalidDirectWrites`
- `TXN_databaseFailureAfterBothTablesWereFlushedRollsBackBoth` (join, cancel, cancelRecruitment, remove)
- `TXN_failedRejoinRestoresCancelledRecordAndOpenPost`

세 테스트 모두 `JdbcTemplate`로 DB CHECK 제약을 일부러 위반시키고 `DataIntegrityViolationException`을 기대한다. MySQL 8.0.16 이상은 CHECK 위반을 오류 3819, SQLState `HY000`으로 보낸다. Spring JDBC 7.0.9의 기본 MySQL 오류 코드 표에는 3819가 없고 SQLState도 무결성 위반 계열(23xxx)이 아니어서 `UncategorizedSQLException`으로 분류됐다. MariaDB는 같은 위반을 SQLState `23000`으로 보내므로 기존 MariaDB 검증에서는 드러나지 않았다.

수정 전 MySQL 8.4에서 새 테스트 `ConstraintTranslationMySqlIT`로 경로를 나눠 확인했다.

| 경로 | 수정 전 결과 |
|---|---|
| `JdbcTemplate` 직접 실행 | `UncategorizedSQLException` (결함) |
| JPA `saveAndFlush` (앱 서비스가 쓰는 경로) | `DataIntegrityViolationException` (Hibernate MySQLDialect가 이미 처리) |

앱 운영 코드에는 `JdbcTemplate` 사용처가 없어 실제 API 동작에는 영향이 없었다. 테스트 계약과 이후 `JdbcTemplate`를 쓰는 코드가 MySQL에서 다르게 동작하는 문제다.

## 수정

`src/main/resources/sql-error-codes.xml`을 추가했다. Spring이 문서화한 확장 방식으로, 클래스패스 루트의 이 파일은 기본 표의 같은 이름 항목(`MySQL`)만 덮어쓴다. 기본값을 그대로 복사하고 무결성 위반 코드에 3819(MySQL CHECK)와 4025(MariaDB CHECK)만 추가했다. H2 등 다른 DB 항목은 기본값을 쓴다.

이 파일이 있으면 Spring은 `JdbcTemplate`에 오류 코드 기반 분류기를 사용한다. 표에 없는 코드는 기존과 같이 SQLException 종류·SQLState 기반 분류로 넘어간다.

## 실행한 테스트

| 실행 | 환경 | 결과 |
|---|---|---|
| `mysqlTest --tests '*ConstraintTranslationMySqlIT'` (수정 전) | Testcontainers MySQL 8.4 | 3건 중 `JdbcTemplate` 1건 실패, JPA·기존 코드 2건 통과 |
| `mysqlTest --tests '*ConstraintTranslationMySqlIT' --tests '*ParticipationMySqlIT' --tests '*PostMySqlIT'` (수정 후) | Testcontainers MySQL 8.4 | 3 + 72 + 72 = 147건 통과 |
| `test` (기본) | H2 | 167건 실행, 실패 0 (외부 DB 조건부 123건 건너뜀, 수정 전 `main`과 같은 수) |
| `test --tests '*PostExternalDatabaseTest' --tests '*ExternalDatabaseIntegrationTest'` | 별도 MariaDB 11.4.13 컨테이너 | 72 + 72 = 144건 통과 |

`ConstraintTranslationMySqlIT`는 CHECK 위반 후 값이 바뀌지 않았는지, 중복 키(1062)는 여전히 `DuplicateKeyException`, 외래 키 위반(1452)은 `DataIntegrityViolationException`인지도 확인한다.

## 범위

- 테스트 계약(`ParticipationContract`), 서비스, 예외 처리기는 바꾸지 않았다. PR #16이 같은 파일을 수정 중이어서 충돌을 피했다.
- 실제 팀 DB와 GitHub CI의 MySQL 실행은 확인하지 않았다. CI는 기본 `test`만 실행한다.
