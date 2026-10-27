# 재검증 3: 모집 중복 방지

검증일: 2026-10-15. 기준: 7f6d944 및 재검증 1·2의 누적 로컬 변경. 이번 항목은 운영 중복 방지 로직을 추가 수정하지 않고 테스트 24건을 보강했다. 아직 커밋/푸시하지 않은 변경이다.

## 결론

동일 작성자의 신규 모집 생성과 기준시각 변경은 같은 USERS 행의 PESSIMISTIC_WRITE 잠금을 사용한다. 유형 구분 없이 같은 작성자·같은 기준시각의 유효 모집을 검사하며, 실질 FINISHED를 제외하는 조건도 일치한다.

| 검증 | 결과 |
|---|---|
| H2 전체 | 모집글 72 + 참여 72 + 인증/기존 사용자·컨텍스트 11 = 155건 통과 |
| MariaDB 11.4.10 / InnoDB | 모집글 72 + 참여 72 = 144건 통과, 실패·오류·skip 0 |
| 실질 FINISHED 경계 | OPEN/FULL/CLOSED 각각 T 직전 포함, T 및 T 이후 제외 |
| 실제 작성자 락 | InnoDB 메타데이터에서 USERS PRIMARY 행의 락 대기를 확인 |
| MySQL 8.4 / 새 GitHub CI | 이번 환경에서 직접 실행하지 않음 |

H2의 외부 DB 테스트 클래스는 비활성화되며, 별도 전용 MariaDB 스키마에서 동일 계약을 실행했다. 두 DB 성공 합계는 299회다. MariaDB 결과를 MySQL 8.4 검증으로 간주하지 않는다.

## 생성과 시간 변경의 공통 잠금 규약

| 경로 | 순서 |
|---|---|
| 신규 생성 | READ_COMMITTED 쓰기 트랜잭션 → 작성자 USERS 잠금 → 서버 시각/입력 검증 → 중복 검사 → INSERT/flush → 커밋 |
| 기준시각 변경 | READ_COMMITTED 쓰기 트랜잭션 → 같은 작성자 USERS 잠금 → 수정 대상 POST 잠금/최신 상태 조회 → 서버 시각/권한/수정 조건 검증 → 변경 시각의 중복 검사 → UPDATE/flush → 커밋 |

두 경로 모두 PostService.lockedActor → UserRepository.findByIdForUpdate를 사용하고, 같은 noConflict → PostRepository.countConflicts로 검사한다. 검사와 저장은 작성자 잠금을 유지한 하나의 트랜잭션 안에서 처리한다. 아직 모집글이 하나도 없는 작성자도 이미 존재하는 USERS 행을 잠그므로 첫 동시 생성에도 적용된다.

기준시각이 실제로 바뀌지 않은 비핵심 수정에는 중복 검사를 다시 적용하지 않는다. 서로 다른 작성자는 같은 시각에 모집할 수 있고, 한 작성자의 잠금 때문에 다른 작성자의 생성까지 직렬화하지 않는 것도 검증했다.

## 중복 검사 대상

countConflicts 조건은 다음과 같다.

- author_id와 reference_time이 요청 대상과 같음
- 수정 대상 자신의 id는 제외
- deleted_at IS NULL
- 저장 상태가 OPEN / FULL / CLOSED
- reference_time > 검증에 사용한 서버 현재시각
- type 조건 없음: TAXI와 DELIVERY 사이도 같은 중복 정책 적용

따라서 CANCELLED / 저장된 FINISHED / REMOVED는 제외된다. 저장 상태가 OPEN/FULL/CLOSED여도 reference_time <= now이면 실질 FINISHED라서 제외된다. 기준시각 10분 전부터 참여가 제한되는 것과 모집이 FINISHED가 되는 시각 T는 구분한다.

실질 FINISHED 제외는 과거 기준시각으로 새 모집을 만들 수 있다는 뜻이 아니다. 같은 과거 시각을 다시 생성하려는 요청은 별도의 현재+10분 초과 검증에서 POST_REFERENCE_TIME_TOO_EARLY로 거절되는 것도 확인했다.

## 보강한 24건

| 구분 | 사례 수 | 결과 |
|---|---:|---|
| 신규 생성의 유형 조합 | 4 | TAXI/TAXI, TAXI/DELIVERY, DELIVERY/TAXI, DELIVERY/DELIVERY 모두 같은 작성자의 같은 시각이면 차단. 다른 작성자는 허용 |
| 기준시각 변경의 유형 조합 | 4 | 동일 네 조합의 중복 변경 차단. 거절된 글의 기존 시각·제목·updatedAt 유지 |
| FULL/CLOSED 대상 시간 변경 | 2 | 같은 작성자·시각으로 변경 차단 |
| CANCELLED/FINISHED/REMOVED 대상 시간 변경 | 3 | 해당 종료 상태를 제외하고 새 유효 모집 하나만 유지 |
| 저장 상태별 실질 FINISHED | 3 | OPEN/FULL/CLOSED 각각 T-1초는 중복 대상, 정확히 T와 T+1초는 제외. DB 저장 상태는 변경하지 않음 |
| 다른 작성자의 시간 변경 | 1 | 타 작성자가 사용하는 시각으로 변경 허용 |
| 첫 생성 ↔ 첫 생성 | 2 | TAXI/DELIVERY가 각각 먼저 처리되는 경우, 후행 생성은 작성자 행에서 대기 후 중복 거절 |
| 신규 생성 ↔ 기준시각 변경 | 2 | 두 처리 순서를 고정해 각각 재현. 목적 시각의 유효 모집은 항상 1개 |
| 시간 변경 ↔ 시간 변경 | 2 | 서로 다른 기존 TAXI/DELIVERY 모집이 같은 시각으로 이동하려는 두 순서에서 하나만 허용 |
| 다른 작성자의 독립 생성 | 1 | 첫 작성자의 트랜잭션이 잠금을 유지한 상태에서도 다른 작성자가 같은 시각의 모집을 커밋 |

기존 동시 시작 경합 테스트도 유지한다. 보강한 순서 고정 테스트는 먼저 처리하는 트랜잭션을 flush 후 커밋 직전에 대기시키고, 다음 요청이 USERS PRIMARY 행에서 실제로 막힌 것을 확인한 뒤 커밋한다. DB 조회를 실행하지 않고 메모리 락만 검사하는 테스트가 아니다.

## DB 제약 및 팀 통합 시 확인 사항

현재 설계는 단순 UNIQUE(author_id, reference_time)를 두지 않는다. 취소/삭제/저장된 FINISHED 등 제외 상태가 사용하는 시각을 다른 유효 모집이 사용할 수 있어야 하기 때문이다. 중복 대상 조건의 검사·생성·시간 변경을 동일 작성자 잠금으로 직렬화한다.

따라서 새 생성/수정 API, 배치 또는 관리자 기능이 추가될 때도 동일 작성자 잠금과 중복 검사 규약을 따라야 한다. POST를 직접 INSERT/UPDATE하는 외부 SQL이나 별도 구현이 이를 우회하는 경우까지 보장하는 설계는 아니다. 기존 UNIQUE(post_id,user_id), FK, CHECK 제약과 트랜잭션은 계속 적용한다.

광빈님과 공유할 핵심은 작성/시간 변경의 USERS → POST 잠금 순서, 실제 READ_COMMITTED 쓰기 조건, 서로 다른 유형을 통합한 유효 모집 정의다. 이번 확인으로 광빈님과의 실제 협의가 완료되었다는 뜻은 아니다.

## 재현 및 근거

```powershell
./gradlew.bat test --no-daemon --console=plain
```

전용 DB 연결 환경변수(PARTICIPATION_TEST_DB_URL/USERNAME/PASSWORD)를 설정한 뒤:

```powershell
./gradlew.bat test --tests '*PostExternalDatabaseTest' --tests '*ExternalDatabaseIntegrationTest' --no-daemon --console=plain
```

락 대기 조회 권한은 [실행 안내](../running.md)를 참고한다. 이번 검증은 전용 새 스키마에서 수행했고 검증용 서버는 종료했다.

근거 코드: PostService.create/update/lockedActor/noConflict, UserRepository.findByIdForUpdate, PostRepository.countConflicts. 테스트: PostContract의 DUP_ 24건과 기존 중복·동시성 테스트. 공통 DatabaseLockAssertions는 POST/USERS의 실제 락 대기 관찰에 사용한다.
