# 모집글·참여 API 검증 결과

검증일: 2026-11-03 (Asia/Seoul)

검증 단계에서는 원본 소스·테스트·명세 문서와 GitHub를 변경하지 않았다. 이후 사용자 요청에 따라 검증 보고서를 기존 작업 브랜치와 열린 PR #16에 추가했다. 기존 수정 코드는 25c21cf이며 이번 제출의 추가 변경은 검증 문서뿐이다. main 수정·병합·자동 머지·배포는 수행하지 않는다.

## 검증 대상과 결과

| 대상 | 기준 버전 | 실행 내용 | 결과 |
|---|---|---|---|
| PR #16의 수정본 | codex/revalidate-post-participation-20261028 / 25c21cf48fb3bfcffd0a092b3fabdc423358cd16 | 캐시 없이 전체 기본 테스트 | 192건 통과, 실패/오류 0 |
| 같은 수정본 | 같은 커밋 | 전용 MariaDB 11.4.10/InnoDB 전체 API 계약 | 168건 통과, 실패/오류/skip 0 |
| GitHub main | 읽기 전용 조회 시 b79ca2a66a0a4475a349e11695896bee680878ef | 분리 스냅샷에 기존 회귀 검사 10건을 적용 | 2건 통과 / 8건 실패. 오류 미반영 상태 재현 |

기본 실행의 조건부 외부 DB/마이그레이션 skip 134건은 192건에 포함하지 않았다. 외부 DB 계약은 별도 MariaDB 실행에서 수행했다. 두 DB의 공통 사례는 중복 실행이므로 서로 다른 기능 360개를 검증했다는 의미가 아니다. main에서는 오류 재현용 10건만 실행했으며 전체 테스트 결과로 표시하지 않는다.

## 1. 모집글 API

로컬 수정본에서 다음 사항을 확인했다.

- 작성: 유형별 필수값, 제목/본문/정원/시간/URL 검증, 201 및 Location, DB 저장값과 응답값 일치.
- 목록/상세: 필터·페이지·기준시각/ID 오름차순, 작성자 포함 현재 인원, 본인/참가 여부에 따른 소통 링크 공개, 삭제된 모집 숨김.
- 수정: 작성자만 허용, 비핵심 필드와 핵심 필드 수정 조건 분리, 생략/null 구분, 시각 변경 시에만 생성 조건과 중복 조건 재검증.
- 잘못된 값: 알 수 없는 필드, 위조 ID, 중복 JSON 키, 잘못된 자료형/enum, 잘못된 페이지, 잘못된 ID 및 없는 모집을 거절.
- 인증/권한: 미인증 401, CSRF·다른 작성자 수정 403, 정지/탈퇴 계정의 기존 세션 접근 차단.
- 응답: 문자열 ID, 숫자 인원, boolean 파생값, data/error 구분, error.code, JSON Content-Type, Cache-Control, 헤더와 meta.requestId 일치.

## 2. 참여·취소·재참여 API

- 최초 참여 201, 참여 수 증가, 작성자는 PARTICIPATION에 저장하지 않음.
- 중복 요청 거절, 작성자의 직접 참여/참여 취소 거절, 없는 모집 404.
- 취소는 CANCELLED 전환, 취소 인원 제외, FULL이면 OPEN으로 전환하되 CLOSED는 유지.
- 기준시각까지 601초 남으면 취소 허용, 600초/599초면 CANCEL_TIME_CLOSED. 거절 시 DB 상태와 시각 컬럼 유지.
- 재참여는 새 행을 생성하지 않고 기존 행을 JOINED로 전환. ID/createdAt 보존, updatedAt 갱신, cancelledAt 초기화.
- 참여 내역은 본인 데이터만 조회하며 JOINED/CANCELLED 필터가 실제 DB 상태와 일치.
- 작성자 1명 + JOINED 수로 인원 계산, 정원 초과 방지, DB UNIQUE(post_id,user_id) 확인.

## 3. 트랜잭션·동시성

전용 MariaDB의 실제 행 잠금 대기를 관찰하는 기존 테스트까지 재실행했다.

- 마지막 한 자리를 여러 사용자가 동시에 신청하면 한 명만 성공하고 최종 정원을 유지.
- 같은 사용자의 동시 참여/취소/재참여는 논리 참여 한 건을 유지하며 중복 성공이나 이중 인원 감소 방지.
- 취소 후 잠금을 얻은 참여 요청이 최신 상태/인원을 읽어 빈자리를 사용.
- 참여와 모집 취소/관리자 삭제 경합의 양쪽 처리 순서에서 최종 POST/PARTICIPATION 일관성 유지.
- 잠금 대기 후 시각/상태 재검증, 오래된 영속성 컨텍스트 재사용 방지.
- CHECK 제약 실패 시 POST/PARTICIPATION 및 관련 시각/삭제 값 전체 롤백.
- 동일 작성자의 생성/시각 변경 직렬화, 유형 간 동일 기준시각 중복 방지.
- 잘못된 상위 읽기 전용/격리 수준 트랜잭션에서 쓰기 거절.

## 4. main에 남은 문제

2026-11-03 검증 시점의 GitHub main에는 PR #16 수정본의 오류 처리가 아직 반영되지 않았다. 원본 작업 트리를 바꾸지 않기 위해 main을 work/revalidation-20261103-main에 git archive로 풀고 현재의 기존 회귀 테스트 세 파일만 검증 도구로 복사했다. main 운영 코드는 수정하지 않았다. 변경 요청 네 사례는 오류 본문 단언을 제외해 406 이후 DB 변경 여부를 별도로 관찰했다.

재현 조건: 정상 인증과 CSRF를 통과한 요청에서 `Accept: text/plain`을 사용한다.

| 요청 | main에서 관찰한 결과 |
|---|---|
| 모집글 생성 | 406인데 모집글 1건 저장됨. 기대 0건, 실제 1건 |
| 모집글 수정 | 406인데 제목 변경됨 |
| 참여 | 406인데 JOINED 1건 저장됨. 기대 0명, 실제 1명 |
| 참여 취소 | 406인데 JOINED가 0명으로 줄어듦. 기대 1명, 실제 0명 |
| 목록 조회 | 406 오류에 JSON Content-Type/공통 오류 본문 누락. 실제 로그인 세션 HTTP에서도 확인 |
| 잘못된 ID + 같은 Accept | 공통 오류 응답 대신 MVC 예외 전파 확인 |

회귀 검사 10건 중 8건의 실패는 서로 다른 오류 8개를 의미하지 않는다. 지원하지 않는 응답 형식을 실행 전에 검증하지 않는 문제와 오류 JSON 반환 문제를 여러 경로에서 재현한 결과다. 깨진 JSON과 지원하지 않는 Accept가 함께 오면 main은 400, 수정본 계약은 실행 전 406이므로 해당 우선순위 차이도 실패 건수에 포함된다.

25c21cf에는 이미 두 컨트롤러의 `produces=application/json` 선언과 오류 처리기의 JSON Content-Type 지정이 있고 작업 브랜치와 PR #16에 올라가 있다. 이번 재검증에서는 코드를 추가로 수정하지 않았다.

## 미완료/미검증 범위

- 실제 프론트 코드/배포 URL/연동 오류 로그를 이용한 브라우저 통합 검증은 하지 않았다. 세션/CSRF 실제 HTTP 테스트를 프론트 연동 완료로 간주하지 않는다.
- 다른 출처에서 호출하는 프론트의 CORS·쿠키·HTTPS·프록시 설정과 실제 요청/응답 매칭은 프론트 환경에서 별도 확인이 필요하다.
- 모집글 마감/모집 전체 취소/관리자 soft delete는 서비스 훅만 있으며 연결 HTTP API가 없다. 참여 취소 API와 모집 전체 취소는 구분한다.
- MySQL 8.4 Testcontainers는 Docker가 없어 실행하지 않았다. MariaDB 결과로 대체 완료 표시하지 않는다.
- 실제 팀 DB, 기존 DB baseline/마이그레이션 선택 테스트, 배포 서버는 이번에 검증하지 않았다.

## 실행 기록

```powershell
./gradlew.bat test --rerun-tasks --no-daemon --console=plain
# 192건 통과. 조건부 skip 134건은 제외.

# 이번에 만든 전용 스키마로 환경변수 설정 후 실행
./gradlew.bat test --tests '*PostExternalDatabaseTest' --tests '*ExternalDatabaseIntegrationTest' --no-daemon --console=plain
# MariaDB 168건 통과.
```

전용 스키마: post_participation_revalidation_20261103. 이번에 시작한 로컬 MariaDB는 검증 후 정상 종료했다. 팀 DB에 접근하지 않았다.

원시 결과는 로컬 work/revalidation-20261103-h2-summary.json, work/revalidation-20261103-mariadb-summary.json, work/revalidation-20261103-main-regression-summary.json과 분리 스냅샷의 JUnit 보고서에 보관했다. 이 파일들은 Git에서 제외되며 PR에는 본 보고서의 결과 요약을 공유한다.
