# 재검증 5: DB 마이그레이션

검증일: 2026-10-15. 기준: 7f6d944 및 재검증 1·2·3·4의 누적 로컬 변경. 실제 팀 DB 자료는 아직 없다는 사용자 확인에 따라 실제 DB 적용 검증은 미완료로 분리했다.

## 결과 구분

| 구분 | 결과 |
|---|---|
| 신규 별도 DB | Flyway V1 SQL + V2 SQL 실행, 모든 엔티티 validate 및 재실행 migration 0건 검증 |
| 모의 기존 사용자 DB | 현재 JPA 사용자 엔티티에서 독립적으로 생성, 합성 데이터 입력. V1과 구조 차이를 발견해 임시 DB에서 정합화한 후 baseline 1 + V2 적용 및 데이터 보존 검증 |
| 실제 팀 사용자 DB | DDL/접속 정보 미확보. 실제 스키마 대조, 복제본 baseline/V2 리허설, 실제 적용은 수행하지 않음 |

실행 환경은 전용 MariaDB 11.4.10/InnoDB, Flyway 12.4.0이다. 기존 실제 DB나 그 복제본에 실행한 결과가 아니며 MySQL 검증 결과로 대체하지 않는다.

최종 실행 결과: 마이그레이션 7건 + 기존 H2/실제 HTTP 테스트 167건 = 총 174건 통과, 실패·오류 0건. 기존 외부 DB API 계약은 이번 실행에서 건너뛰었다. 테스트가 만든 임시 스키마는 실행 후 정리하며 기존 검증 DB의 다른 스키마는 유지한다.

## 발견한 차이 및 검증

V1은 refresh_token.revoked를 BOOLEAN으로 정의하며 MariaDB에서 TINYINT(1)로 생성된다. 현재 사용자 JPA 엔티티를 사용해 별도 생성한 컬럼은 BIT(1)이어서 첫 구조 대조가 실패했다. 이 차이를 무시하거나 V1을 수정하지 않았다.

모의 DB에서만 BOOLEAN으로 정합화하고 true/false 값 보존을 확인한 후, V1 기준의 타입·길이·NULL·기본값·자동 증가 속성, PK/UNIQUE/FK 구성·참조 동작, 엔진/콜레이션을 대조했다. 제약/인덱스의 자동 생성 이름과 컬럼의 물리적 나열 순서는 동등성 비교에서 제외했다. CHECK·트리거 등 추가 항목은 별도 조사 SQL로 수집하며 실제 스키마 확보 시 검토한다.

이 fixture는 통합 이후 현재 엔티티를 사용한다. 통합 전 원본 엔티티에는 role/status/provider의 VARCHAR 강제 매핑이 없으므로 실제 과거 DB의 native ENUM 여부도 확인해야 한다. 이를 실제 팀 DB에서 발견한 차이라고 보고하지 않는다.

## 추가한 테스트 7건

| 사례 | 검증 내용 |
|---|---|
| 신규 DB | 이력 1:SQL / 2:SQL, 전체 매핑 validate, 재실행 0건 |
| 모의 기존 DB | 조사 SQL 실행, 구조 대조와 임시 정합화, baseline 후 V2만 1건 실행, 이력 1:BASELINE / 2:SQL |
| 기존 데이터 보호 | 사용자 2명, 자격정보 1개, 인증 연결 1개, refresh token 2개의 ID·모든 필드 보존. revoked는 0/1 의미를 비교. 사용자 테이블 AUTO_INCREMENT 다음 값 유지 |
| 새 FK 연결 | 보존한 사용자 ID 1017/1043으로 모집글·참여 저장, 존재하지 않는 사용자 FK는 거절 |
| 자동 baseline 방지 | 기존 비어 있지 않은 스키마에 baseline 없이 migrate하면 실패. 기존 행 유지, 신규 테이블/이력 없음 |
| 타입/길이 불일치 탐지 | native ENUM 및 이메일 VARCHAR(99) 차이를 각각 탐지하고 baseline 미실행(2건) |
| JPA BOOLEAN 차이 탐지 | BIT(1)와 V1 TINYINT(1) 차이를 독립적으로 확인 |
| baseline의 검증 한계 | refresh_token 누락 상태에서도 baseline/V2/Flyway validate는 성공하지만 Hibernate validate는 실패함을 확인 |

모의 기존 DB의 데이터 보존·FK 연결은 하나의 통합 시나리오에 포함된다. 위 표는 검증 내용을 나눈 것이며 JUnit 실행 수는 매개변수 사례를 포함해 7건이다.

이번 작업은 테스트·조사 SQL·운영 절차 문서만 추가/수정했다. V1/V2 마이그레이션, 운영 사용자 엔티티, 애플리케이션 DB 설정은 변경하지 않았다. baseline-on-migrate 자동 활성화도 추가하지 않았다.

## 다음에 실제 자료가 생기면

[기존 사용자 DB 적용 절차](../db-migration-existing-users.md)에 따라 [읽기 전용 조사 SQL](../../scripts/inspect-user-schema.sql)과 네 테이블의 SHOW CREATE TABLE 결과를 확보한다. 같은 제품·버전의 실제 DB 복제본에서 구조 정합화, baseline, V2, 데이터·기존 인증 보존, Hibernate validate, 재시작 및 복구를 별도로 확인한다.

신규 DB 성공이나 이번 모의 리허설만으로 실제 팀 DB 적용 완료를 보고하지 않는다. 아직 커밋·푸시·PR 갱신·머지는 수행하지 않았다.
