# 새 저장소 통합 검증 기록

검증일: 2026-10-12. 대상: SYU-Taxi-Delivery/taxi-delivery-sharing, Spring Boot 4.1.1 / Gradle 9.7.1.

| 검증 | 결과 |
|---|---|
| 기본 Gradle test / H2 | 모집글 39 + Participation 48 + 학교 세션 연결 7 + 기존 사용자/컨텍스트 4 = 98개 통과 |
| 전용 MariaDB 11.4.10 / InnoDB | 모집글 39 + Participation 48 = 87개 통과 |
| Flyway 신규 사용자 V1 / 모집글·참여 V2 | 전용 신규 스키마에서 적용 성공 |
| Hibernate validate | 팀 User/UserAuth/LocalCredential/RefreshToken 및 모집글·참여 매핑 검증 성공 |
| Gradle bootJar | 실행 가능한 JAR 생성 성공 |
| 최종 실행 JAR 실제 HTTP | 모집글 smoke와 기존 Participation smoke 모두 통과 |
| MySQL 8.4 Testcontainers | Docker 미설치로 미실행. `gradlew mysqlTest` 실행 경로 제공 |

기본 test에서는 외부 DB 계약 87개가 환경변수 미설정으로 skip된다. 외부 DB를 연결한 별도 실행에서 해당 87개를 모두 수행했다. 두 DB의 성공 합계는 185회이며 MariaDB를 MySQL에서 실행한 결과로 간주하지 않는다.

모집글 계약은 유형별 입력, 작성자 포함 인원, +10분과 분 단위 시간, 유효 모집 중복, 작성자 권한, 참가/마감 상태의 핵심 조건 수정 제한, PATCH 생략/null, JSON 타입/중복 키, 정보 공개, 실질 FINISHED, 목록 필터·정렬·페이지 및 동시성 잠금을 검증한다. Participation 계약은 마지막 자리 경쟁, 중복 신청, 취소/재참여 ID 유지, 목록 권한, 모집 취소/삭제 경합, DB CHECK 실패 시 롤백을 검증한다.

학교 세션 연결은 메일 발송을 mock으로 대체하고 기존 이메일 가입 → 로그인 → 실제 User ID로 모집글 작성, 로그인 실패 횟수/5회 잠금, CSRF, 비활성 계정 차단, ID 위조 입력 거절, 세션 ID 교체/로그아웃, 로그인 후 계정 정지 시 접근 차단을 검증한다. 실제 SMTP 발송 및 별도 학교 SSO 공급자 연결은 검증하지 않았다.

기존 팀 사용자 코드를 유지하면서 컴포넌트/엔티티/저장소 스캔과 PasswordEncoder를 연결했다. 기존 데이터가 있는 공유 DB는 신규 DB와 달리 별도 스키마 대조와 baseline 절차가 필요하다. [실행 안내](running.md)를 참고한다.

실제 HTTP 모집글 검증은 작성 → 비참가자 상세/소통 링크 비노출 → 수정 → 참여/인원 반영 → 핵심 조건 수정 409 → 중복 생성 409 → 유형 목록 → 취소/링크 차단 순서다. Participation 검증은 참여 → 상태/명단 → 취소 → 명단 권한 차단 → 재참여 시 기존 ID 유지 → 내역 조회 순서다.
