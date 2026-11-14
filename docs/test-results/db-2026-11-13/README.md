# MySQL 8.4 DB 검증 재실행 결과 (2026-11-13)

팀원이 공유한 DB 검증 371건(main `0de2f2b` 기준)을 같은 조건으로 별도 환경에서 다시 실행해 결과를 확인했다. 실제로 실행한 결과만 PASS로 적는다.

## 1. 실행 정보

| 항목 | 내용 |
|---|---|
| 실행일 | 2026-11-13 (UTC 17:51~17:55) |
| 테스트 대상 코드 | `main` `0de2f2b` (PR #21 병합 후). 이 문서를 추가한 브랜치는 문서·결과 파일만 바꾼다 |
| 실행 환경 | OpenJDK 21.0.12 (컴파일 대상 Java 17), Gradle 9.7.1, Spring Boot 4.1.1, Flyway 12.4.0, MySQL Connector/J 9.7.0 |
| DB | 실행 후 삭제한 일회용 MySQL 8.4 Docker 컨테이너만 사용 (별도 컨테이너는 8.4.13, `lower_case_table_names=0`, `utf8mb4_0900_ai_ci`). 배포 DB(Aiven)와 팀원 PC의 DB에는 접속하지 않음 |
| 결과 원본 | 이 폴더의 `junit-mysql84-container/`, `junit-mysqltest/` (Gradle JUnit XML, 실행 로그와 호스트 이름만 제거) |

```bash
# ① 직접 띄운 MySQL 8.4 컨테이너 (127.0.0.1 전용, 비밀번호는 일회용)
PARTICIPATION_TEST_DB_URL='jdbc:mysql://127.0.0.1:<포트>/participation_test?allowPublicKeyRetrieval=true&useSSL=false' \
PARTICIPATION_TEST_DB_USERNAME=root PARTICIPATION_TEST_DB_PASSWORD=<일회용> \
MIGRATION_REHEARSAL_ADMIN_URL='jdbc:mysql://127.0.0.1:<포트>/mysql?allowPublicKeyRetrieval=true&useSSL=false' \
MIGRATION_REHEARSAL_USERNAME=root MIGRATION_REHEARSAL_PASSWORD=<일회용> \
./gradlew test --tests '*UserDatabaseMigrationTest' --tests '*PostExternalDatabaseTest' --tests '*ExternalDatabaseIntegrationTest' --rerun-tasks

# ② Testcontainers가 MySQL 8.4 컨테이너를 띄웠다 지움 (Docker 필요)
./gradlew mysqlTest --rerun-tasks
```

## 2. 결과

| 실행 | 테스트 | 검증 내용 | 실행 | PASS | FAIL |
|---|---|---|---:|---:|---:|
| ① | `UserDatabaseMigrationTest` | 새 DB에 V1→V2 적용, 데이터가 있는 DB에 baseline 후 V2(데이터·ID 보존), 구조가 다르면 baseline 거부 | 7 | 7 | 0 |
| ① | `PostExternalDatabaseTest` | 모집글 API 계약 | 83 | 83 | 0 |
| ① | `ExternalDatabaseIntegrationTest` | 참여 API 계약, 동시성 | 85 | 85 | 0 |
| ② | `AuthMySqlIT` | 회원가입, 로그인·잠금, 비밀번호 재설정, 권한, 동시 요청 | 25 | 25 | 0 |
| ② | `PostMySqlIT` | 모집글 | 83 | 83 | 0 |
| ② | `ParticipationMySqlIT` | 참여 | 85 | 85 | 0 |
| ② | `ConstraintTranslationMySqlIT` | CHECK 제약 위반 오류 분류 | 3 | 3 | 0 |
| | **합계** | | **371** | **371** | **0** |

같은 코드로 기본 `./gradlew test`(H2)도 실행했다. 219건 실행, 모두 PASS다. 외부 DB나 프론트 소스가 필요한 135건은 이 실행에서 건너뛰었으므로 PASS에 포함하지 않았다.

팀원이 공유한 371건(7 / 83 / 85 / 25 / 83 / 85 / 3)과 테스트 구성과 결과가 같다. 팀원 실행 결과는 공유받은 내용이며, 이 폴더의 원본은 위 별도 환경에서 실행한 결과다.

## 3. 실제 팀 사용자 DB 구조 대조: 대상 없음으로 종료

- 이전 기록에서 미실행으로 남아 있던 "실제 팀 사용자 DB 구조 대조·복제본 리허설"은 수행하지 않고 종료한다.
- 근거: 보존해야 할 데이터가 있는 기존 팀 사용자 DB가 없다는 확인을 받았다. 배포 DB(MySQL 8.4)는 2026-11-05 빈 DB에 Flyway V1→V2로 테이블을 만들었다. PR #17에 있던 `localhost:3306`은 개인 PC의 개발용 DB 접속 기록이라 대조 대상이 아니다.
- 이 항목은 실행하지 않았으므로 PASS가 아니라 **대상 없음**으로 표시한다. 배포 DB의 Flyway 이력은 이 환경에서 직접 확인하지 않았다.
- 데이터가 있는 기존 사용자 DB가 새로 생기면 [기존 사용자 DB 적용 절차](../../db-migration-existing-users.md)와 `scripts/inspect-user-schema.sql`로 대조한다. 위 `UserDatabaseMigrationTest`는 그 절차를 모의 DB로 검증한다.
