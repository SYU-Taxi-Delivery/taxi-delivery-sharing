# 기존 사용자 DB의 Flyway 도입 절차

작성일: 2026-10-15. 대상: taxi-delivery-sharing의 V1__team_user_schema.sql / V2__post_participation.sql.

## 현재 검증 상태

| 대상 | 상태 |
|---|---|
| 신규 전용 MariaDB | V1 SQL → V2 SQL 생성 및 전체 엔티티 validate 검증 |
| 모의 기존 사용자 DB | 현재 JPA 사용자 엔티티로 생성하고 합성 데이터를 넣은 별도 DB에서 구조 대조·정합화 → baseline 1 → V2 적용 검증 |
| 실제 팀 사용자 DB | DDL/접속 정보 미확보. 실제 구조 대조, 복제본 리허설 및 적용 미완료 |

위 결과는 서로 다른 검증이다. 모의 DB에서 통과했다는 이유로 실제 팀 DB 적용 완료 또는 즉시 적용 가능으로 보고하지 않는다. 실제 DB 버전이 MySQL이라면 같은 버전의 복제 환경에서 리허설해야 한다. 이번 실행 DB는 MariaDB 11.4.10이다.

## 1. 실제 구조를 먼저 확보

DB 담당자가 실제 사용자 DB에 읽기 전용으로 연결해 [조사 SQL](../scripts/inspect-user-schema.sql)을 실행하고 결과를 보관한다. 이 SQL은 메타데이터 SELECT만 수행하며 사용자 행/비밀번호 해시/토큰을 조회하지 않는다.

함께 확보할 자료:

- 서버 버전, DB 이름, lower_case_table_names, 문자셋/콜레이션, SQL 모드
- users / user_auth / local_credential / refresh_token의 정확한 이름과 SHOW CREATE TABLE 결과
- 기존 flyway_schema_history 존재 여부. 존재하면 버전·유형·성공 여부·체크섬 이력
- 관련 CHECK, 인덱스, FK, 트리거, 추가 테이블·컬럼 및 데이터 건수

대문자 테이블을 사용하는 서버는 조사 결과에 나온 정확한 이름으로 SHOW CREATE TABLE을 실행한다. 이미 Flyway 이력이 있다면 baseline을 먼저 실행하지 않고 기존 이력과 저장소 마이그레이션의 관계부터 검토한다.

## 2. V1과 대조

| 테이블 | V1 기준 핵심 구조 |
|---|---|
| users | signed BIGINT id AUTO_INCREMENT PK, student_no VARCHAR(20) UNIQUE, school_email VARCHAR(100) UNIQUE, name VARCHAR(50), role/status VARCHAR(20), 시간 DATETIME(6) |
| user_auth | BIGINT id PK, user_id BIGINT NOT NULL UNIQUE 및 users FK, provider VARCHAR(20), provider_user_id VARCHAR(255), UNIQUE(provider,provider_user_id), 시간 DATETIME(6) |
| local_credential | user_id BIGINT PK 및 users FK, password_hash VARCHAR(255), failed_login_count INT, nullable locked_until, password_updated_at DATETIME(6) |
| refresh_token | BIGINT id PK, user_id BIGINT 및 users FK, token_hash VARCHAR(255) UNIQUE, expires_at/created_at DATETIME(6), revoked BOOLEAN NOT NULL |

원본 SQL을 기준으로 모든 컬럼의 타입·signed/unsigned·길이·NULL 허용·기본값·자동 증가, PK/UNIQUE/FK 컬럼 순서·참조 대상·삭제/갱신 규칙, 엔진, 문자셋/콜레이션, CHECK·트리거를 확인한다. FK/인덱스 이름이 다르더라도 기능과 컬럼 구성이 같은지 판단한다.

이번 모의 DB에서 실제로 확인한 차이는 refresh_token.revoked다. 현재 JPA가 생성한 것은 BIT(1), V1의 BOOLEAN은 MariaDB에서 TINYINT(1)이었다. 또한 통합 전 사용자 엔티티에는 role/status/provider의 VARCHAR 강제 매핑이 없었다. 실제 DB가 native ENUM으로 생성됐는지는 실제 DDL 확인이 필요하다.

차이가 있으면 baseline 전에 데이터 분포·기존 코드와의 호환성·복구 방법을 확인하고 별도 정합화 절차를 검토한다. 이번 테스트는 모의 DB의 revoked 0/1을 확인하고 해당 임시 DB에서만 BOOLEAN으로 변경한 뒤 값 보존을 검증했다. 이 테스트용 변경을 실제 DB에 그대로 적용하는 절차로 사용하지 않는다. V1/V2의 기존 SQL과 체크섬은 이번 작업에서 변경하지 않았다.

## 3. 실제 DB 복제본에서 리허설

1. 사용자 DB 백업을 확보하고, 같은 DB 제품·버전·설정의 별도 환경에 복원한다. 먼저 복원이 가능한지도 확인한다.
2. 위 구조 대조와 필요한 정합화를 복제본에서 수행한다. 개인정보가 포함된 덤프는 저장소에 올리지 않는다.
3. 네 사용자 테이블의 행 수, PK/FK, 전체 필드 비교값 또는 비공개 체크섬, AUTO_INCREMENT 다음 값, 인증 상태를 기록한다.
4. Flyway 이력이 없고 V1에 해당하는 구조가 확인된 경우에만 baselineVersion=1을 명시해 baseline한다. baseline-on-migrate는 false를 유지한다.
5. baseline 후 V2를 적용한다. 예상 이력은 `1:BASELINE`, `2:SQL`이다. V1 SQL이 재실행되거나 기존 사용자 테이블이 재생성되면 안 된다.
6. 사용자 테이블 구조·데이터·자동 증가값을 전후 대조한다. 합성/허가된 검증 계정으로 기존 인증과 새 모집글·참여 FK 연결을 확인한다.
7. Hibernate ddl-auto=validate로 모든 엔티티를 검증하고, Flyway validate 및 재실행 시 추가 migration 0건을 확인한다.
8. 결과·차이 처리·백업/복구 방법을 팀 DB 담당자와 검토한 뒤 실제 적용 시점과 실행 담당자를 정한다.

baseline은 V1까지의 마이그레이션을 이미 적용된 것으로 표시하는 동작이다. 실제 스키마가 V1과 같다는 검증은 별도로 해야 한다. 이번 음성 테스트에서는 refresh_token 테이블을 제거해도 baseline·V2·Flyway validate는 통과했고 Hibernate validate에서 누락을 발견했다. [Flyway baseline 공식 문서](https://documentation.red-gate.com/flyway/reference/commands/baseline)를 참고한다.

MySQL 계열 DDL 적용 실패는 전체 변경이 자동 롤백된다고 가정하지 않는다. 실패하면 새 기능 접근을 중지하고 남은 테이블과 Flyway 이력을 확인한 뒤, 검증한 백업 복구 또는 검토된 정정 절차로 처리한다. repair는 테이블/데이터를 복원하는 작업을 대신하지 않는다. [Flyway 트랜잭션 처리 문서](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/migration-transaction-handling)를 참고한다.

## 4. 이번 모의 리허설 재실행

UserDatabaseMigrationTest는 선택 실행하는 통합 테스트다. 새 임시 스키마만 생성·정리하며 앱의 DB_URL을 사용하지 않는다. 운영 서버가 아닌 로컬 전용 검증 DB에 연결한다. 생성한 스키마는 `migration_revalidation_<무작위 ID>`이며 실행 종료 시 해당 실행이 만든 스키마만 삭제한다.

필요한 환경변수:

- MIGRATION_REHEARSAL_ADMIN_URL: 로컬 전용 서버의 `jdbc:mariadb://127.0.0.1:포트/mysql` 또는 jdbc:mysql 형식
- MIGRATION_REHEARSAL_USERNAME / MIGRATION_REHEARSAL_PASSWORD: 해당 전용 서버의 임시 스키마 생성·삭제 권한을 가진 계정. 암호는 환경변수로 주입하고 커밋하지 않는다.

```powershell
./gradlew.bat test --tests '*UserDatabaseMigrationTest' --no-daemon --console=plain
```

환경변수가 없으면 이 테스트는 건너뛴다. 일반 H2 테스트 통과를 마이그레이션 리허설 통과로 간주하지 않는다. Flyway Java API 12.4.0을 사용했으며 별도 CLI 설치나 운영 baseline은 이번 검증에서 수행하지 않았다.

## 보고 시 구분할 항목

- 신규 DB 검증: DB 제품/버전, V1/V2 SQL 적용 결과, Hibernate validate 결과
- 모의 기존 DB 검증: 모의 구조의 출처, 발견한 차이와 정합화, baseline/V2 이력, 데이터/자동 증가값 보존 결과
- 실제 사용자 DB 검증: 실제 DDL 대조 결과, 실제 복제본 리허설 결과, 담당자 확인 및 적용 여부. 현재는 모두 자료 확보 후 확인
