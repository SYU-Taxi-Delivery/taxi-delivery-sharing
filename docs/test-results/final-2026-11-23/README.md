# 최종 검증: DB 정합성·마이그레이션·인증 권한·개인정보 노출 (2026-11-23)

담당: 손광빈 (백엔드 – 인증·권한·DB). **실제로 실행한 결과만 PASS**로 적는다. 실행하지 않은 항목은 PASS로 적지 않고 따로 표시한다.

## 1. 실행 정보

| 항목 | 내용 |
|---|---|
| 실행일 | 2026-11-23 (UTC 23:37~23:41) |
| 기준 코드 | 최신 `main` `ab628eb` (PR #39·#40·#43·#45·#47·#49 반영) + 이 브랜치의 테스트 추가분. **운영 코드 변경 없음** |
| 포함한 이전 작업 | 아직 병합되지 않은 PR #44의 테스트(`IntegrityAccess*`, `RecruitmentConsistencyMySqlIT`, 로그인 예외 2건)를 최신 `main` 위에 그대로 가져와 함께 실행 |
| 실행 환경 | OpenJDK 21.0.12 (컴파일 대상 Java 17), Gradle 9.7.1, Spring Boot 4.1.1, Flyway 12.4.0 |
| DB | H2(기본 테스트), 일회용 MySQL 8.4 컨테이너(Testcontainers `mysql:8.4`, 별도 컨테이너 8.4.13). 실행 후 삭제. 배포 DB(Aiven)·배포 서버(Render)에는 접속하지 않음 |
| 결과 원본 | `junit-h2/`, `junit-mysqltest/`, `junit-mysql84-container/` (Gradle JUnit XML, 실행 로그와 호스트 이름만 제거) |

```bash
./gradlew test --rerun-tasks        # H2 전체
./gradlew mysqlTest --rerun-tasks   # Testcontainers MySQL 8.4 (Docker 필요)
# 별도 MySQL 8.4 컨테이너: PARTICIPATION_TEST_DB_* / MIGRATION_REHEARSAL_* 환경변수 설정 후
./gradlew test --tests '*UserDatabaseMigrationTest' --tests '*PostExternalDatabaseTest' --tests '*ExternalDatabaseIntegrationTest' --rerun-tasks
```

## 2. 실행 결과 요약

| 실행 | DB | 실행 | PASS | FAIL | 실행 안 함 |
|---|---|---:|---:|---:|---:|
| `./gradlew test` 전체 | H2 | 259 | 259 | 0 | 135 |
| `./gradlew mysqlTest` 전체 | MySQL 8.4 | 232 | 232 | 0 | 0 |
| 외부 DB 계약·마이그레이션 | MySQL 8.4.13 | 175 | 175 | 0 | 0 |

H2의 실행 안 함 135건은 외부 DB나 프론트 소스가 필요한 테스트다. 외부 DB 계약·마이그레이션은 세 번째 줄에서 MySQL 8.4로 따로 실행했다.

### 이번 주에 추가한 테스트 (11개 케이스, H2·MySQL 실행 합계 18건)

| 테스트 | 케이스 작성 | 코드 구현 | H2 | MySQL 8.4 |
|---|---|---|---|---|
| `FinalVerificationContract` `PRIV01~04` 4건 | O | O | 4 PASS | 4 PASS |
| `FinalVerificationMySqlIT` `MIG01·MIG02·DB01·DB02` 4건 (MySQL 전용) | O | O | 해당 없음 | 4 PASS |
| `AuthContract` `LOGIN09~11` 3건 | O | O | 3 PASS | 3 PASS |

## 3. 할당 항목별 결과

### DB 데이터 정합성 최종 확인 (11/23)

| 확인 내용 | 결과 | 근거 테스트 |
|---|---|---|
| 동시 신청·취소·마감·일정 취소 뒤 DB 규칙 8개(정원 초과, 상태·인원 불일치, 취소 모집의 참여 중 기록, 작성자 자기 참여, 중복, 상태·취소 시각 불일치, 고아 기록) 위반 없음 | PASS (H2, MySQL 8.4) | `IntegrityAccessContract` `INT00~02` (최신 `main`에서 재실행) |
| API 조회 결과(상세·목록·내 참여 내역·내 작성 글)가 DB 행과 일치 | PASS (H2, MySQL 8.4) | `INT03` |
| PR #39의 MySQL 세션 설정(`useLocalSessionState`) 이후에도 서비스 트랜잭션이 서버에서 실제로 READ-COMMITTED로 실행되고, 다른 격리 수준을 쓴 뒤 연결이 원래 수준으로 돌아옴 | PASS (MySQL 8.4) | `DB01` |
| 한글 이름이 utf8mb4로 저장·조회됨 | PASS (MySQL 8.4) | `DB02` |
| 동시성·롤백 기존 계약(마지막 자리, 중복 신청, 취소·마감 경합, DB 오류 롤백) | PASS (H2, MySQL 8.4) | `ParticipationContract` 85건, `PostContract` 83건, `RecruitmentConsistency*` 12건 |

### 마이그레이션 결과 최종 점검 (11/23)

| 확인 내용 | 결과 | 근거 |
|---|---|---|
| 빈 MySQL 8.4에 앱을 띄우면 Flyway 이력이 정확히 `1:SQL:성공`, `2:SQL:성공` | PASS (MySQL 8.4) | `MIG01` |
| V1·V2에 정의한 FK·UNIQUE·CHECK 제약 17개와 학번·이메일 UNIQUE가 서버에 실제로 존재 | PASS (MySQL 8.4) | `MIG02` |
| 모든 엔티티가 마이그레이션된 스키마와 일치(Hibernate validate) | PASS (MySQL 8.4) | 모든 `*MySqlIT` 컨텍스트 시작 |
| 신규 DB·모의 기존 DB 마이그레이션 리허설 | PASS (MySQL 8.4.13) | `UserDatabaseMigrationTest` 7건 |
| 마이그레이션 파일 변경 여부 | 변경 없음 | `V1`, `V2` 모두 PR #14(10/28) 이후 수정 이력 없음, 새 마이그레이션 없음 |
| 배포 DB의 실제 Flyway 이력 | **미확인 (PASS 아님)** | 배포 DB 접속 불가·직접 연결 금지 |

### 인증·권한 관련 예외 상황 최종 검증 (11/24)

| 확인 내용 | 결과 | 근거 테스트 |
|---|---|---|
| 잠긴 계정에 올바른 비밀번호 8건이 동시에 와도 모두 거절되고 실패 횟수·잠금 시각·마지막 로그인이 그대로 (PR #40의 "잠금 밖 비밀번호 비교" 이후) | PASS (H2, MySQL 8.4) | `LOGIN09` (신규) |
| 비밀번호 재설정과 예전 비밀번호 로그인이 동시에 와도, 재설정 뒤에는 새 비밀번호만 통과 | PASS (H2, MySQL 8.4) | `LOGIN10` (신규) |
| 없는 계정·잠긴 계정·정지 계정·틀린 비밀번호의 거절 응답이 모두 같아 계정 존재·잠금 여부를 알 수 없음 | PASS (H2, MySQL 8.4) | `LOGIN11` (신규) |
| 기존 예외 검증: 5회 잠금·30분 해제, 동시 실패 집계, 정지 계정, 잘못된 입력, CSRF, 세션 교체·로그아웃, 권한(`/api/admin/**`, 작성자 전용 마감·취소) | PASS (H2, MySQL 8.4) | `AuthContract` `LOGIN01~08`·`ROLE01`, `IntegrityAccess` `ACC01·ACC04`, `SessionSecurityHttpTest`, `LocalSessionIntegrationTest` |

### 서버 권한 및 개인정보 노출 여부 최종 검증 (11/25)

요청 사항: 참가자 명단과 외부 소통 링크에 비참여자·참여 취소자가 접근할 수 없는지 확인.

| 확인 내용 | 결과 | 근거 테스트 |
|---|---|---|
| 모집 상태 5가지(모집 중, 작성자 마감, 일정 취소, 종료, 관리자 삭제) 모두에서 비참여자·참여 취소자·관리자는 참가자 명단 403(삭제 시 404), 응답에 참가자 이름 없음, 비로그인 401 | PASS (H2, MySQL 8.4) | `PRIV01` (신규) |
| 같은 5가지 상태에서 비참여자·참여 취소자·관리자·비로그인에게는 목록·상세·명단·내 상태·내 참여 내역·내 작성 글 어디에도 외부 소통 링크가 없음 | PASS (H2, MySQL 8.4) | `PRIV01` (신규) |
| 작성자·현재 참가자는 진행 중(모집 중·마감)일 때만 링크를 보고, 일정 취소·종료·삭제 뒤에는 볼 수 없음 | PASS (H2, MySQL 8.4) | `PRIV01` (신규) |
| 취소 후 재참여하면 명단·링크 권한이 돌아오고, 다시 취소하면 즉시 사라짐 | PASS (H2, MySQL 8.4) | `PRIV02` (신규) |
| 어떤 조회 API도 사용자(본인 포함 5명)의 학번·학교 이메일을 응답하지 않음 | PASS (H2, MySQL 8.4) | `PRIV03` (신규) |
| PR #45로 목록에 추가된 `viewer`는 요청한 본인과의 관계만 담고 다른 사람의 참여 여부를 드러내지 않음 | PASS (H2, MySQL 8.4) | `PRIV04` (신규) |
| 기존 권한 검증: 명단·링크(`ACC02`, `DISC004_*`, `linkAndMembersStayPrivate`), 타인 데이터 차단(`ACC03`), 로그인 중 정지·탈퇴 차단(`ACC04`) | PASS (H2, MySQL 8.4) | 위 테스트 |

참고: 현재 참가자의 "내 참여 내역"에는 소통 링크가 포함된다. 상세 화면과 같은 기준(작성자·현재 참가자, 진행 중인 모집)이라 의도된 동작으로 판단했다.

## 4. 결론

- 4개 항목 모두 테스트를 실제로 실행해 확인했고, **고칠 결함은 발견되지 않아 운영 코드는 변경하지 않았다.**
- 지난주 이후 병합된 팀원 변경(PR #39 DB 왕복 감소, #40 인증 수정, #43·#47 마감·취소 트랜잭션, #45 목록 viewer)을 포함한 최신 `main`에서 실행했다.

## 5. 실행하지 않은 항목 (PASS 아님)

| 항목 | 이유 |
|---|---|
| 배포 서버(Render)·배포 DB(Aiven)에서의 확인(실제 Flyway 이력, 실제 응답) | 작업 환경에서 접속 불가, 팀 규칙상 배포 DB 직접 연결 금지 |
| 실제 SMTP 메일 발송·수신 | 테스트에서 메일 발송은 대체 처리, 배포 환경 메일 설정 미확인 |
| GitHub CI에서의 MySQL 테스트 | CI는 기본 `test`(H2)만 실행. MySQL 결과는 일회용 컨테이너 실행 결과 |
| 기존 사용자 데이터 이전 검증 | 보존할 기존 사용자 DB가 없어 대상 없음 (PR #22, #24) |
