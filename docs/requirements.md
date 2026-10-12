# OSS EOL 관리 플랫폼 POC 요구사항 명세서

| 항목 | 내용 |
|------|------|
| 문서 버전 | 0.2 |
| 작성일 | 2026-10-11 |
| 대상 | eol-poc-backend (Kotlin, Spring Boot 4.1, Java 21) 및 대시보드 화면 |
| 상태 | 결정 사항 Q1~Q8 확정 반영 (11장) |

---

## 1. 개요

### 1.1 목적
Opensource별 버전 및 지원 종료(EOL) 정보를 공식 출처에서 자동으로 수집·검증하고, 검증된 결과와 그 근거를 대시보드에서 확인할 수 있는 관리 플랫폼의 POC를 개발한다.

### 1.2 POC 목표
- 소수의 Opensource(6종)에 대해 수집 → 정규화 → 검증 → 저장 → 조회의 전체 흐름이 동작함을 확인한다.
- 모든 EOL 날짜가 출처와 근거 문구로 추적 가능함을 확인한다.
- 수집 방식별 공통 Collector + AI 추출 + 출처별 설정 구조가 1,000개 이상으로 확장 가능한지 판단할 근거를 얻는다.

### 1.3 범위

| 구분 | POC 포함 범위 |
|------|---------------|
| Opensource 관리 | 대상 Opensource 및 정보 출처 등록 |
| EOL 수집 | 공식 출처에서 버전·지원 종료 정보 수집 |
| 데이터 검증 | 필수값, 출처 신뢰도, 날짜 및 변경 여부 확인 |
| 저장 | 현재 데이터, 수집 결과, 근거 및 변경 이력 |
| 화면 | Opensource 목록, 상세 정보·버전 목록, 버전 상세 |
| 자동화 | 기본 수집 실행 및 수동 재수집 |
| AI 활용 | 수집한 원본에서 버전·EOL 정보를 추출하는 기본 추출기로 활용 (Q7 확정) |

### 1.4 범위 제외 (POC)
- 대시보드 차트, 알림(메일·메신저), 리포트 내보내기
- 배포판(RHEL, Ubuntu 등) 커널 및 배포판 패키지의 EOL
- Spring Framework 등 Spring Boot 이외의 Spring 프로젝트
- 마이크로서비스 분리, 동적 플러그인 시스템
- 사용자·권한 관리 (POC는 인증 없음, 11장 Q5 참고)
- MCP 연동 (필요 시 선택적으로 검토)

---

## 2. 용어 정의

| 용어 | 정의 |
|------|------|
| Opensource | 관리 대상 프로젝트. 예: PostgreSQL |
| 관리 대상 버전(Version) | 지원 정책이 적용되는 단위. 일반적으로 릴리스 사이클/브랜치(예: PostgreSQL `16`, Tomcat `10.1`, OpenSSL `3.0`). 패치 버전(`16.4`)은 해당 사이클의 "최신 패치 버전" 속성으로 관리한다. |
| 최신 확인 버전 | 수집 데이터 기준으로 해당 Opensource에서 확인된 가장 최신의 릴리스 버전 |
| 일반 지원 종료일 (EOL 날짜) | 공식 정책상 일반 지원(버그 수정 등)이 끝나는 날짜 |
| 보안 지원 종료일 | 공식 정책이 별도로 제공하는 보안 패치 지원 종료일 |
| 출처(Source) | 정보를 가져오는 공식 URL·API와 그 수집 방식 설정 |
| 수집(Collection) | 하나의 출처에서 원본 데이터를 가져와 후보 데이터를 만드는 1회 실행 |
| 후보 데이터(Candidate) | 수집 후 AI(또는 전용 Parser)로 추출했지만 아직 현재 데이터에 반영되지 않은 정규화된 데이터 |
| 현재 데이터 | 검증을 통과(APPROVED)하여 화면에 노출되는 데이터 |
| 근거(Evidence) | 출처 URL, 출처에서 추출한 근거 문구, 확인 시각 |

---

## 3. 상태 정의

EOL 상태, 수집 상태, 검증 상태는 서로 독립적으로 관리한다. **수집 실패는 EOL 상태에 영향을 주지 않는다.**

### 3.1 EOL 상태

| 값 | 표시명 | 의미 |
|----|--------|------|
| `SUPPORTED` | 지원 중 | 확인된 정책상 지원 중 |
| `EOL_SOON` | 종료 임박 | 설정된 기간 내 지원 종료 예정 |
| `EOL` | 종료 | 지원 종료가 확인됨 |
| `UNKNOWN` | 미확인 | 지원 상태를 판단할 정보가 부족함 |

**판정 규칙** (기준일 = 오늘, 서버 기준 타임존)

| 순서 | 조건 | 결과 |
|------|------|------|
| 1 | 승인된 일반 지원 종료일이 있고 종료일 < 기준일 | `EOL` |
| 2 | 승인된 일반 지원 종료일이 있고 (종료일 − 기준일) ≤ `eol.soon-threshold-days` | `EOL_SOON` |
| 3 | 승인된 일반 지원 종료일이 있고 그 외 | `SUPPORTED` |
| 4 | 종료일은 없으나 출처가 지원 종료를 명시(예: "더 이상 지원하지 않음")하고 근거가 있음 | `EOL` |
| 5 | 종료일은 없으나 출처가 현재 지원 중임을 명시(예: 최신 안정 브랜치)하고 근거가 있음 | `SUPPORTED` |
| 6 | 그 외 | `UNKNOWN` |

- `eol.soon-threshold-days` 기본값은 90이며 `application.yaml` 설정으로 변경할 수 있다.
- 날짜 기반 상태는 시간이 지나면 바뀌므로 **조회 시점에 계산**한다(저장값이 오래되어 틀리는 문제 방지). 출처가 명시한 지원 여부(규칙 4·5)만 데이터로 저장한다.
- EOL 상태는 일반 지원 종료일을 기준으로 판정한다. 보안 지원 종료일은 판정에 사용하지 않고 별도로 표시만 한다(Q1 확정).

### 3.2 수집 상태

| 값 | 의미 |
|----|------|
| `SUCCESS` | 원본 데이터를 가져와 추출(AI 또는 전용 Parser)까지 완료함 (후보 0건도 성공으로 본다) |
| `FAILED` | 네트워크 오류, HTTP 오류, AI 호출·응답 형식 오류, 파싱 오류, 타임아웃 등으로 후보를 만들지 못함 |

### 3.3 검증 상태

| 값 | 표시명 | 의미 | 현재 데이터 반영 |
|----|--------|------|------------------|
| `APPROVED` | 승인 | 자동 검증 통과 또는 관리자 승인 | 반영 |
| `REVIEW_REQUIRED` | 검토 필요 | 자동 판단이 어려워 사람의 확인이 필요 | 미반영 (관리자 승인 시 반영) |
| `REJECTED` | 거부 | 필수값 누락, 형식 오류 등으로 사용할 수 없음 | 미반영 |

---

## 4. 기능 요구사항

우선순위: **P0** = POC 완료 필수, **P1** = POC 내 구현 권장, **P2** = 여유 시

### 4.1 Opensource·출처 관리 (REG)

| ID | 요구사항 | 우선순위 |
|----|----------|----------|
| REG-01 | Opensource를 등록·수정·조회할 수 있다. 항목: 이름, 식별 키(slug, 고유), 공식 홈페이지 URL, 설명, 활성 여부 | P0 |
| REG-02 | Opensource별로 하나 이상의 출처를 등록할 수 있다. 항목: 출처 URL, 수집 방식(`API`, `WEB`, `GITHUB`), 전용 Parser 키(선택), 신뢰 등급(`OFFICIAL`, `SECONDARY`), 수집 설정(JSON), 활성 여부 | P0 |
| REG-03 | 초기 대상 6종과 출처는 애플리케이션 기동 시 시드 데이터로 등록된다. 등록 화면은 POC 범위가 아니며 API로 관리한다. | P0 |
| REG-04 | 비활성화된 Opensource·출처는 자동 수집 대상에서 제외한다. 삭제 대신 비활성화를 사용하여 이력을 보존한다. | P1 |

### 4.2 수집 (COL)

| ID | 요구사항 | 우선순위 |
|----|----------|----------|
| COL-01 | 수집은 출처의 수집 방식에 맞는 **공통 Collector**가 수행한다. Opensource별 전용 Collector를 만들지 않는다. | P0 |
| COL-02 | 수집한 원본은 기본적으로 **AI로 추출**한다(4.6절). AI 추출이 부정확한 것으로 확인된 출처에 한해 `parser_key`로 지정한 **전용 Parser**로 대체할 수 있다. | P0 (전용 Parser는 P2) |
| COL-03 | Collector는 원본 응답(본문, HTTP 상태, Content-Type, 콘텐츠 해시, 수집 시각)을 그대로 저장한다. | P0 |
| COL-04 | Extractor(AI 또는 전용 Parser)의 결과를 Normalizer가 공통 후보 형식(5.2절)으로 변환한다. 날짜·정밀도 정규화는 코드로 수행한다. 원본에 없는 값은 추정하지 않고 `null`로 둔다. | P0 |
| COL-05 | 수집 실행 1회마다 수집 상태(`SUCCESS`/`FAILED`), 시작·종료 시각, 실패 사유, 후보 건수를 기록한다. | P0 |
| COL-06 | 원본 콘텐츠 해시가 직전 성공 수집과 같으면 AI 추출·검증을 생략하고 "변경 없음"으로 기록한다. AI 결과는 같은 입력에도 달라질 수 있으므로 불필요한 변경·검토 발생을 막기 위함이다. | P0 |
| COL-07 | 외부 요청에는 타임아웃(기본 연결 5초, 읽기 30초)과 재시도(최대 2회, 지수 백오프)를 적용하고, 식별 가능한 User-Agent를 사용한다. | P1 |
| COL-08 | 하나의 출처 수집 실패가 다른 출처·Opensource 수집을 중단시키지 않는다. | P0 |

**수집 방식별 Collector (POC 구현 범위)**

| Collector | 용도 | POC |
|-----------|------|-----|
| ApiCollector | 공식 JSON API 수집 | 구현 |
| WebCollector | 공식 웹페이지(HTML) 수집. 출처 설정의 CSS 선택자(선택)로 범위를 좁히고 본문 텍스트로 변환한다. | 구현 |
| GitHubCollector | GitHub 릴리스·태그 수집 | 필요 대상이 있을 때 구현 |

Collector는 원본을 가져오는 역할만 하며, 정보 추출은 AI(또는 전용 Parser)가 담당한다.

### 4.3 검증 (VAL)

Validator는 후보 데이터 1건(버전 1개)마다 아래 규칙을 순서대로 적용하고, 가장 엄격한 결과를 검증 상태로 정한다. 모든 판정 사유는 사유 코드와 메시지로 기록한다.

| ID | 규칙 | 실패 시 결과 | 우선순위 |
|----|------|--------------|----------|
| VAL-01 | **필수값**: Opensource, 버전, 출처 URL이 있어야 한다. | `REJECTED` | P0 |
| VAL-02 | **근거 필수**: 날짜 또는 지원 여부 값이 있으면 근거 문구가 있어야 한다. | `REJECTED` | P0 |
| VAL-03 | **날짜 형식**: 날짜는 유효한 `yyyy-MM-dd`로 해석 가능해야 한다. 연·월만 제공되는 경우 해당 기간의 마지막 날로 정규화하고 정밀도(`DAY`/`MONTH`/`YEAR`)를 함께 기록한다. 원문은 근거 문구로 보관한다(Q3). | `REJECTED` | P0 |
| VAL-04 | **날짜 논리**: 릴리스일 ≤ 일반 지원 종료일, 릴리스일 ≤ 보안 지원 종료일이어야 한다. | `REJECTED` | P0 |
| VAL-05 | **날짜 범위**: 릴리스일이 미래이거나, 종료일이 1990년 이전 또는 기준일 + 15년 이후이면 의심 값으로 본다. | `REVIEW_REQUIRED` | P1 |
| VAL-06 | **버전 형식**: 출처 설정에 정의한 버전 패턴(정규식)과 일치해야 한다. | `REJECTED` | P1 |
| VAL-07 | **출처 신뢰도**: 출처 URL의 도메인이 해당 Opensource에 등록된 `OFFICIAL` 출처 도메인이어야 한다. `SECONDARY`이면 검토 대상이다. | 미등록: `REJECTED` / `SECONDARY`: `REVIEW_REQUIRED` | P0 |
| VAL-08 | **AI 근거 확인**: AI가 추출한 후보는 근거 문구가 원본 텍스트에 그대로 존재해야 하고, 추출한 날짜가 근거 문구를 코드로 다시 파싱한 날짜와 일치해야 한다. | `REJECTED` | P0 |
| VAL-09 | **AI 추출 변경**: VAL-08을 통과한 AI 추출 값 중 처음 저장되는 값(현재 값이 `null`)은 자동 승인한다. 이미 승인된 값과 다르면 자동 승인하지 않는다(Q7 확정). | `REVIEW_REQUIRED` | P0 |
| VAL-10 | **기존 값 일관성**: 이미 승인된 날짜가 `null`로 바뀌거나, 승인된 종료일이 과거로 당겨지는 등 이상 변경은 검토 대상이다. | `REVIEW_REQUIRED` | P1 |
| VAL-11 | **변경 여부**: 현재 데이터와 모든 값이 같으면 "변경 없음"으로 판정하고 마지막 확인 시각만 갱신한다. | — | P0 |
| VAL-12 | **출처 간 충돌**: 같은 버전·필드에 대해 다른 `OFFICIAL` 출처의 최근 승인 값 또는 같은 수집 주기의 후보 값과 다르면 검토 대상이다. | `REVIEW_REQUIRED` | P1 |

### 4.4 저장 및 이력 (STO)

| ID | 요구사항 | 우선순위 |
|----|----------|----------|
| STO-01 | 수집 원본, 정규화된 후보, 현재 데이터를 별도 테이블로 분리 저장한다. | P0 |
| STO-02 | `APPROVED` 후보만 EOL Service를 통해 현재 데이터에 반영한다. AI 및 Collector는 현재 데이터를 직접 수정하지 않는다. | P0 |
| STO-03 | 현재 데이터 반영 시 값이 바뀐 필드마다 변경 전·후 값, 변경 시각, 원인(수집 ID 또는 관리자 승인)을 변경 이력으로 기록한다. | P0 |
| STO-04 | 값이 바뀌지 않았으면 변경 이력을 생성하지 않는다. 마지막 확인 시각과 근거의 확인 시각만 갱신한다. | P0 |
| STO-05 | 현재 데이터의 모든 날짜·지원 여부 값은 근거 레코드와 연결되어야 한다(근거 없는 값 저장 불가). | P0 |
| STO-06 | 확인할 수 없는 날짜는 임의로 채우지 않고 `NULL`로 저장한다. | P0 |
| STO-07 | 일반 지원 종료일과 보안 지원 종료일은 별도 컬럼으로 저장한다. | P0 |
| STO-08 | 처음 발견된 버전은 관리 대상 버전으로 자동 등록한다. 출처에서 사라진 버전은 삭제하지 않는다. | P0 |
| STO-09 | 반영과 이력 기록은 하나의 트랜잭션으로 처리한다. | P0 |

### 4.5 수집 실행 자동화 (SCH)

| ID | 요구사항 | 우선순위 |
|----|----------|----------|
| SCH-01 | 활성 Opensource 전체를 주기적으로 수집한다. 주기는 cron 설정으로 관리하며 기본값은 매일 1회(새벽 3시)이다. | P0 |
| SCH-02 | 관리자는 특정 Opensource(선택적으로 특정 출처)를 수동 재수집할 수 있다. | P0 |
| SCH-03 | 동일 Opensource에 대해 동시에 두 개의 수집이 실행되지 않는다. 실행 중 재수집 요청은 거절하고 사유를 반환한다. | P1 |
| SCH-04 | Orchestrator는 수집 → 추출(AI 또는 전용 Parser)·정규화 → 검증 → 반영 순서로 실행하고, 단계별 결과를 수집 실행 레코드에 남긴다. | P0 |

### 4.6 AI 활용 (AI)

| ID | 요구사항 | 우선순위 |
|----|----------|----------|
| AI-01 | 수집한 원본(HTML은 본문 텍스트로 변환)을 AI에 전달하여 버전, 최신 패치 버전, 릴리스일, 일반 지원 종료일, 보안 지원 종료일, 지원 여부를 추출한다. 모든 출처의 기본 추출 방식이다. | P0 |
| AI-02 | AI 출력은 구조화된 형식(JSON 스키마)이어야 하며, 각 값마다 원문 근거 문구를 포함해야 한다. 원문에 없는 값은 `null`로 응답하고, 상용(Enterprise) 지원 날짜는 추출하지 않도록 지시한다(Q8). | P0 |
| AI-03 | AI 출력은 후보 데이터로만 저장하며 VAL-08, VAL-09를 거친다. AI는 DB를 직접 수정하지 않는다. | P0 |
| AI-04 | 사내망 제약으로 Azure OpenAI를 사용하며 배포 이름(모델)은 설정값으로 관리한다. 후보에는 추출 방식(`RULE`/`AI`), 모델 ID, 프롬프트 버전을 기록한다. | P0 |
| AI-05 | AI 호출 실패 또는 응답 형식 오류는 해당 수집을 `FAILED`로 기록하며 기존 현재 데이터에 영향을 주지 않는다. | P0 |
| AI-06 | 원본이 AI 입력 한도를 넘으면 출처 설정의 CSS 선택자로 범위를 줄인다. 그래도 넘으면 `FAILED`로 기록한다(분할 처리는 POC 제외). | P1 |
| AI-07 | AI 호출 비용·횟수 제한은 두지 않는다. 호출별 입력·출력 토큰 수를 로그로 기록한다(Q7 확정). | P1 |
| AI-08 | 출처 후보 탐색(새 공식 출처 찾기)은 POC 범위에서 제외하되, 결과를 자동 등록하지 않는다는 원칙만 정한다. | P2 |

### 4.7 검토 처리 (REV)

| ID | 요구사항 | 우선순위 |
|----|----------|----------|
| REV-01 | `REVIEW_REQUIRED` 후보 목록을 조회할 수 있다. | P1 |
| REV-02 | 관리자는 후보를 승인 또는 거부할 수 있으며 승인자와 사유를 입력한다(승인자 미입력 시 `admin`). 승인 시 STO-02~04에 따라 반영된다. | P1 |
| REV-03 | 동일 버전에 대해 더 최신 후보가 생기면 이전 미처리 후보는 대체됨으로 표시한다. | P2 |

---

## 5. 데이터 요구사항

### 5.1 테이블

초안의 6개 테이블에, POC 범위의 "출처 등록"과 "원본/정규화 데이터 분리"를 위해 2개 테이블을 추가한다(*표시).

| 테이블 | 역할 | 주요 컬럼 |
|--------|------|-----------|
| `opensource` | Opensource 기본 정보 | id, slug(UK), name, homepage_url, description, active, created_at, updated_at |
| `source_registry`* | 정보 출처 및 수집 설정 | id, opensource_id(FK), url, collector_type, parser_key, trust_level, config(JSON), active |
| `oss_version` | 관리 대상 버전 | id, opensource_id(FK), version(opensource_id+version UK), latest_patch_version, release_date, release_date_precision, created_at |
| `oss_lifecycle` | 버전별 현재 지원 정책 | id, version_id(FK, UK), eol_date, eol_date_precision, security_eol_date, security_eol_date_precision, support_declared(`SUPPORTED`/`EOL`/null), validation_status, last_verified_at, updated_at |
| `eol_evidence` | 근거 | id, version_id(FK), field(`RELEASE_DATE`/`EOL_DATE`/`SECURITY_EOL_DATE`/`SUPPORT`), source_url, quote, extraction_method(`RULE`/`AI`), collection_id(FK), verified_at |
| `eol_collection` | 수집 실행 결과 | id, opensource_id(FK), source_id(FK), trigger(`SCHEDULED`/`MANUAL`), status(`SUCCESS`/`FAILED`), error_message, raw_content, content_hash, http_status, started_at, finished_at, candidate_count |
| `eol_candidate`* | 정규화된 후보 및 검증 결과 | id, collection_id(FK), version, latest_patch_version, release_date, eol_date, security_eol_date, 각 날짜의 precision, support_declared, evidence(JSON), extraction_method, ai_model, prompt_version, validation_status, validation_reasons(JSON), review_comment, reviewed_by, reviewed_at |
| `eol_change_history` | 승인된 데이터 변경 이력 | id, version_id(FK), field, old_value, new_value, candidate_id(FK), changed_by(`SYSTEM`/관리자), changed_at |

### 5.2 후보 공통 형식 (Parser → Validator)

```json
{
  "version": "16",
  "latestPatchVersion": "16.4",
  "releaseDate": "2023-09-14",
  "releaseDatePrecision": "DAY",
  "eolDate": "2028-11-09",
  "securityEolDate": null,
  "eolDatePrecision": "DAY",
  "securityEolDatePrecision": null,
  "supportDeclared": null,
  "evidence": [
    { "field": "EOL_DATE", "sourceUrl": "https://...", "quote": "..." }
  ],
  "extractionMethod": "RULE"
}
```
위 값은 형식 예시이며 실제 데이터가 아니다.

### 5.3 데이터 원칙
1. 날짜를 확인할 수 없으면 `NULL`, 상태는 `UNKNOWN`으로 둔다.
2. 원본(`eol_collection.raw_content`), 정규화 후보(`eol_candidate`), 현재 데이터(`oss_version`, `oss_lifecycle`)를 구분한다.
3. 모든 EOL 날짜는 `eol_evidence`로 출처와 근거에 연결된다.
4. 값이 바뀌면 변경 전·후 값을 `eol_change_history`에 기록하고, 바뀌지 않으면 기록하지 않는다.
5. 수집 상태와 EOL 상태는 다른 컬럼·테이블에 저장한다.
6. 일반 지원 종료일과 보안 지원 종료일은 별도 컬럼이다.
7. 날짜는 `DATE`, 시각은 UTC `TIMESTAMP`로 저장하고 화면에서 KST로 표시한다.
8. 연·월 단위로만 확인된 날짜는 해당 기간의 마지막 날로 저장하고 정밀도 컬럼(`DAY`/`MONTH`/`YEAR`)에 원래 정밀도를 기록한다. 화면에는 정밀도에 맞춰 `2027-11`, `2027`처럼 표시한다.

---

## 6. 화면 요구사항

### 6.1 화면 1 — Opensource 목록
**목적**: 어떤 Opensource의 지원 상태를 확인해야 하는지 빠르게 파악한다.

| 표시 항목 | 내용 |
|-----------|------|
| Opensource 이름 | |
| 최신 확인 버전 | 수집 데이터 기준 최신 버전, 없으면 `-` |
| 관리 중인 버전 수 | |
| EOL 상태 요약 | EOL / 종료 임박 / 지원 중 / 미확인 버전 수 |
| 마지막 수집 시각 | 가장 최근 수집 실행 종료 시각 |
| 수집 상태 | 가장 최근 수집의 성공·실패 (실패 시 사유 툴팁) |

| ID | 요구사항 | 우선순위 |
|----|----------|----------|
| UI1-01 | 이름으로 검색할 수 있다(부분 일치, 대소문자 무시). | P0 |
| UI1-02 | EOL 상태(해당 상태 버전이 1개 이상 있는 Opensource)와 수집 상태로 필터할 수 있다. | P0 |
| UI1-03 | 행을 클릭하면 화면 2로 이동한다. | P0 |
| UI1-04 | 차트는 구현하지 않는다. | — |

### 6.2 화면 2 — Opensource 상세 정보 및 버전 목록

**상단 정보**: Opensource 이름, 공식 홈페이지(링크), 최신 확인 버전, 등록된 출처 목록, 수동 재수집 버튼(P1)

**버전 목록**

| 컬럼 | 내용 |
|------|------|
| 버전 | 관리 대상 버전 |
| 릴리스 날짜 | 없으면 `-` |
| EOL 날짜 | 일반 지원 종료일, 없으면 `-` |
| 보안 지원 종료일 | 별도로 제공되는 경우만, 없으면 `-` |
| EOL 상태 | 지원 중 / 종료 임박 / 종료 / 미확인 |
| 검증 상태 | 승인 / 검토 필요 / 거부 |
| 근거 링크 | 공식 출처 URL |

| ID | 요구사항 | 우선순위 |
|----|----------|----------|
| UI2-01 | 최신 확인 버전과 관리 대상 버전을 구분하여 표시한다. | P0 |
| UI2-02 | 버전 목록은 기본적으로 버전 내림차순(시맨틱 비교)으로 정렬한다. | P0 |
| UI2-03 | 검증 상태는 해당 버전에 미처리 `REVIEW_REQUIRED` 후보가 있으면 "검토 필요"로 표시한다. | P1 |
| UI2-04 | 행을 클릭하면 화면 3으로 이동한다. | P0 |

### 6.3 화면 3 — 버전 상세 및 근거
**목적**: AI 등이 잘못 추출한 날짜를 추적하고, 특정 EOL 상태로 판단한 이유를 설명한다.

| ID | 표시 항목 | 우선순위 |
|----|-----------|----------|
| UI3-01 | 버전, 최신 패치 버전, 릴리스 날짜 | P0 |
| UI3-02 | 일반 지원 종료일, 보안 지원 종료일 | P0 |
| UI3-03 | EOL 상태와 판정 사유(3.1절 판정 규칙 중 적용된 규칙, 예: "종료일까지 45일 ≤ 90일") | P0 |
| UI3-04 | 필드별 공식 출처 URL과 출처에서 추출한 근거 문구, 추출 방식(규칙/AI) | P0 |
| UI3-05 | 마지막 확인 시각 | P0 |
| UI3-06 | 검증 결과와 검토 사유(사유 코드·메시지) | P0 |
| UI3-07 | 변경 이력: 필드, 이전 값, 현재 값, 변경 시각, 원인 | P0 |
| UI3-08 | 미처리 검토 후보가 있으면 현재 값과 후보 값을 나란히 보여주고 승인·거부할 수 있다. | P1 |

---

## 7. API 요구사항 (초안)

| 메서드 | 경로 | 용도 | 화면 |
|--------|------|------|------|
| GET | `/api/opensources?q=&eolStatus=&collectionStatus=` | Opensource 목록 + 요약 | 1 |
| GET | `/api/opensources/{slug}` | Opensource 상세 + 출처 + 버전 목록 | 2 |
| GET | `/api/opensources/{slug}/versions/{version}` | 버전 상세 + 근거 + 검증 결과 + 변경 이력 | 3 |
| POST | `/api/opensources` · PUT `/api/opensources/{slug}` | Opensource 등록·수정 | — |
| POST | `/api/opensources/{slug}/sources` · PUT `/api/sources/{id}` | 출처 등록·수정 | — |
| POST | `/api/opensources/{slug}/collections` | 수동 재수집 (body: sourceId 선택) | 2 |
| GET | `/api/opensources/{slug}/collections` | 수집 실행 이력 | 2 |
| GET | `/api/candidates?status=REVIEW_REQUIRED` | 검토 대상 목록 | 3 |
| POST | `/api/candidates/{id}/approve` · `/reject` | 검토 처리 (body: reviewer, comment) | 3 |

- 오류 응답은 RFC 9457 Problem Details 형식을 따른다(Spring 기본 지원).
- 수동 재수집은 비동기로 실행하고 수집 ID를 즉시 반환한다(`202 Accepted`).

---

## 8. 시스템 구조 요구사항

하나의 Spring Boot 애플리케이션 안에서 패키지 단위로 역할을 분리한다.

```
Scheduler/Orchestrator ─▶ Collector ─▶ Extractor ─▶ Normalizer ─▶ Validator ─▶ EOL Service ─▶ DB
                                         (기본: AI / 예외: 전용 Parser)
                                         ※ AI는 후보만 생성, DB 직접 수정 금지
```

| 구성요소 | 책임 |
|----------|------|
| Collector | 외부에서 원본 데이터를 가져온다. 수집 방식별 공통 구현 |
| Extractor | 원본에서 버전·EOL 정보를 추출한다. 기본은 AI, AI로 부정확한 출처만 전용 Parser 허용 |
| Normalizer | 추출 결과를 공통 후보 형식으로 변환하고 날짜·정밀도를 정규화한다 |
| Validator | 필수값, 출처, 버전, 날짜, 기존 데이터와의 일관성을 확인한다 |
| EOL Service | 승인된 데이터를 저장하고 변경 이력을 기록한다. 현재 데이터를 수정하는 유일한 경로 |
| Scheduler / Orchestrator | 수집 실행 시점과 순서를 관리한다 |

**확장 원칙**
- 새 Opensource 추가는 원칙적으로 **코드 변경 없이** `opensource` + `source_registry` 데이터 추가로 가능해야 한다(공통 Collector + AI 추출로 처리 가능한 경우).
- AI 추출이 부정확한 경우에만 전용 Parser를 추가하고 `source_registry.parser_key`로 연결한다.
- 동적 플러그인 로딩은 구현하지 않는다.

### 8.1 기술 스택 및 배포 (Q6 확정)

| 구분 | 결정 |
|------|------|
| 백엔드 | Kotlin, Spring Boot 4.1, Java 21 (eol-poc-backend) |
| DB | PostgreSQL. 스키마는 Flyway 마이그레이션으로 관리하고 JSON 컬럼은 `jsonb`를 사용한다. |
| 데이터 접근 | Spring Data JPA. 목록 요약 같은 집계는 JPQL 또는 네이티브 쿼리로 처리한다. |
| 테스트 | DB가 필요한 테스트는 Testcontainers(PostgreSQL)를 사용한다. |
| 프런트엔드 | 별도 레포의 SPA. 백엔드는 REST API만 제공한다. |
| 배포 | 사내 서버 1대에 Docker 이미지로 배포한다(애플리케이션 + PostgreSQL). 로컬 개발은 Docker Compose로 DB를 실행한다. |
| AI | Azure OpenAI(v1 API, 구조화 출력 지원 모델). 엔드포인트와 API 키는 환경 변수로 주입한다. (Q7 확정 후 사내망 제약으로 변경) |

---

## 9. 비기능 요구사항

| ID | 구분 | 요구사항 |
|----|------|----------|
| NFR-01 | 추적성 | 화면에 표시되는 모든 날짜는 근거(출처 URL, 문구, 확인 시각)로 역추적할 수 있어야 한다. |
| NFR-02 | 멱등성 | 같은 원본으로 수집을 반복해도 현재 데이터와 변경 이력이 바뀌지 않는다. |
| NFR-03 | 장애 격리 | 출처 하나의 실패가 다른 수집과 기존 데이터에 영향을 주지 않는다. |
| NFR-04 | 성능 | 목록·상세 API는 POC 데이터 규모(6개 Opensource, 수백 버전)에서 응답 1초 이내. |
| NFR-05 | 외부 예의 | 출처별 요청 간격을 두고, robots.txt 및 이용 약관을 확인한다. |
| NFR-06 | 설정 | EOL 임박 기준일, 수집 cron, 타임아웃, AI 모델 ID·프롬프트 버전은 `application.yaml`로 관리한다. |
| NFR-07 | 보안 | API 키 등 비밀값은 환경 변수로 주입하고 저장소에 커밋하지 않는다. 원본 HTML은 화면에 그대로 렌더링하지 않는다(XSS 방지). |
| NFR-08 | 로깅 | 수집 실행마다 수집 ID를 로그 상관 키로 사용한다. |
| NFR-09 | 테스트 | 판정 규칙(3.1)과 검증 규칙(4.3)은 단위 테스트로 검증한다. AI 추출은 저장된 원본 샘플(fixture)과 기대 결과로 대상별 정확도를 확인한다(실제 API 호출, 정기 테스트에서는 제외 가능). |
| NFR-10 | 연동 | 프런트엔드 SPA의 출처(origin)를 설정값으로 받아 CORS를 허용한다. |

---

## 10. 초기 대상 및 출처 후보

관리 대상 버전은 **출처에 나온 버전 전부**다(Q4). 버전 단위는 endoflife.date 표의 Release 값을 그대로 쓴다.

| 순위 | Opensource (slug) | 버전 단위(예) | 출처 | 비고 |
|------|----------|-----------|-----------|------|
| 1 | PostgreSQL (`postgresql`) | `18`, `9.6` | `https://endoflife.date/postgresql` | |
| 2 | OpenSSL (`openssl`) | `3.5`, `1.1.1` | `https://endoflife.date/openssl` | |
| 3 | Apache Tomcat (`tomcat`) | `11.0`, `10.1`, `7` | `https://endoflife.date/tomcat` | |
| 4 | OpenSSH (`openssh`) | - | 없음 | endoflife.date에 페이지가 없어 수집하지 않는다(출처 비활성화) |
| 5 | Linux kernel (`linux`) | `6.12` | `https://endoflife.date/linux` | |
| 6 | Spring Boot (`spring-boot`) | `3.5` | `https://endoflife.date/spring-boot` | OSS 지원 종료일만 수집, 상용 지원 종료일 제외(Q8) |

- 출처는 endoflife.date(`https://endoflife.date/{slug}`)로 통일하고 `OFFICIAL`로 취급한다. 본문은 앱이 직접 가져와 텍스트로 바꾼다. endoflife.date에 페이지가 없는 Opensource(예: OpenSSH)는 수집하지 않는다.

---

## 11. 결정 사항

| ID | 질문 | 결정 |
|----|------|-----------|
| Q1 | EOL 상태 판정에 일반 지원 종료일과 보안 지원 종료일 중 무엇을 쓰는가? 보안 지원만 남은 기간을 별도 상태로 표시할 필요가 있는가? | **확정**: 일반 지원 종료일 기준. 보안 지원 종료일은 판정에 사용하지 않고 별도 표시만 한다. |
| Q2 | 출처마다 종료일이 다를 때(예: 공식 페이지 vs 공지) 우선순위는? | **확정**: `OFFICIAL` 출처 값만 자동 승인 대상. `OFFICIAL` 출처끼리 값이 다르면 `REVIEW_REQUIRED` (VAL-12) |
| Q3 | "2027년 11월"처럼 월 단위로만 제공되는 날짜를 어떻게 저장하는가? | **확정**: 해당 기간의 마지막 날로 저장하고 정밀도 컬럼(`DAY`/`MONTH`/`YEAR`)을 함께 저장한다. 자동 승인 가능하며, 화면에는 정밀도에 맞춰 표시한다. |
| Q4 | 관리 대상 버전의 범위: 출처에 나오는 모든 과거 버전인가, 일정 시점 이후인가? | **확정**: 출처에 나오는 모든 버전을 저장한다(STO-08). 최소 버전 필터는 두지 않는다. |
| Q5 | 인증·권한: POC 대시보드에 로그인이 필요한가? 검토 승인자 기록 방식은? | **확정**: 인증 없음(내부망 POC 가정). 승인자는 요청 body의 `reviewer` 값으로 기록하고, 없으면 `admin`으로 기록한다. |
| Q6 | DB 종류 (PostgreSQL / H2)와 프런트엔드 기술 스택, 배포 형태 | **확정**: PostgreSQL + Flyway, Spring Data JPA, 프런트엔드는 별도 SPA 레포, 사내 서버 1대에 Docker로 배포 (8.1절) |
| Q7 | AI 사용 범위와 비용 한도, 사용할 모델 | **확정**: 모든 출처의 원본을 AI로 추출(전용 Parser는 예외), Claude Sonnet 5.5 → 사내망 제약으로 Azure OpenAI로 변경, 비용 제한 없음. 신규 값은 근거 확인 후 자동 승인, 기존 값 변경은 검토 (AI-01~07, VAL-08·09) |
| Q8 | Spring Boot의 상용(Enterprise) 지원 종료일도 관리하는가? | **확정**: OSS 지원만 관리한다. 상용(Enterprise) 지원 종료일은 저장하지 않으며, AI 프롬프트에 상용 지원 날짜를 추출하지 않도록 명시한다. |

---

## 12. POC 완료 기준 (인수 조건)

1. 초기 대상 6종 중 **최소 4종**이 자동 수집되어 화면 1~3에서 조회된다.
2. 화면 2·3에 표시되는 모든 날짜에 출처 URL과 근거 문구가 연결되어 있다.
3. 같은 원본으로 두 번 수집했을 때 변경 이력이 추가로 생성되지 않는다.
4. 출처 값을 바꾼 원본 fixture로 수집하면 변경 전·후 값이 변경 이력에 기록된다.
5. 출처를 일부러 실패시켜도 기존 EOL 상태가 그대로 유지되고, 목록 화면에 수집 실패가 표시된다.
6. 종료일이 기준일 + 90일 이내인 버전은 `EOL_SOON`, 설정값을 바꾸면 판정이 바뀐다.
7. 날짜를 확인할 수 없는 버전은 `NULL` / `UNKNOWN`으로 표시되며 임의 값이 없다.
8. AI로 추출한 후보 중 근거 문구가 원본에 없거나 날짜가 근거 문구와 다르면 `REJECTED`, 이미 승인된 값이 바뀌면 `REVIEW_REQUIRED`로 처리되고 현재 데이터에 반영되지 않는다. 처음 저장되는 값은 자동 승인된다.
9. 수동 재수집을 실행하면 새 수집 실행 레코드가 생성되고 결과가 화면에 반영된다.
10. 새 Opensource 1종을 코드 변경 없이(공통 Collector + AI 추출) 데이터 등록만으로 수집할 수 있음을 시연한다.
