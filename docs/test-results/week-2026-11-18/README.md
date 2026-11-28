# 주간 검증: DB 정합성·인증 권한·동시성 (2026-11-18)

담당: 손광빈 (DB·인증·권한·동시성). 이 문서는 **실제로 실행한 결과만 PASS**로 적는다. 테스트 케이스만 정의했거나 실행하지 않은 항목은 PASS로 적지 않고 따로 표시한다.

## 1. 실행 정보

| 항목 | 내용 |
|---|---|
| 실행일 | 2026-11-18 (UTC 13:43~13:47) |
| 기준 코드 | `main` `97b5b43` + 이 브랜치의 테스트 추가분. **운영 코드는 변경하지 않음** |
| 실행 환경 | OpenJDK 21.0.12 (컴파일 대상 Java 17), Gradle 9.7.1, Spring Boot 4.1.1, Flyway 12.4.0 |
| DB | H2(기본 테스트), 일회용 MySQL 8.4 컨테이너(Testcontainers `mysql:8.4`, 별도 컨테이너 8.4.13). 실행 후 삭제. 배포 DB(Aiven)·배포 서버(Render)에는 접속하지 않음 |
| 결과 원본 | 이 폴더의 `junit-h2/`, `junit-mysqltest/`, `junit-mysql84-container/` (Gradle JUnit XML. 실행 로그와 호스트 이름만 제거) |

```bash
./gradlew test --rerun-tasks        # H2 전체
./gradlew mysqlTest --rerun-tasks   # Testcontainers MySQL 8.4 (Docker 필요)
# 별도 MySQL 8.4 컨테이너: PARTICIPATION_TEST_DB_* / MIGRATION_REHEARSAL_* 환경변수 설정 후
./gradlew test --tests '*UserDatabaseMigrationTest' --tests '*PostExternalDatabaseTest' --tests '*ExternalDatabaseIntegrationTest' --rerun-tasks
```

## 2. 실행 결과 요약

| 실행 | DB | 실행 | PASS | FAIL | 실행 안 함 |
|---|---|---:|---:|---:|---:|
| `./gradlew test` 전체 | H2 | 242 | 242 | 0 | 135 |
| `./gradlew mysqlTest` 전체 | MySQL 8.4 | 218 | 218 | 0 | 0 |
| 외부 DB 계약·마이그레이션 | MySQL 8.4.13 | 175 | 175 | 0 | 0 |

H2의 실행 안 함 135건은 외부 DB나 프론트 소스가 필요한 테스트다. 그중 외부 DB 계약·마이그레이션은 세 번째 줄에서 MySQL 8.4로 따로 실행했다. 프론트 소스가 필요한 선택 테스트 1건은 실행하지 않았다.

### 이번에 추가한 테스트 (22건)

| 테스트 | 케이스 작성 | 코드 구현 | H2 실행 | MySQL 8.4 실행 |
|---|---|---|---|---|
| `IntegrityAccessContract` 9건 (`IntegrityAccessIntegrationTest`, `IntegrityAccessMySqlIT`) | O | O | 9 PASS | 9 PASS |
| `AuthContract` `LOGIN07`, `LOGIN08` 2건 | O | O | 2 PASS | 2 PASS |
| `RecruitmentConsistencyMySqlIT` 11건: 기존 `RecruitmentConsistencyTest`를 MySQL 8.4에서 그대로 실행 (기존 파일 수정 없음) | 기존 | 기존 상속 | (기존 11 PASS) | 11 PASS |

## 3. 할당 항목별 결과

열 의미: **케이스** = 무엇을 검증할지 정의했는가, **코드** = 테스트 코드가 있는가, **실행** = 이번에 실제로 실행한 결과.

### ① DB 연결 및 데이터 정합성

| 항목 | 케이스 | 코드 | 실행 결과 | 근거 테스트 |
|---|---|---|---|---|
| MySQL DB 연결 상태 | O | O | PASS (MySQL 8.4) | 모든 `*MySqlIT`: 빈 MySQL 8.4에 Flyway V1→V2 적용·검증, Hibernate validate 후 실행 |
| 기존 사용자 데이터 최종 검증 | O | - | **대상 없음 (실행 안 함, PASS 아님)** | 보존할 기존 사용자 DB가 없어 종료된 항목 (PR #22, #24). 절차는 모의 DB `UserDatabaseMigrationTest` 7건 PASS |
| DB 마이그레이션 결과·데이터 조회 상태 | O | O | PASS (H2, MySQL 8.4) | `UserDatabaseMigrationTest` 7건, `INT03`(동시 요청 뒤 상세·목록·내 참여 내역·내 작성 글이 DB 행과 일치), `INT04`(검색어 `%` `_` `\` `'`가 전체 조회로 번지지 않음), `RecruitmentConsistencyMySqlIT`(검색·목록·상세) |
| 모집글·참여 관계 및 정합성 | O | O | PASS (H2, MySQL 8.4) | `INT01`·`INT02` 뒤 DB 규칙 8개 검사(정원 초과, FULL/OPEN과 인원 불일치, 취소된 모집의 참여 중 기록, 작성자 자기 참여, 중복 기록, 상태·취소 시각 불일치, 모집·사용자 없는 기록). `INT00`: 일부러 깨뜨린 데이터를 검사가 잡아냄 |
| 중복·누락 데이터 및 DB 조회 오류 수정 | O | O | **수정할 결함 없음** | 위 검사에서 발견되지 않아 운영 코드를 바꾸지 않음 |

### ② 인증 및 권한 검증

| 항목 | 케이스 | 코드 | 실행 결과 | 근거 테스트 |
|---|---|---|---|---|
| 로그인·세션 인증 예외 추가 검증 | O | O | PASS (H2, MySQL 8.4) | 신규 `LOGIN07`(비밀번호 정보가 없는 계정 401, 500 아님), `LOGIN08`(잘못된 JSON·타입 400, 5,000자 입력 401, 앞뒤 공백 허용). 기존 `LOGIN01~05`, `LocalSessionIntegrationTest`, `SessionSecurityHttpTest`(정지·탈퇴 계정 로그인 차단 포함) |
| 권한에 따른 API 접근 제어 | O | O | PASS (H2, MySQL 8.4) | `ACC01`(마감·일정 취소는 작성자만: 비로그인 401, 다른 학생·참가자·관리자 403, CSRF 없으면 403, 거절 시 상태 변화 없음). 기존 `ROLE01`, `AUTH004_*` |
| 비정상 접근·권한 없는 데이터 조회 차단 | O | O | PASS (H2, MySQL 8.4) | `ACC03`(내 작성 글·내 참여 내역에 다른 사람 데이터 없음), `ACC04`(로그인 중 정지·탈퇴된 계정은 API 6개 모두 401). 기존 `HTTP_bodyUserIdCannotImpersonateAnyone`, `HTTP_untrustedIdentityHeaderIsIgnored` |
| 참가자 명단·외부 소통 링크 서버 측 권한 | O | O | PASS (H2, MySQL 8.4) | `ACC02`(명단은 작성자·현재 참가자만, 거절 응답에 이름 없음. 링크는 작성자·현재 참가자만, 공개 목록·취소자·외부인·관리자에게 없음, 일정 취소 후 모두에게 없음). 기존 `DISC004_*`, `linkAndMembersStayPrivate` |

참고: 현재 참가자의 "내 참여 내역"에는 소통 링크가 포함된다. 상세 화면과 같은 기준(작성자·현재 참가자만)이라 의도된 동작으로 판단했다.

### ③ 트랜잭션 및 동시성 제어

| 항목 | 케이스 | 코드 | 실행 결과 | 근거 테스트 |
|---|---|---|---|---|
| 마지막 자리 동시 신청 시 정원 초과 방지 | O | O | PASS (H2, MySQL 8.4) | 기존 `JOIN002_concurrentLastSeatNeverExceedsCapacity`, `TXN_waitingJoinSeesCommittedFullStateAtLastSeat`. 신규 `INT01`(학생 6명이 정원 2·3·4인 모집 4개에 동시 신청 → 정원 초과 0건) |
| 동일 사용자 중복·연속 요청 방지 | O | O | PASS (H2, MySQL 8.4) | 기존 `JOIN003_concurrentDuplicateHasOneLogicalParticipation`, `REV_TXN_concurrentRejoins*`, `repeatedJoinTapsCountOnce`(이번에 MySQL에서도 실행). 신규 `INT02`(같은 사용자의 참여·취소 12건 동시 → 기록 1건 이하) |
| 참여 취소와 모집 마감 동시 발생 | O | O | PASS (H2, MySQL 8.4) | `memberCancelRacingAuthorCloseNeverCountsTwice`, `joinRacingAuthorCloseEndsInOneConsistentState`(이번에 MySQL에서도 실행), 기존 `POST006_joinRacingRecruitmentCancelLeavesNoJoinedMembers`. 신규 `INT01` 2라운드(참여·취소·재참여와 마감·일정 취소 동시 → 규칙 위반 0건, 예상 못 한 오류 0건) |
| 트랜잭션 처리 및 예외 시 롤백 | O | O | PASS (H2, MySQL 8.4) | 기존 `TXN_databaseFailureAfterBothTablesWereFlushedRollsBackBoth`(4건), `NFR_TXN_constraintFailureRollsBackParticipationAndFullTransition`, `TXN_failedRejoinRestoresCancelledRecordAndOpenPost`, 인증 `JOIN02`(가입 중간 실패 시 세 테이블 롤백) |

## 4. 실행하지 않은 항목 (PASS 아님)

| 항목 | 이유 |
|---|---|
| 기존 사용자 데이터 최종 검증 | 보존할 기존 사용자 DB가 없어 대상 없음 |
| 배포 서버(Render)·배포 DB(Aiven)에서의 확인 | 작업 환경에서 접속할 수 없고, 팀 규칙상 배포 DB 직접 연결 금지 |
| 실제 SMTP 메일 발송·수신 | 테스트에서 메일 발송은 대체 처리. 배포 환경 메일 설정 여부 미확인 |
| GitHub CI에서의 MySQL 테스트 | CI는 기본 `test`(H2)만 실행. MySQL 결과는 위 일회용 컨테이너 실행 결과 |
| 열린 PR(#27, #29, #39, #40, #43)과 함께 병합한 상태의 테스트 | 각 PR과 함께 병합해도 텍스트 충돌이 없음은 확인했으나, 합친 상태로 테스트를 실행하지는 않음 |

## 5. 다른 PR과의 관계

- PR #40도 `AuthContract.java`에 `LOGIN06`(정지 계정 로그인)을 추가한다. 이름이 겹치지 않도록 이 브랜치의 테스트는 `LOGIN07`, `LOGIN08`로 두고 파일 끝쪽에 배치했다. PR #40과 함께 병합해도 충돌하지 않음을 확인했다.
- 정지·탈퇴 계정 로그인 검증은 PR #40의 `LOGIN06`과 기존 `SessionSecurityHttpTest`가 다루므로 이 브랜치에서는 중복 추가하지 않았다.
- `RecruitmentConsistencyMySqlIT`는 `RecruitmentConsistencyTest`를 상속한다. PR #27·#29에서 그 테스트가 바뀌면 바뀐 테스트가 MySQL 8.4에서도 함께 실행된다.
