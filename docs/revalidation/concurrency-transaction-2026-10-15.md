# 재검증 2: 동시성 및 트랜잭션

검증일: 2026-10-15. 기준: 7f6d944 및 로컬 재검증 패치. 아직 커밋/푸시하지 않은 변경이다.

## 결론

DB 락 + DB 제약조건 + 트랜잭션을 함께 사용하는 구조는 이미 있었지만, 기존 트랜잭션에서 미리 읽은 엔티티를 재사용하는 결함을 실제 MariaDB에서 재현했다. 잠금 후 최신 DB 상태를 읽도록 수정했다. 이후 요청된 경합·롤백 및 전체 회귀 검증이 통과했다.

| 검증 | 결과 |
|---|---|
| H2 전체 회귀 | 모집글 48 + 참여 72 + 학교 세션 7 + 기존 사용자/컨텍스트 4 = 131건 통과 |
| MariaDB 11.4.10 / InnoDB 신규 검증 스키마 | 모집글 48 + 참여 72 = 120건 통과, 실패·오류·skip 0 |
| 마이그레이션 / 매핑 | 신규 V1/V2 적용 및 Hibernate validate 성공 |
| 실행 JAR | bootJar 빌드 성공 |
| MySQL 8.4 | Docker 환경이 없어 직접 미실행. MariaDB 결과와 구분 |

H2 실행에서는 외부 DB 클래스가 비활성화되고, 별도 MariaDB 실행에서 실제 DB 계약을 수행했다. 두 DB 실행 성공의 합계는 251회다. 앞선 기준시각 재검증 8건도 이번 회귀에 포함한다. 이번 변경에 대한 GitHub CI나 실서비스 배포는 수행하지 않았다.

## 발견한 결함과 수정

기존 트랜잭션 A가 Post를 OPEN으로 미리 읽고, 다른 트랜잭션 B가 같은 모집을 취소하거나 삭제한 뒤 커밋하면, A의 잠금 조회가 DB 행을 잠그더라도 JPA 1차 캐시에 있는 OPEN 객체를 재사용할 수 있었다. 실제 MariaDB에서 취소 후 POST_CANCELLED, 삭제 후 POST_NOT_FOUND를 기대한 테스트 2건이 모두 OK로 잘못 통과하는 것을 확인했다.

- `LockedPostRepositoryImpl`: 기존 PESSIMISTIC_WRITE 잠금을 유지하고, 잠금을 가진 상태에서 `EntityManager.refresh(..., PESSIMISTIC_WRITE)`로 최신 모집 상태를 반영한다. PostService 수정과 Participation/Lifecycle 서비스가 같은 진입점을 사용한다.
- `CurrentParticipationRepositoryImpl`: 참여/취소의 기존 참여 레코드도 부모 POST 잠금 이후 다시 읽고 refresh한다. 같은 트랜잭션에 오래된 JOINED/CANCELLED 객체가 있어도 재참여/취소 조건을 최신 값으로 검사한다. 읽기 API에는 이 쓰기용 조회를 사용하지 않는다.
- `MutationTransactions`: 실제 쓰기 트랜잭션과 JDBC 연결의 READ_COMMITTED를 확인한다. REQUIRED는 상위 트랜잭션의 격리 수준을 바꾸지 않으므로, REPEATABLE_READ 또는 읽기 전용 상위 트랜잭션에서 쓰기가 진행되지 않게 한다. 신규 작성 경로도 이 조건을 확인한다.
- `Post`의 정원 CHECK 매핑: 기존 V2에 있던 DB 정원 범위를 H2 엔티티 생성 스키마에도 동일하게 반영한다. V1/V2 SQL 및 기존 DB 마이그레이션 체크섬은 변경하지 않았다.

잠금 순서, READ_COMMITTED 쓰기 정책, 유효 모집/참여 상태 전이 및 조회 API의 REPEATABLE_READ 정책은 유지한다. refresh에 따른 추가 조회는 발생한다.

## 요청된 경합의 최종 상태

| 상황 | 검증 결과 |
|---|---|
| 마지막 한 자리 동시 참여 | 8개 요청 중 1개만 성공. 작성자 포함 정원 유지, POST=FULL |
| 참여 커밋 후 모집 취소 | 참여 레코드를 포함해 활성 참가자를 취소. POST=CANCELLED, JOINED=0 |
| 모집 취소 커밋 후 대기 중 참여 | POST_CANCELLED로 거절. 새 참여 레코드 없음 |
| 참여 커밋 후 관리자 삭제 | 진행 중 모집의 참여 레코드를 취소. POST=REMOVED, deleted_at/deleted_by 기록, JOINED=0 |
| 관리자 삭제 커밋 후 대기 중 참여 | POST_NOT_FOUND로 거절. 새 참여 레코드 없음 |
| POST가 계속 OPEN인 동안 앞선 참여 커밋 | 대기 중 참여가 최신 JOINED 수를 계산해 마지막 자리를 채우고 FULL로 전이 |
| FULL을 CLOSED로 바꾼 뒤 대기 중 참여 취소 | 참여 취소는 처리하되 CLOSED를 유지하고 OPEN으로 복귀하지 않음 |
| 잠금 대기 중 T-10 또는 T 도달 | 참여/취소/모집 마감·취소가 잠금 후 시각으로 재검증되어 거절됨 |
| 잠금 대기 중 종료시각을 지난 뒤 관리자 삭제 | 실질 FINISHED로 판정하고 기존 JOINED 이력을 보존한 채 숨김 |

원래의 동시 시작 테스트에 더해, 선행 트랜잭션의 SQL을 flush하고 커밋을 지연한 상태에서 후행 요청을 대기시키는 테스트를 추가했다. MariaDB에서는 INNODB_LOCK_WAITS/INNODB_LOCKS에서 POST PRIMARY 행의 실제 락 대기를 확인한 다음 선행 트랜잭션을 커밋한다. 두 처리 순서를 각각 재현하므로 우연히 한 순서만 실행되는 테스트가 아니다.

## 실패 시 원자적 롤백

신규 참여, 재참여, 참여 취소, 모집 취소, 관리자 삭제 각각에서 POST와 PARTICIPATION의 변경 SQL을 먼저 flush하고 같은 트랜잭션의 JDBC 조회로 양쪽 변경을 확인했다. 그 뒤 정원을 0으로 바꾸는 SQL로 실제 DB CHECK 오류를 발생시켰다.

롤백 후 신규 참여는 생성되지 않고, 재참여는 기존 CANCELLED 레코드/ID로 복원되며, 참여 취소·모집 취소·삭제는 기존 JOINED 및 POST 상태로 복원된다. cancelled_at/updated_at과 삭제 시각/삭제 처리자도 원복되는 것을 확인했다. 기존 초기 참여 중 DB 제약 실패 테스트도 통과했다.

일반 비즈니스 거절은 ApiException(RuntimeException), 검증한 SQL 오류는 DataIntegrityViolationException이다. 현재 쓰기 경로는 이 예외를 삼키지 않으며 같은 트랜잭션 전체가 롤백된다. 미래에 checked exception을 추가할 경우 rollbackFor 정책을 별도로 합의해야 한다.

## DB 제약조건의 역할

- UNIQUE(post_id, user_id): 서비스 검사를 우회한 중복 INSERT도 DB에서 차단한다.
- FK: 존재하지 않는 모집글/사용자를 참조하지 못하게 한다.
- CHECK: JOINED/CANCELLED와 cancelled_at의 일관성, 유형별 정원 범위 등을 검사한다.
- 여러 참여 행을 합산한 실제 정원 초과, 작성자의 자기 참여, 시간 제한, 유효 모집 중복은 이 CHECK만으로 보장하지 않는다. 모든 쓰기 경로가 공통 잠금·서비스 검증·트랜잭션 규칙을 따라야 한다.

UNIQUE, FK, 참여 상태 CHECK를 서비스 밖 JDBC/JPA 쓰기로 위반시키는 테스트에서도 DB가 거절하고 기존 정상 데이터가 유지되는 것을 확인했다.

## 광빈님과 공유·확인할 통합 계약

1. 모집 작성/시간 수정은 작성자 USERS → POST 순서, 참여·취소·마감·모집 취소·삭제는 해당 POST의 쓰기 잠금을 기준으로 직렬화한다. 참여/Lifecycle 경로에서 작성자 행의 쓰기 잠금을 추가 획득하지 않는다.
2. 쓰기는 실제 READ_COMMITTED, 읽기는 별도 읽기 전용 트랜잭션이다. 상위 읽기 트랜잭션에서 쓰기 서비스를 호출하지 않는다.
3. 잠금 후 최신 POST/PARTICIPATION을 읽고 서버 시각·상태·관련 인원/참여 조건을 확인한다. 모집 마감/전체 취소/삭제처럼 정원 비교가 필요 없는 경로까지 의미 없는 COUNT를 추가하지는 않는다.
4. 외부 컨트롤러는 엔티티 상태를 미리 직접 수정하지 않고 쓰기 서비스를 호출한다. 같은 POST를 만지는 별도 배치/관리자 기능에도 공통 잠금 규칙을 적용한다.
5. close/cancelRecruitment/remove는 현재 다른 컨트롤러에서 연결할 서비스 훅이다. 이 문서의 검증은 서비스 수준이며 별도의 모집 상태 변경·관리자 HTTP API가 추가된 것은 아니다.
6. 잠금 대기/데드락/DB 제약 실패를 잡아서 성공 처리하거나 일부만 커밋하지 않는다. 재시도 시 최종 참여 상태를 먼저 확인한다.
7. 이 공유 사항은 코드로 제안한 계약이며 광빈님과의 실제 협의가 완료되었다는 뜻은 아니다.

## 재현

```powershell
./gradlew.bat test --no-daemon --console=plain
```

전용 MariaDB 검증 DB 연결 환경변수(PARTICIPATION_TEST_DB_URL/USERNAME/PASSWORD)를 설정한 뒤:

```powershell
./gradlew.bat test --tests '*PostExternalDatabaseTest' --tests '*ExternalDatabaseIntegrationTest' --no-daemon --console=plain
```

락 대기 조회를 위해 전용 검증 계정에는 MariaDB PROCESS 등 메타데이터 조회 권한이 필요하다. 운영 DB/계정으로 이 테스트를 실행하지 않는다. MySQL Testcontainers 경로는 일회성 컨테이너 root로 performance_schema 락 대기를 관찰하도록 구성했지만 이번 환경에서는 직접 실행하지 않았다.

근거 코드: `LockedPostRepositoryImpl`, `CurrentParticipationRepositoryImpl`, `MutationTransactions`, `ParticipationService`, `PostLifecycleService`, `V2__post_participation.sql`, `ParticipationContract`의 TXN_ 테스트.

공식 문서: [InnoDB Locking Reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html), [Jakarta Persistence refresh](https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html), [Spring 트랜잭션/상위 트랜잭션 검증](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html), [Spring 롤백 규칙](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html).
