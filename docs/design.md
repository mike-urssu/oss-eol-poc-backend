# OSS EOL 관리 플랫폼 POC 설계 문서

| 항목 | 내용 |
|------|------|
| 문서 버전 | 0.1 (초안) |
| 작성일 | 2026-10-11 |
| 기준 문서 | [요구사항 명세서](requirements.md) v0.2 |
| 대상 | eol-poc-backend (Kotlin, Spring Boot 4.1, Java 21) |

이 문서는 요구사항 명세서를 구현하기 위한 백엔드 설계를 정의한다. 요구사항 ID(`COL-01`, `VAL-08` 등)는 명세서를 따른다.

---

## 1. 설계 개요

### 1.1 설계 원칙
1. **현재 데이터를 바꾸는 경로는 하나다.** `EolService.apply()`만 `oss_version`·`oss_lifecycle`을 수정한다. Collector, AI, 컨트롤러는 직접 수정하지 않는다.
2. **AI는 판단하지 않고 추출만 한다.** AI 출력의 모든 값은 원문 근거 문구로 코드에서 다시 검증한다(4.4절).
3. **시간에 따라 바뀌는 값은 저장하지 않는다.** EOL 상태는 조회 시점에 계산한다.
4. **DB가 지킬 수 있는 규칙은 DB에 둔다.** enum 값은 CHECK 제약, 중복 방지는 UNIQUE 제약으로 보장한다.
5. **POC 규모에 맞게 단순하게 만든다.** 단일 인스턴스를 전제로 하고, 확장 시 바꿔야 할 부분은 10장에 기록한다.

### 1.2 전체 구조

```
                ┌──────────────────────── eol-poc-backend (Spring Boot 단일 애플리케이션) ─────────────────────────┐
 Scheduler ─┐   │                                                                                                 │
 (cron)     ├─▶ │ CollectionOrchestrator ─▶ Collector ─▶ Extractor ─▶ Normalizer ─▶ Validator ─▶ EolService ─▶ DB │
 수동 재수집 ─┘   │                           (Web/Api)    (AI 기본)                                    ▲             │
 (REST API)     │                                          │                                         │             │
                │                                   Anthropic API                         ReviewService(검토 승인)  │
                │                                                                                                 │
 SPA ─────────▶ │ 조회 API ─▶ QueryService ─▶ EolStatusCalculator (조회 시 계산)                                    │
                └─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 1.3 요구사항을 구체화하며 정한 설계 결정

| ID | 결정 | 이유 |
|----|------|------|
| D1 | 버전의 "검증 상태"는 컬럼으로 저장하지 않고 후보 이력에서 계산한다(6.3절). `oss_lifecycle.validation_status` 컬럼은 두지 않는다. | 현재 데이터는 정의상 승인된 값이므로, 화면의 검증 상태는 "최근 후보의 상태"를 의미한다. 저장하면 후보와 값이 어긋날 수 있다. |
| D2 | 검증 상태는 후보(버전 1건) 단위로 정하며, 필드 중 가장 엄격한 결과를 따른다. | 필드 단위 부분 반영은 근거·이력 처리가 복잡해진다. |
| D3 | VAL-09(AI 값 변경)·VAL-10(이상 변경)·VAL-12(출처 간 충돌)는 하나의 규칙 `VALUE_CHANGED`로 구현한다. | 모든 추출이 AI이므로 "승인된 값과 다르면 검토"로 세 규칙이 모두 충족된다. 같은 수집 주기의 출처 간 충돌은 출처를 순서대로 처리하여 앞 출처 값이 승인된 뒤 비교되도록 한다. |
| D4 | `LATEST_PATCH_VERSION` 변경은 `VALUE_CHANGED` 대상에서 제외한다. 근거 확인만 통과하면 자동 승인하고 변경 이력은 남긴다. | 패치 릴리스마다 검토가 생기면 검토 큐가 의미를 잃는다. EOL 판단과 무관한 값이다. |
| D5 | 콘텐츠 해시는 원본 HTML이 아니라 **AI에 전달한 텍스트**로 계산하고, 프롬프트 버전이 같을 때만 생략한다. | HTML에는 매 요청 바뀌는 토큰·타임스탬프가 섞여 해시가 매번 달라질 수 있다. 프롬프트를 바꾸면 다시 추출해야 한다. |
| D6 | 근거의 `source_url`은 AI 출력이 아니라 **코드가 출처 설정에서 채운다**. | AI가 URL을 만들어내는 것을 원천 차단한다. |
| D7 | AI 모델 ID·프롬프트 버전·토큰 사용량은 후보가 아니라 `eol_collection`에 기록한다(수집 1회 = AI 호출 1회). 후보는 `collection_id`로 참조한다. | 같은 값의 중복 저장 방지 |
| D8 | 근거 테이블에 `value`와 `source_id`를 추가하고 `(version_id, field, source_id, value)`를 UNIQUE로 둔다. | "이 근거가 어떤 값을 뒷받침하는가"를 알아야 현재 값의 근거만 보여줄 수 있고, 같은 값 재확인 시 행을 늘리지 않고 확인 시각만 갱신할 수 있다(STO-04). |
| D9 | 같은 출처·같은 버전에 미처리 검토 후보가 있는데 새 검토 후보가 생기면 이전 후보는 `REJECTED`(사유 `SUPERSEDED`)로 닫는다. | 매일 같은 검토 건이 쌓이는 것을 막는다(REV-03을 별도 상태 없이 구현). |
| D10 | 처음 발견된 버전은 후보가 `REJECTED`가 아니면(승인 또는 검토 필요) 즉시 `oss_version` 행을 만든다. 검토 필요인 경우 값은 비어 있다. | STO-08. 검토 대기 중인 신규 버전도 화면에 "미확인 / 검토 필요"로 보여야 한다. |
| D11 | 동시 수집 방지는 JVM 메모리 내 잠금으로 구현한다. | 사내 서버 1대 배포(Q6). |

---

## 2. 패키지 구조

기능 단위로 패키지를 나눈다. 루트 패키지는 `cloud.jjoon.eolpoc`이다.

```
cloud.jjoon.eolpoc
├── opensource/    Opensource·Source 엔티티, 리포지토리, 등록·수정 API
├── collection/    CollectionOrchestrator, CollectionScheduler, Collector(Web/Api), HtmlToText, Collection 엔티티, 수집 API
├── extraction/    Extractor 인터페이스, AiExtractor, 출력 스키마, 프롬프트, Normalizer
├── validation/    Validator, 검증 규칙, QuoteVerifier, DateMentionParser
├── lifecycle/     Version·Lifecycle·Evidence·ChangeHistory 엔티티, EolService, EolStatusCalculator, VersionComparator, 조회 API
├── candidate/     Candidate 엔티티, ReviewService, 검토 API
└── common/        EolProperties(설정), Clock, 오류 처리(ProblemDetail), CORS
```

- 인터페이스는 구현이 둘 이상일 때만 만든다. 현재는 `Extractor`(AiExtractor + 테스트용 Fake, 향후 전용 Parser)와 `Collector`(Web/Api) 두 개뿐이다.
- 전용 Parser(COL-02, P2)는 필요해질 때 `extraction/parser/`에 추가하고 `source_registry.parser_key`로 선택한다.

---

## 3. 처리 흐름

### 3.1 수집 파이프라인 (출처 1개 기준)

```
Orchestrator            Collector          Extractor(AI)        Normalizer/Validator          EolService
    │ ① collection 생성        │                   │                       │                         │
    │   (status=NULL)          │                   │                       │                         │
    │── fetch(source) ───────▶│                   │                       │                         │
    │◀─ raw, contentText ──────│                   │                       │                         │
    │ ② hash(contentText)+promptVersion 비교                                │                         │
    │   같으면 → 확인 시각만 갱신, SUCCESS(skipped) 종료                       │                         │
    │── extract(text) ─────────────────────────────▶│                       │                         │
    │◀─ ExtractionResult ───────────────────────────│                       │                         │
    │── normalize + validate (후보별) ───────────────────────────────────────▶│                         │
    │◀─ 후보 + 검증 결과 ─────────────────────────────────────────────────────│                         │
    │── [트랜잭션] 후보 저장, APPROVED 후보 apply ─────────────────────────────────────────────────────▶│
    │ ③ collection 종료 (SUCCESS, candidate_count)                            │                         │
    │   예외 발생 시 → collection 종료 (FAILED, error_message), 현재 데이터 변경 없음                         │
```

| 단계 | 처리 | 트랜잭션 |
|------|------|----------|
| ① | `eol_collection` 행 생성(`status=NULL`, `started_at`) | 단독 |
| fetch, extract | 외부 HTTP·AI 호출. **트랜잭션 밖에서** 실행하여 DB 커넥션을 오래 잡지 않는다. | 없음 |
| 저장·반영 | 후보 저장 → 검증 → `APPROVED` 후보 `apply` → 수집 종료 기록 | **수집 1건 = 트랜잭션 1개** (STO-09) |
| 실패 | 예외를 잡아 `FAILED`, `error_message` 기록 | 단독 (반영 트랜잭션은 롤백) |

- 한 Opensource의 출처들은 `source_registry.id` 오름차순으로 **순차** 처리한다(D3). 한 출처의 실패는 다음 출처 처리를 막지 않는다(COL-08).
- 진행 중인 수집은 `finished_at IS NULL`로 구분한다. 수집 상태는 종료 시점에만 `SUCCESS`/`FAILED`로 정해진다.
- 애플리케이션 기동 시(`ApplicationReadyEvent`) `finished_at IS NULL`인 수집을 `FAILED`(`INTERRUPTED`)로 정리한다.
- 로그 MDC에 `collectionId`를 넣는다(NFR-08).

### 3.2 수집 실행 트리거

| 트리거 | 동작 |
|--------|------|
| 정기 (SCH-01) | `@Scheduled(cron = "\${eol.collection.cron}", zone = "\${eol.zone}")`. 활성 Opensource를 순서대로 수집한다. 이미 수집 중인 Opensource는 건너뛴다. |
| 수동 (SCH-02) | `POST /api/opensources/{slug}/collections`. 잠금 획득 → 대상 출처별 `eol_collection` 행을 **동기로 생성** → ID 목록을 `202`로 반환 → 실제 처리는 비동기(가상 스레드)로 실행한다. |

**동시 실행 방지 (SCH-03)**: `ConcurrentHashMap.newKeySet<Long>()`에 opensourceId를 `add`하여 성공한 경우만 실행하고 `finally`에서 제거한다. 실패하면 수동 요청은 `409 Conflict`를 반환한다.

---

## 4. 컴포넌트 상세

### 4.1 Collector

```kotlin
interface Collector {
    val type: CollectorType               // API, WEB
    fun fetch(source: Source): Fetched
}

data class Fetched(
    val httpStatus: Int,
    val rawContent: String,     // 응답 본문 원문 (eol_collection.raw_content)
    val contentText: String,    // AI에 전달할 텍스트 (eol_collection.content_text)
)
```

| 구현 | 처리 |
|------|------|
| `WebCollector` | `RestClient` GET → Jsoup 파싱 → `config.selector`가 있으면 해당 영역만 선택 → `HtmlToText`로 텍스트 변환 |
| `ApiCollector` | `RestClient` GET → 응답 본문(JSON)을 그대로 `contentText`로 사용 |
| `GitHubCollector` | 대상 중 필요한 출처가 생길 때 추가 |

- 공통 HTTP 설정(COL-07): 연결 5초·읽기 30초 타임아웃, `User-Agent` 설정값, 2xx 외 응답은 예외. 재시도는 Spring Framework 7의 `@Retryable`(최대 2회, 지수 백오프)을 사용한다.
- **`HtmlToText`**: `script`, `style`, `nav`, `footer`를 제거하고, 블록 요소와 `<br>`, `<tr>`은 줄바꿈, `<td>`/`<th>`는 ` | `로 이어 붙인다. 표의 행 구조를 유지해야 AI가 "버전 ↔ 종료일" 대응을 정확히 읽는다.
- JavaScript로 렌더링되는 페이지는 수집할 수 없다. 해당 출처는 공식 API나 다른 공식 페이지로 대체한다(11장 R4).

**출처 설정(`source_registry.config`, jsonb)**

| 키 | 필수 | 용도 |
|----|------|------|
| `selector` | 아니요 | CSS 선택자. 본문 범위를 좁혀 토큰과 오추출을 줄인다(AI-06). |
| `versionHint` | 아니요 | AI 프롬프트에 전달할 버전 단위 설명. 예: `"메이저 버전(예: 16). 16.4 같은 패치 버전은 latestPatchVersion으로"` |
| `versionPattern` | 아니요 | VAL-06 정규식. 예: `^\d+$` |

### 4.2 Extractor (AI)

```kotlin
interface Extractor {
    fun extract(opensource: Opensource, source: Source, contentText: String): Extraction
}

data class Extraction(
    val result: ExtractionResult,
    val model: String,          // 응답의 실제 모델 ID (fallback 시 달라질 수 있음)
    val promptVersion: String,
    val inputTokens: Long,
    val outputTokens: Long,
)
```

**AI 출력 스키마** (구조화 출력으로 강제)

```kotlin
data class ExtractionResult(val versions: List<ExtractedVersion>)

data class ExtractedVersion(
    val version: String,
    val latestPatchVersion: Quoted?,   // value: "16.4"
    val releaseDate: Quoted?,          // value: "yyyy-MM-dd" | "yyyy-MM" | "yyyy"
    val eolDate: Quoted?,
    val securityEolDate: Quoted?,
    val support: Quoted?,              // value: "SUPPORTED" | "EOL"
)

data class Quoted(val value: String, val quote: String)
```

**API 호출**

| 항목 | 값 |
|------|----|
| SDK | `com.anthropic:anthropic-java` (2.34.0), `AnthropicOkHttpClient.fromEnv()` — `ANTHROPIC_API_KEY` 환경 변수 사용 |
| 모델 | `claude-sonnet-5-5` (설정값 `eol.ai.model`) |
| 구조화 출력 | `MessageCreateParams.builder().outputConfig(ExtractionResult::class.java)`로 스키마를 강제하고 타입으로 받는다. Kotlin 클래스의 스키마 생성이 맞지 않으면 `JsonOutputFormat`으로 스키마를 직접 지정한다. |
| thinking / effort | `thinking`은 지정하지 않는다(adaptive). `effort`는 설정값, 기본 `medium`. fixture 정확도(9장)를 보고 조정한다. |
| max_tokens | 기본 16000(설정값). 비스트리밍 호출. 버전이 많은 출처에서 부족하면 스트리밍 + 64000으로 바꾼다. |
| 입력 한도 | 컨텍스트 1M 토큰. 초과 위험이 있는 출처는 `selector`로 범위를 줄인다(AI-06). |
| 재시도 | SDK 기본 재시도(2회, 429/5xx/연결 오류) |
| 거절 대비 | Claude API의 server-side fallback(`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`)을 켠다. Java SDK에서의 지정 방법은 구현 시 확인한다. 실제 응답 모델을 `ai_model`에 기록한다. |

**응답 처리 (AI-05)**

| 상황 | 처리 |
|------|------|
| `stop_reason = end_turn` | 정상 |
| `stop_reason = max_tokens` | 수집 `FAILED` (`AI_OUTPUT_TRUNCATED`) — 잘린 결과는 쓰지 않는다 |
| `stop_reason = refusal` | 수집 `FAILED` (`AI_REFUSED`) |
| API 예외, 스키마 불일치 | 수집 `FAILED` (`AI_ERROR`) |

**프롬프트** (`src/main/resources/prompts/extract-v1.md`, 프롬프트 버전 = 파일명)

시스템 프롬프트에 담을 지시:
1. 주어진 문서에 **적힌 내용만** 추출한다. 문서 밖 지식, 정책 문장으로부터의 날짜 계산(예: "5년간 지원")은 하지 않는다.
2. 값마다 근거 문구(`quote`)를 문서에서 **그대로 복사**한다. 한 문장 또는 표의 한 행 이내로 짧게 쓴다.
3. 날짜는 문서의 정밀도대로 `yyyy-MM-dd`, `yyyy-MM`, `yyyy` 중 하나로 쓴다.
4. 문서에 없는 값은 `null`로 둔다.
5. 상용(Enterprise/Commercial) 지원 날짜는 추출하지 않는다(Q8).
6. 버전 단위는 `versionHint`를 따른다.

사용자 메시지: Opensource 이름, 출처 URL, `versionHint`, 그리고 `<document>` 태그로 감싼 `contentText`.

### 4.3 Normalizer

AI 출력(`ExtractedVersion`)을 후보(`eol_candidate`) 형태로 바꾼다. **예외를 던지지 않고** 문제는 `formatIssues`로 넘겨 Validator가 판정한다.

| 대상 | 규칙 |
|------|------|
| 버전 | 앞뒤 공백 제거, 앞의 `v`/`V` 제거 |
| 날짜 | `yyyy-MM-dd` → (그 날짜, `DAY`), `yyyy-MM` → (그 달 말일, `MONTH`), `yyyy` → (12월 31일, `YEAR`). 그 외 형식은 `formatIssues`에 추가 |
| 지원 여부 | `SUPPORTED`, `EOL` 외 값은 `formatIssues`에 추가 |
| 근거 | 필드별 `{field, value, quote}` 목록으로 정리. `sourceUrl`은 출처 설정의 URL로 채운다(D6) |
| 중복 버전 | 같은 결과 안에서 같은 버전이 두 번 나오면 두 번째는 `DUPLICATE_VERSION`으로 거부 |

### 4.4 Validator

후보 1건에 규칙을 모두 적용하고 결과 중 가장 엄격한 것(`REJECTED` > `REVIEW_REQUIRED` > `APPROVED`)을 검증 상태로 정한다. 사유는 `validation_reasons`(jsonb)에 `[{code, field, message}]`로 모두 기록한다.

| 코드 | 요구사항 | 조건 | 결과 |
|------|----------|------|------|
| `REQUIRED_MISSING` | VAL-01 | 버전이 비어 있음 | `REJECTED` |
| `EVIDENCE_MISSING` | VAL-02 | 값이 있는데 `quote`가 비어 있음 | `REJECTED` |
| `DATE_FORMAT` | VAL-03 | Normalizer의 `formatIssues` 존재 | `REJECTED` |
| `DATE_ORDER` | VAL-04 | 릴리스일 > 일반 또는 보안 지원 종료일 | `REJECTED` |
| `DATE_SUSPICIOUS` | VAL-05 | 릴리스일 > 오늘, 종료일 < 1990-01-01, 종료일 > 오늘 + 15년 | `REVIEW_REQUIRED` |
| `VERSION_PATTERN` | VAL-06 | `config.versionPattern`과 불일치 | `REJECTED` |
| `SOURCE_SECONDARY` | VAL-07 | 출처 `trust_level = SECONDARY` | `REVIEW_REQUIRED` |
| `QUOTE_NOT_FOUND` | VAL-08 | `quote`가 `contentText`에 없음 | `REJECTED` |
| `QUOTE_VALUE_MISMATCH` | VAL-08 | 값이 `quote`에서 확인되지 않음 | `REJECTED` |
| `VALUE_CHANGED` | VAL-09/10/12 | 날짜·지원 여부 필드에서 승인된 값이 있고(`NOT NULL`) 후보 값과 다름(`null`로 바뀌는 경우 포함, 정밀도만 바뀌는 경우 포함) | `REVIEW_REQUIRED` |
| `DUPLICATE_VERSION` | — | 4.3절 | `REJECTED` |

- 출처는 등록된 출처에서만 수집하므로 VAL-07의 "미등록 출처" 경우는 구조적으로 발생하지 않는다.
- VAL-11(변경 없음)은 규칙이 아니라 `EolService.apply()`에서 처리한다. 모든 값이 같으면 이력 없이 확인 시각만 갱신한다.

**QuoteVerifier (VAL-08)**

1. 정규화: NFKC 정규화 → 각종 대시(`–`, `—`)를 `-`로, NBSP를 공백으로 → 연속 공백을 하나로 → 소문자.
2. 존재 확인: `normalize(contentText).contains(normalize(quote))`
3. 값 확인:
   - 날짜 필드: `DateMentionParser.parse(quote)`가 찾은 (날짜, 정밀도) 목록에 후보 값이 포함되어야 한다.
   - `LATEST_PATCH_VERSION`: 정규화된 `quote`에 버전 문자열이 포함되어야 한다.
   - `SUPPORT`: 존재 확인만 한다.

**DateMentionParser** — 문구 안의 날짜 표현을 모두 찾아 Normalizer와 같은 규칙(기간 말일 + 정밀도)으로 변환한다.

| 형식 | 예 | 정밀도 |
|------|----|--------|
| `yyyy-MM-dd`, `yyyy/MM/dd`, `yyyy.MM.dd` | `2028-11-09` | DAY |
| 영문 월 이름(전체·약어, 서수 접미사 허용) | `November 9, 2028`, `9 Nov 2028`, `Nov 9th 2028` | DAY |
| 한글 | `2028년 11월 9일` | DAY |
| 월·연 | `November 2028`, `2028-11`, `2028년 11월` | MONTH |
| 연도 단독 | `2028` | YEAR |

지원하지 않는 형식(예: `Q4 2027`)은 `QUOTE_VALUE_MISMATCH`로 거부된다. 거부 사유를 모니터링하여 형식을 추가한다(11장 R2).

### 4.5 EolService

```kotlin
@Transactional
fun apply(candidate: Candidate, changedBy: String)   // changedBy: "SYSTEM" 또는 검토자
```

1. `oss_version`을 `(opensource_id, version)`으로 조회하고 없으면 생성한다(STO-08).
2. `oss_lifecycle`을 조회하고 없으면 생성한다.
3. 필드(`RELEASE_DATE`, `LATEST_PATCH_VERSION`, `EOL_DATE`, `SECURITY_EOL_DATE`, `SUPPORT`)마다:
   - 날짜는 (날짜, 정밀도) 쌍으로 비교한다.
   - 다르면 값을 바꾸고 `eol_change_history`에 이전 값과 새 값을 표시 형식(`2027-11`, `2028-11-09`, `EOL`)으로 기록한다(STO-03).
   - 새 값이 `null`이 아니면 `eol_evidence`를 `(version_id, field, source_id, value)` 기준으로 upsert한다(`ON CONFLICT DO UPDATE SET verified_at = now()`)(D8, STO-05).
4. `oss_lifecycle.last_verified_at = now()`.

같은 값이 다시 수집되면 3단계에서 이력이 생기지 않고 근거의 `verified_at`만 갱신된다(STO-04, NFR-02).

**해시가 같아 추출을 생략한 경우**: 그 출처의 근거 `verified_at`과, 그 출처 근거가 있는 버전의 `last_verified_at`만 갱신한다.

### 4.6 EolStatusCalculator

순수 함수로 구현하고 `Clock`을 주입받는다(기준일 = `eol.zone` 기준 오늘).

```kotlin
data class EolJudgement(val status: EolStatus, val rule: String, val message: String)

fun judge(lifecycle: Lifecycle?, today: LocalDate, thresholdDays: Int): EolJudgement
```

| 순서 | 규칙 코드 | 조건 | 상태 | 메시지 예 |
|------|-----------|------|------|-----------|
| 1 | `EOL_DATE_PASSED` | `eol_date < today` | `EOL` | 종료일(2025-11-13)이 지남 |
| 2 | `EOL_DATE_WITHIN_THRESHOLD` | `eol_date - today ≤ threshold` | `EOL_SOON` | 종료일까지 45일 (기준 90일) |
| 3 | `EOL_DATE_FUTURE` | 그 외 종료일 있음 | `SUPPORTED` | 종료일까지 812일 |
| 4 | `DECLARED_EOL` | 종료일 없음, `support_declared = EOL` | `EOL` | 출처에 지원 종료 명시 |
| 5 | `DECLARED_SUPPORTED` | 종료일 없음, `support_declared = SUPPORTED` | `SUPPORTED` | 출처에 지원 중 명시 |
| 6 | `NO_INFORMATION` | 그 외 | `UNKNOWN` | 판단할 정보 없음 |

보안 지원 종료일은 판정에 사용하지 않는다(Q1). 화면 3의 판정 사유(UI3-03)는 `message`를 그대로 보여준다.

### 4.7 VersionComparator

버전 정렬(UI2-02)과 "최신 확인 버전" 계산에 사용한다. `.`, `-`, `_`로 나눈 토큰을 앞에서부터 비교하며, 숫자 토큰은 숫자로, 그 외는 문자열로 비교한다. 앞부분이 같으면 토큰이 적은 쪽이 작다.

**최신 확인 버전** = 그 Opensource의 모든 버전에서 `latest_patch_version ?: version`의 최댓값.

### 4.8 ReviewService

| 동작 | 처리 |
|------|------|
| 승인 | 후보가 `REVIEW_REQUIRED`이고 미처리(`reviewed_at IS NULL`)인지 확인(아니면 `409`) → `EolService.apply(candidate, reviewer)` → `APPROVED`, `reviewed_by`, `review_comment`, `reviewed_at` 기록 |
| 거부 | 같은 확인 → `REJECTED`와 검토 정보 기록. 현재 데이터 변경 없음 |
| 대체(D9) | 새 `REVIEW_REQUIRED` 후보 저장 시, 같은 출처·같은 버전의 미처리 후보를 `REJECTED` + 사유 `SUPERSEDED`로 닫는다 |

`reviewer`가 없으면 `admin`을 기록한다(Q5). 승인 시점의 현재 값과 비교하여 반영하므로, 후보 생성 이후 값이 바뀌었더라도 이력은 정확하게 남는다.

---

## 5. 데이터 모델

### 5.1 테이블 관계

| 부모 | 자식 | 관계 |
|------|------|------|
| `opensource` | `source_registry`, `oss_version`, `eol_collection`, `eol_candidate` | 1:N |
| `source_registry` | `eol_collection`, `eol_evidence` | 1:N |
| `oss_version` | `oss_lifecycle` | 1:1 |
| `oss_version` | `eol_evidence`, `eol_change_history` | 1:N |
| `eol_collection` | `eol_candidate`, `eol_evidence` | 1:N |
| `eol_candidate` | `eol_change_history` | 1:N |

### 5.2 DDL (Flyway `V1__init.sql`)

```sql
CREATE TABLE opensource (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug          TEXT        NOT NULL UNIQUE,
    name          TEXT        NOT NULL,
    homepage_url  TEXT,
    description   TEXT,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE source_registry (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    opensource_id  BIGINT      NOT NULL REFERENCES opensource(id),
    url            TEXT        NOT NULL,
    collector_type TEXT        NOT NULL CHECK (collector_type IN ('API', 'WEB', 'GITHUB')),
    parser_key     TEXT,
    trust_level    TEXT        NOT NULL CHECK (trust_level IN ('OFFICIAL', 'SECONDARY')),
    config         JSONB       NOT NULL DEFAULT '{}',
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (opensource_id, url)
);

CREATE TABLE oss_version (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    opensource_id          BIGINT      NOT NULL REFERENCES opensource(id),
    version                TEXT        NOT NULL,
    latest_patch_version   TEXT,
    release_date           DATE,
    release_date_precision TEXT CHECK (release_date_precision IN ('DAY', 'MONTH', 'YEAR')),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (opensource_id, version),
    CHECK ((release_date IS NULL) = (release_date_precision IS NULL))
);

CREATE TABLE oss_lifecycle (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id                  BIGINT      NOT NULL UNIQUE REFERENCES oss_version(id),
    eol_date                    DATE,
    eol_date_precision          TEXT CHECK (eol_date_precision IN ('DAY', 'MONTH', 'YEAR')),
    security_eol_date           DATE,
    security_eol_date_precision TEXT CHECK (security_eol_date_precision IN ('DAY', 'MONTH', 'YEAR')),
    support_declared            TEXT CHECK (support_declared IN ('SUPPORTED', 'EOL')),
    last_verified_at            TIMESTAMPTZ,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((eol_date IS NULL) = (eol_date_precision IS NULL)),
    CHECK ((security_eol_date IS NULL) = (security_eol_date_precision IS NULL))
);

CREATE TABLE eol_collection (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    opensource_id     BIGINT      NOT NULL REFERENCES opensource(id),
    source_id         BIGINT      NOT NULL REFERENCES source_registry(id),
    trigger           TEXT        NOT NULL CHECK (trigger IN ('SCHEDULED', 'MANUAL')),
    status            TEXT CHECK (status IN ('SUCCESS', 'FAILED')),   -- NULL = 진행 중
    skipped_unchanged BOOLEAN     NOT NULL DEFAULT FALSE,
    error_code        TEXT,
    error_message     TEXT,
    http_status       INT,
    raw_content       TEXT,
    content_text      TEXT,
    content_hash      TEXT,
    prompt_version    TEXT,
    ai_model          TEXT,
    ai_input_tokens   BIGINT,
    ai_output_tokens  BIGINT,
    candidate_count   INT,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ
);
CREATE INDEX ON eol_collection (source_id, id DESC);
CREATE INDEX ON eol_collection (opensource_id, id DESC);

CREATE TABLE eol_candidate (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    collection_id               BIGINT      NOT NULL REFERENCES eol_collection(id),
    opensource_id               BIGINT      NOT NULL REFERENCES opensource(id),
    version                     TEXT        NOT NULL,
    latest_patch_version        TEXT,
    release_date                DATE,
    release_date_precision      TEXT,
    eol_date                    DATE,
    eol_date_precision          TEXT,
    security_eol_date           DATE,
    security_eol_date_precision TEXT,
    support_declared            TEXT,
    evidence                    JSONB       NOT NULL,   -- [{field, value, quote}]
    extraction_method           TEXT        NOT NULL CHECK (extraction_method IN ('AI', 'RULE')),
    validation_status           TEXT        NOT NULL CHECK (validation_status IN ('APPROVED', 'REVIEW_REQUIRED', 'REJECTED')),
    validation_reasons          JSONB       NOT NULL DEFAULT '[]',
    reviewed_by                 TEXT,
    review_comment              TEXT,
    reviewed_at                 TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON eol_candidate (opensource_id, version, id DESC);
CREATE INDEX ON eol_candidate (validation_status) WHERE reviewed_at IS NULL;

CREATE TABLE eol_evidence (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id        BIGINT      NOT NULL REFERENCES oss_version(id),
    field             TEXT        NOT NULL CHECK (field IN ('RELEASE_DATE', 'LATEST_PATCH_VERSION', 'EOL_DATE', 'SECURITY_EOL_DATE', 'SUPPORT')),
    value             TEXT        NOT NULL,   -- 표시 형식 (예: 2027-11, 2028-11-09, EOL)
    source_id         BIGINT      NOT NULL REFERENCES source_registry(id),
    source_url        TEXT        NOT NULL,
    quote             TEXT        NOT NULL,
    extraction_method TEXT        NOT NULL CHECK (extraction_method IN ('AI', 'RULE')),
    collection_id     BIGINT      NOT NULL REFERENCES eol_collection(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (version_id, field, source_id, value)
);

CREATE TABLE eol_change_history (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id    BIGINT      NOT NULL REFERENCES oss_version(id),
    field         TEXT        NOT NULL,
    old_value     TEXT,
    new_value     TEXT,
    candidate_id  BIGINT      NOT NULL REFERENCES eol_candidate(id),
    changed_by    TEXT        NOT NULL,
    changed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON eol_change_history (version_id, changed_at DESC);
```

- 요구사항 5.1 대비 변경: `oss_lifecycle.validation_status` 제거(D1), `eol_evidence.value/source_id` 추가(D8), AI 메타데이터를 `eol_collection`으로 이동(D7), `eol_collection.content_text/skipped_unchanged/error_code` 추가(D5), `eol_candidate.opensource_id` 추가(조회용).
- `raw_content`와 `content_text`는 수집마다 저장되므로 커진다. POC에서는 보관 기간을 두지 않는다(10장).

### 5.3 시드 데이터 (REG-03)

`R__seed.sql`(Flyway repeatable migration)에 초기 6종과 출처를 `INSERT ... ON CONFLICT (slug) DO UPDATE`, `ON CONFLICT (opensource_id, url) DO UPDATE`로 작성한다. 파일을 고치면 다음 기동 시 다시 적용되므로, 출처 URL을 확인하며 반복 수정하기 쉽다.

### 5.4 JPA 매핑
- `kotlin("plugin.jpa")`(기본 생성자)과 `allOpen`(`jakarta.persistence.Entity` 등)을 설정한다.
- enum은 `@Enumerated(EnumType.STRING)`, jsonb는 `@JdbcTypeCode(SqlTypes.JSON)`을 사용한다.
- 근거 upsert는 네이티브 쿼리(`ON CONFLICT`)로 작성한다.
- `spring.jpa.open-in-view=false`, `spring.jpa.hibernate.ddl-auto=validate`(스키마는 Flyway만 변경).

---

## 6. API 설계

공통: JSON, 시각은 UTC ISO-8601(`2026-10-11T03:00:12Z`), 날짜는 `{ "date": "2027-11-30", "precision": "MONTH" }` 형태로 내려주고 표시 형식 변환은 프런트엔드가 한다. 오류는 RFC 9457 `ProblemDetail`(400 입력 오류, 404 없음, 409 상태 충돌). 아래 예시의 값은 형식 설명용이다.

### 6.1 화면 1 — `GET /api/opensources?q=&eolStatus=&collectionStatus=`

```json
[
  {
    "slug": "postgresql",
    "name": "PostgreSQL",
    "latestVersion": "17.6",
    "versionCount": 12,
    "eolSummary": { "EOL": 6, "EOL_SOON": 0, "SUPPORTED": 5, "UNKNOWN": 1 },
    "lastCollectedAt": "2026-10-11T03:00:12Z",
    "collectionStatus": "FAILED",
    "collectionError": "HTTP 503 (https://...)"
  }
]
```

- `collectionStatus`: 각 활성 출처의 **가장 최근 완료 수집** 중 하나라도 `FAILED`면 `FAILED`, 모두 성공이면 `SUCCESS`, 수집 이력이 없으면 `null`.
- `lastCollectedAt`: 해당 Opensource 수집의 최대 `finished_at`.
- 필터: `q`는 이름 부분 일치(대소문자 무시), `eolStatus`는 해당 상태 버전이 1개 이상, `collectionStatus`는 위 값 기준.

### 6.2 화면 2 — `GET /api/opensources/{slug}`

```json
{
  "slug": "postgresql",
  "name": "PostgreSQL",
  "homepageUrl": "https://www.postgresql.org",
  "latestVersion": "17.6",
  "sources": [
    { "id": 1, "url": "https://...", "collectorType": "WEB", "trustLevel": "OFFICIAL", "active": true,
      "lastCollection": { "id": 31, "status": "SUCCESS", "skippedUnchanged": true, "finishedAt": "...", "error": null } }
  ],
  "versions": [
    {
      "version": "16",
      "latestPatchVersion": "16.10",
      "releaseDate": { "date": "2023-09-14", "precision": "DAY" },
      "eolDate": { "date": "2028-11-09", "precision": "DAY" },
      "securityEolDate": null,
      "eolStatus": "SUPPORTED",
      "validationStatus": "APPROVED",
      "sourceUrls": ["https://..."]
    }
  ]
}
```

- `versions`는 `VersionComparator` 내림차순.
- `validationStatus`(D1): 이 버전에 미처리 `REVIEW_REQUIRED` 후보가 있으면 `REVIEW_REQUIRED`, 없으면 가장 최근 후보의 상태.
- `sourceUrls`: 현재 값과 일치하는 근거들의 출처 URL(중복 제거).

### 6.3 화면 3 — `GET /api/opensources/{slug}/versions/{version}`

```json
{
  "version": "16",
  "latestPatchVersion": "16.10",
  "releaseDate": { "date": "2023-09-14", "precision": "DAY" },
  "eolDate": { "date": "2028-11-09", "precision": "DAY" },
  "securityEolDate": null,
  "supportDeclared": null,
  "eolStatus": { "status": "SUPPORTED", "rule": "EOL_DATE_FUTURE", "message": "종료일까지 760일" },
  "lastVerifiedAt": "2026-10-11T03:00:12Z",
  "evidence": [
    { "field": "EOL_DATE", "value": "2028-11-09", "sourceUrl": "https://...", "quote": "16 | 16.10 | Yes | September 14, 2023 | November 9, 2028",
      "extractionMethod": "AI", "verifiedAt": "...", "collectionId": 31 }
  ],
  "validation": { "candidateId": 88, "status": "APPROVED", "reasons": [] },
  "pendingReview": {
    "candidateId": 91,
    "reasons": [{ "code": "VALUE_CHANGED", "field": "EOL_DATE", "message": "승인된 값 2028-11-09 → 후보 값 2028-11-16" }],
    "changes": [{ "field": "EOL_DATE", "current": "2028-11-09", "proposed": "2028-11-16", "quote": "..." }]
  },
  "history": [
    { "field": "LATEST_PATCH_VERSION", "oldValue": "16.9", "newValue": "16.10", "changedBy": "SYSTEM", "changedAt": "...", "candidateId": 88 }
  ]
}
```

- `evidence`: 현재 값과 일치하는 근거만(필드·출처별). 근거가 없는 현재 값은 존재할 수 없다(STO-05).
- `pendingReview`: 미처리 검토 후보가 없으면 `null`(UI3-08).

### 6.4 수집

| 메서드·경로 | 요청 | 응답 |
|-------------|------|------|
| `POST /api/opensources/{slug}/collections` | `{ "sourceId": 3 }` (선택) | `202 { "collectionIds": [41, 42] }` / 실행 중이면 `409` |
| `GET /api/opensources/{slug}/collections?limit=20` | — | 수집 목록: `id, sourceId, trigger, status, skippedUnchanged, errorCode, errorMessage, candidateCount, aiModel, aiInputTokens, aiOutputTokens, startedAt, finishedAt` (원본 본문 제외) |
| `GET /api/collections/{id}/content` | — | `{ "rawContent": "...", "contentText": "..." }` — 근거 추적용. 프런트엔드는 반드시 텍스트로 표시한다(NFR-07). |

### 6.5 검토

| 메서드·경로 | 요청 | 응답 |
|-------------|------|------|
| `GET /api/candidates?status=REVIEW_REQUIRED` | — | 미처리 후보 목록: `id, opensourceSlug, version, reasons, changes, createdAt` |
| `POST /api/candidates/{id}/approve` | `{ "reviewer": "kim", "comment": "공지 확인" }` | `200` 처리된 후보 / 미처리 상태가 아니면 `409` |
| `POST /api/candidates/{id}/reject` | 같음 | 같음 |

### 6.6 등록·수정

| 메서드·경로 | 요청 |
|-------------|------|
| `POST /api/opensources` | `{ slug, name, homepageUrl, description }` |
| `PUT /api/opensources/{slug}` | `{ name, homepageUrl, description, active }` |
| `POST /api/opensources/{slug}/sources` | `{ url, collectorType, trustLevel, config, parserKey }` |
| `PUT /api/sources/{id}` | 같음 + `active` |

`slug`는 `^[a-z0-9-]+$`, URL은 `http(s)`만 허용한다. 삭제 API는 두지 않는다(REG-04).

---

## 7. 설정

```yaml
spring:
  application:
    name: eol-poc-backend
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/eol}
    username: ${DB_USERNAME:eol}
    password: ${DB_PASSWORD:eol}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  threads:
    virtual:
      enabled: true

eol:
  zone: Asia/Seoul                 # 기준일 계산, cron 타임존
  soon-threshold-days: 90
  collection:
    cron: "0 0 3 * * *"
  http:
    connect-timeout: 5s
    read-timeout: 30s
    user-agent: "eol-poc/0.1"
  ai:
    model: claude-sonnet-5-5
    effort: medium
    max-tokens: 16000
    prompt-version: extract-v1
  cors:
    allowed-origins: http://localhost:5173
```

- `eol.*`는 `@ConfigurationProperties` 데이터 클래스 하나(`EolProperties`)로 받는다.
- `ANTHROPIC_API_KEY`, `DB_PASSWORD`는 환경 변수로 주입한다(NFR-07).

---

## 8. 의존성 추가

| 용도 | 의존성 |
|------|--------|
| REST API | Spring Boot Web MVC starter |
| DB | Spring Data JPA starter, Flyway starter + `flyway-database-postgresql`, `org.postgresql:postgresql` |
| JSON (Kotlin) | Jackson Kotlin 모듈 (Boot 4 = Jackson 3) |
| HTML 파싱 | `org.jsoup:jsoup` |
| AI | `com.anthropic:anthropic-java:2.34.0` |
| 테스트 | Spring Boot Testcontainers, Testcontainers PostgreSQL |
| 빌드 플러그인 | `kotlin("plugin.jpa")`, `allOpen` 설정 |

Boot 4에서 starter 이름이 일부 바뀌었으므로 정확한 좌표는 구현 시 start.spring.io 기준으로 확인한다. Docker 이미지는 `bootBuildImage`로 만든다.

---

## 9. 테스트 전략

| 종류 | 대상 | 방법 |
|------|------|------|
| 단위 | `EolStatusCalculator`, `VersionComparator`, `Normalizer`, `DateMentionParser`, `QuoteVerifier`, 검증 규칙, `HtmlToText` | 순수 함수 테스트. 고정 `Clock` 사용 |
| 통합 | 수집 → 검증 → 반영 전체 흐름 | Testcontainers PostgreSQL + `FakeExtractor`(고정 `ExtractionResult` 반환) + 로컬 fixture HTTP 응답 |
| AI 정확도 | Opensource별 추출 결과 | 저장한 `contentText` fixture와 기대값 비교. `@Tag("ai")`로 분리하여 기본 `test`에서 제외하고, 프롬프트·모델 변경 시 수동 실행 |

**완료 기준(요구사항 12장) 대응 통합 테스트**

| 완료 기준 | 테스트 |
|-----------|--------|
| 3. 재수집 시 이력 미생성 | 같은 fixture로 두 번 수집 → 이력 행 수 동일, 두 번째는 `skipped_unchanged` |
| 4. 값 변경 시 이력 | 종료일을 바꾼 fixture → `VALUE_CHANGED` 검토 → 승인 → 이력 1건 |
| 5. 수집 실패 격리 | 출처 HTTP 500 → `FAILED`, 기존 EOL 상태 유지 |
| 6. EOL_SOON 기준 | `Clock`과 `soon-threshold-days`를 바꿔 판정 확인 |
| 7. 값 없음 | 날짜가 없는 버전 → `NULL`, `UNKNOWN` |
| 8. AI 검증 | 원문에 없는 quote → `QUOTE_NOT_FOUND`, 날짜와 다른 quote → `QUOTE_VALUE_MISMATCH`, 신규 값 → 자동 승인 |

---

## 10. 확장 시 바꿔야 할 부분

POC에서 의도적으로 단순화한 부분이다. 1,000개 이상으로 확장할 때 다시 검토한다.

| 항목 | POC 구현 | 한계 | 확장 시 |
|------|----------|------|---------|
| 동시 수집 방지 | JVM 메모리 잠금 | 인스턴스 1대에서만 유효 | PostgreSQL advisory lock 또는 ShedLock |
| 정기 수집 | 순차 실행 | 출처 수 × (HTTP + AI 시간) | 가상 스레드로 Opensource 단위 병렬 실행, 호스트별 요청 간격 |
| 목록 집계 | 전체 로드 후 메모리 집계·필터 | 버전 수만 건 이상에서 느려짐 | SQL 집계 또는 요약 테이블 |
| 원본 저장 | 수집마다 `raw_content`, `content_text` 저장 | 저장 공간 증가 | 해시가 같으면 저장 생략, 보관 기간 정책 |
| AI 입력 | 출처 1개 = 호출 1번, 분할 없음 | 매우 큰 문서 | 분할 추출 또는 출처별 `selector` 정교화 |

---

## 11. 리스크

| ID | 리스크 | 대응 |
|----|--------|------|
| R1 | AI 결과가 같은 입력에도 달라짐 | 해시가 같으면 재추출하지 않음(D5). 값 변경은 항상 검토(VAL-09) |
| R2 | 근거 문구의 날짜 형식을 `DateMentionParser`가 못 읽어 정상 값이 거부됨 | 거부 사유(`QUOTE_VALUE_MISMATCH`)를 모니터링하여 형식 추가. 프롬프트에서 표의 행 전체를 quote로 쓰도록 지시 |
| R3 | AI가 원문에 있는 다른 날짜를 엉뚱한 버전에 연결함(quote는 실제 존재) | quote 검증으로는 못 막는다. 표의 행 단위 quote로 버전과 날짜가 한 문구에 함께 있게 유도하고, AI 정확도 fixture 테스트로 확인. 필요하면 "quote에 버전 문자열 포함" 규칙 추가 검토 |
| R4 | JavaScript로 렌더링되는 공식 페이지 | 공식 API 또는 다른 공식 페이지를 출처로 사용. 헤드리스 브라우저는 POC 범위 밖 |
| R5 | 공식 출처에 버전별 EOL 날짜가 없음(예: OpenSSH) | 지원 여부 명시(`support`) 추출로 `SUPPORTED`/`EOL` 판정, 없으면 `UNKNOWN` |
| R6 | 출처 URL이 아직 검증되지 않음 | M7 단계에서 출처별로 확인 후 시드에 반영 |

---

## 12. 구현 순서

| 단계 | 내용 | 확인 방법 |
|------|------|-----------|
| M1 | 의존성, Flyway V1, 시드, 엔티티, 조회 API 3종, `EolStatusCalculator`, `VersionComparator` | 시드에 수동 데이터를 넣고 API 응답 확인 → 프런트엔드 개발 시작 가능 |
| M2 | `WebCollector`, `ApiCollector`, `HtmlToText`, 수집 기록, 해시 생략, 수동 수집 API(우선 동기) | PostgreSQL 출처 원본·텍스트 저장 확인 |
| M3 | `AiExtractor`, 프롬프트 v1, `Normalizer` | PostgreSQL fixture로 AI 정확도 테스트 |
| M4 | `Validator`, `QuoteVerifier`, `DateMentionParser`, `EolService`(근거·이력) | 완료 기준 3·4·7·8 통합 테스트 |
| M5 | 스케줄러, 비동기 수동 수집, 잠금, 기동 시 정리 | 완료 기준 5·9 |
| M6 | 검토 API, 대체 처리(D9) | 승인·거부 흐름 테스트 |
| M7 | 나머지 5종 출처 확인·시드 반영, 정확도 확인, 신규 Opensource 데이터 등록 시연 | 완료 기준 1·2·10 |

---

## 13. 요구사항 추적

| 요구사항 | 설계 위치 |
|----------|-----------|
| REG-01~04 | 5.2 `opensource`, `source_registry` / 5.3 / 6.6 |
| COL-01~08 | 3.1 / 4.1 / 4.3 / 5.2 `eol_collection` |
| VAL-01~12 | 4.4 |
| STO-01~09 | 3.1 트랜잭션 / 4.5 / 5.2 |
| SCH-01~04 | 3.1 / 3.2 |
| AI-01~08 | 4.2 / D5~D7 |
| REV-01~03 | 4.8 / 6.5 / D9 |
| UI1~UI3 | 6.1~6.3 |
| NFR-01~10 | 4.5(추적성), D5(멱등성), 3.1(장애 격리), 10(성능), 7(설정·보안), 3.1 MDC(로깅), 9(테스트), 7 `eol.cors`(연동) |
| 3.1 EOL 상태 판정 | 4.6 |
