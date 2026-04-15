# Mock Worker 연동 백엔드 과제

이미지 처리 요청을 받아 외부 Mock Worker에 위임하고, 상태와 결과를 추적/제공하는 Spring Boot 서버입니다.
과제 요구사항은 "기능이 돌아가는 것"이 아니라 **불확실한 외부 환경에서의 설계 사고**를 봅니다. 이 문서는 왜 이런 구조로 만들었는지를 중심으로 씁니다.

---

## 1. 실행 방법

### 1.1 로컬 (컨테이너)

```bash
# 1) Mock Worker API Key를 먼저 발급받아 넣어두면 서버 기동 시 /auth/issue-key 호출을 건너뜁니다.
export MOCK_WORKER_CANDIDATE_NAME="정한영"
export MOCK_WORKER_EMAIL="me@example.com"
# 또는 이미 발급받은 키가 있다면
# export MOCK_WORKER_API_KEY="mock_xxx"

docker compose up --build
```

- 앱 포트: `8080`
- MySQL 포트: `3306` (user: `mockworker`, password: `mockworker`, db: `mockworker`)
- 헬스체크: `GET http://localhost:8080/actuator/health`

`MOCK_WORKER_API_KEY`를 지정하지 않으면 앱은 기동 시 Mock Worker의 `/auth/issue-key`를 호출해 키를 받아 메모리에 캐시합니다.

### 1.2 API

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/api/v1/jobs` | 작업 접수. 헤더 `Idempotency-Key` 권장, body `{ "imageUrl": "..." }` |
| `GET` | `/api/v1/jobs/{id}` | 단건 상태/결과 조회 |
| `GET` | `/api/v1/jobs?page=0&size=20` | 목록 조회 |

**접수 예시:**

```bash
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 2d4c...b3a1" \
  -d '{"imageUrl":"https://example.com/tooth.png"}'
```

**응답 예시:**

```json
{
  "id": "1c5e...f17a",
  "status": "PENDING",
  "imageUrl": "https://example.com/tooth.png",
  "result": null,
  "failureReason": null,
  "attemptCount": 0,
  "createdAt": "2026-04-15T10:00:00Z",
  "updatedAt": "2026-04-15T10:00:00Z"
}
```

---

## 2. 상태 모델

우리 서비스가 소유하는 상태는 Mock Worker가 쓰는 상태와 별개로 정의합니다. 이유: 외부 상태는 언제든 바뀔 수 있고, 우리 쪽에서 "아직 제출 전"과 "제출 후 대기"는 완전히 다른 운영 의미를 가지기 때문입니다.

```
              ┌──────────┐
  accept ───▶ │ PENDING  │
              └────┬─────┘
        submit OK  │  submit permanent fail / max attempts
                   ▼
              ┌───────────┐                    ┌───────────┐
              │IN_PROGRESS│  ── poll COMPLETED ▶│ COMPLETED │  (terminal)
              └────┬──────┘                    └───────────┘
                   │
                   │  poll FAILED / permanent poll fail / deadline
                   ▼
               ┌────────┐
               │ FAILED │  (terminal)
               └────────┘
```

### 2.1 허용되는 전이

- `PENDING → IN_PROGRESS` — submit 성공 (worker jobId 확보)
- `PENDING → FAILED` — submit 영구 실패 or 재시도 횟수 초과
- `IN_PROGRESS → COMPLETED` — poll이 worker `COMPLETED` 확인
- `IN_PROGRESS → FAILED` — poll이 worker `FAILED` 확인 / 영구 poll 실패 / poll deadline 초과

### 2.2 허용되지 않는 전이 (명시적으로 금지)

- `COMPLETED → *` / `FAILED → *` (터미널 상태는 freeze)
- `IN_PROGRESS → PENDING` (후퇴 없음 — 재전송하려면 새로운 job을 accept 해야 함)
- `PENDING → COMPLETED` (중간 제출 단계를 건너뛰지 않음)

### 2.3 어디서 강제되는가

`ImageJob.markSubmitted/markCompleted/markFailed` **엔티티 내부**에서 `JobStatus.canTransitionTo`로 검증합니다. 서비스에서 `job.setStatus(...)` 같은 호출이 존재할 수 없도록 setter를 열지 않았습니다. 이 규칙이 한 군데에만 있어야 회귀가 생기지 않습니다.

실패/성공 전환 시 `result`와 `failureReason` 필드도 함께 세팅됩니다 — 이것도 엔티티 책임입니다. 서비스는 "어떤 이벤트가 발생했다"만 알면 되고, "그 결과 어떤 필드가 업데이트되어야 하는가"는 도메인이 정합니다.

---

## 3. 실패 처리 전략

### 3.1 에러 분류

Mock Worker 호출 시 발생하는 모든 예외는 `MockWorkerException`으로 포장되며, **`isTransient` 플래그로 재시도 가능 여부를 분류**합니다.

| 원인 | 분류 | 동작 |
|---|---|---|
| IO/network, timeout | transient | 백오프 후 재시도 |
| HTTP 5xx | transient | 백오프 후 재시도 |
| HTTP 429 | transient | 백오프 후 재시도 |
| HTTP 401 | transient (1회) | API key refresh 후 1회 재시도, 실패 시 transient로 전파 |
| HTTP 4xx (429 제외) | **permanent** | 즉시 `FAILED`로 이동 |
| 응답 body malformed | permanent | 즉시 `FAILED` |

이 분류는 `HttpMockWorkerClient#classify`에 한 곳에 모아뒀습니다. 흩어지면 일관성이 깨지고, 특정 에러를 재시도하는지/멈추는지가 운영 중에 헷갈리게 됩니다.

### 3.2 백오프

- Submit: `initialBackoff=1s`, `maxBackoff=30s`, `maxAttempts=5` (그 이후 `FAILED`)
- Poll: `initialInterval=2s`, `maxInterval=15s`, **횟수 제한 없음** — 대신 `deadline=10m`

Poll에 "횟수 제한"이 아닌 "deadline"을 쓰는 이유: worker가 실제로 작업 중이면 poll 횟수는 의미가 없습니다. 벽시계 기준 너무 오래 `IN_PROGRESS`이면 stuck으로 간주하고 `FAILED` 처리합니다. deadline은 `updatedAt` 기준이라, poll이 의미있는 상태 변화를 잡아내는 한 계속 연장됩니다.

### 3.3 타임아웃

HTTP 레이어에서 **connect 2s / read 5s**로 짧게 끊습니다. 과제 명세상 worker 응답이 "수 초에서 수십 초"라서 read 5s는 짧아 보이지만, 이건 **동기 제출 API**에 대한 타임아웃입니다. 동기 응답이 5초 안에 오지 않으면 transient로 간주해 백오프 후 재시도합니다. 실제 작업 완료 대기는 polling이 담당하므로 HTTP 호출 자체를 오래 들고 있을 필요가 없습니다.

### 3.4 표현

- `status=FAILED`인 경우 `failureReason` 필드에 실패 사유 문자열이 들어갑니다 (1024자 truncate)
- 재시도 중에도 직전 실패 사유는 `failureReason`에 남아있어 운영 시 "지금 왜 재시도 중인지"를 바로 확인할 수 있습니다
- `attemptCount`는 Submit 단계와 Poll 단계에서 각각 0으로 리셋됩니다 (`markSubmitted`에서 reset). Submit 단계의 재시도 횟수가 Poll 단계에서 잘못 쓰이지 않도록 하기 위함입니다.

---

## 4. 트랜잭션 경계 — 외부 호출은 TX 밖에서

이 구조의 핵심 원칙입니다.

> **DB 트랜잭션 안에서는 절대 외부 HTTP를 호출하지 않는다.**

### 4.1 왜

- DB 커넥션은 제한 자원입니다. HTTP가 느려지면 커넥션 풀이 말라버리고, 전혀 상관없는 조회 API까지 같이 느려집니다.
- 외부 시스템 장애가 내부 전파되는 가장 흔한 경로가 "TX 안의 HTTP"입니다.

### 4.2 어떻게

- `POST /api/v1/jobs` (accept): TX 한 번에 insert만. 이 순간 worker는 호출하지 않습니다.
- `JobSubmitter` / `JobPoller` 스케줄러:
  1. **TX 1 (짧음)**: "지금 처리할 job id 목록" 조회
  2. **TX 밖**: 외부 HTTP 호출 (여기서 몇 초~수십 초 걸려도 DB는 안 묶여 있음)
  3. **TX 2 (짧음)**: 결과를 엔티티에 반영, 저장

`TransactionTemplate`으로 TX 경계를 명시적으로 드러냈습니다. `@Transactional` 메서드 내부에서 RestTemplate을 호출하는 실수를 구조적으로 막기 위해서입니다.

---

## 5. 중복 요청 처리 (멱등성)

### 5.1 설계

- 클라이언트는 `Idempotency-Key` 헤더로 요청을 식별합니다.
- DB의 `image_job.client_request_key` 컬럼에 **UNIQUE 인덱스**를 걸었습니다. 단순 "조회 후 insert" 흐름은 race에 취약하기 때문에, 동시 요청 보호는 DB 제약에 맡깁니다.
- Accept 로직:
  1. `findByClientRequestKey`로 빠른 경로 조회 → 있으면 바로 반환
  2. 없으면 `saveAndFlush`
  3. `DataIntegrityViolationException` 발생 (경쟁 insert) → 다시 조회해서 이기는 쪽 반환

### 5.2 Idempotency-Key 오남용

같은 키 + 다른 payload는 **409 `idempotency_conflict`**로 응답합니다. 조용히 기존 결과를 돌려주면 클라이언트 버그를 감추게 됩니다. 이를 위해 `request_fingerprint` (SHA-256) 를 같이 저장하고 비교합니다.

### 5.3 헤더가 없는 경우

클라이언트가 `Idempotency-Key`를 안 보내면 **payload 해시로 자동 키**를 생성합니다 (`auto-<fingerprint>`). 동일 payload 두 번 POST는 같은 job으로 병합됩니다. 이는 안전망이지 이상적 동작은 아닙니다 — README 상단에서 명시적으로 헤더 사용을 권장합니다.

### 5.4 Mock Worker 쪽 멱등성

Mock Worker `/mock/process`는 **idempotency key를 받지 않습니다**. 즉 우리가 재시도하면 worker 입장에서는 새로운 job이 됩니다. 이건 구조적으로 피할 수 없고, 아래 "처리 보장 모델"에서 다룹니다.

---

## 6. 동시성 제어

### 6.1 현재

- `ImageJob`에 `@Version` (JPA optimistic lock) 컬럼이 있습니다.
- 스케줄러는 "PENDING/IN_PROGRESS 중 `nextAttemptAt <= now`인 것"을 배치로 읽어 처리합니다.
- 저장 시 version이 어긋나면 `ObjectOptimisticLockingFailureException`이 발생하고, 그 job은 **그냥 스킵**합니다. 다음 tick에 다시 잡힙니다.

### 6.2 왜 이 수준인가

- 단일 인스턴스 (docker compose는 한 노드) 기준에서는 이 정도가 충분합니다. Submitter/Poller가 같은 row를 동시에 건드리려고 해도, 의미 있는 상태 전이는 `PENDING→IN_PROGRESS`(submitter only)와 `IN_PROGRESS→COMPLETED/FAILED`(poller only)로 분기돼 있어 사실상 충돌하지 않습니다.
- 상태 전이 규칙 자체가 `job.markXxx()` 내부에서 `canTransitionTo`로 검증되기 때문에, 설령 두 스케줄러가 동일 job에 동시에 접근해도 **정합성이 깨진 상태로 저장되는 경로가 존재하지 않습니다**.

### 6.3 수평 확장 시 업그레이드 경로

- 여러 앱 인스턴스가 동시에 "due job"을 조회하면 같은 행을 중복 fetch합니다. 외부 호출이 중복될 수 있습니다. (정합성은 여전히 안전 — optimistic lock이 막음)
- 해결책: `SELECT ... FOR UPDATE SKIP LOCKED` (MySQL 8 지원). JPA에서는 `@Lock(PESSIMISTIC_WRITE)` + `jakarta.persistence.lock.timeout = -2` (Hibernate SKIP_LOCKED 힌트).
- 지금 단계에서 도입하지 않은 이유: 과제 평가 환경이 단일 인스턴스이고, skip-locked를 걸면 H2 테스트가 깨지기 때문입니다. 업그레이드 지점은 `ImageJobRepository#findDueByStatus` 한 곳으로 국지화돼 있습니다.

### 6.4 검토된 "같은 요청 동시 2번" 시나리오

| 시나리오 | 결과 |
|---|---|
| 같은 `Idempotency-Key` 두 번 동시 POST | UNIQUE 제약으로 1건만 insert, 나머지는 기존 row 반환 |
| 두 스케줄러 tick이 동일 PENDING job fetch | 한 쪽이 `markSubmitted` 저장 성공, 다른 쪽은 version mismatch로 skip |
| Submit 호출 중 앱 crash | row는 여전히 PENDING. 재기동 후 다시 submit 시도. (중복 worker job 가능성 — 아래 섹션) |

---

## 7. 처리 보장 모델

**클라이언트 → 우리 서버: effectively exactly-once**
**우리 서버 → Mock Worker: at-least-once**

### 근거

- **Exactly-once (클라 방향)**: `Idempotency-Key` + UNIQUE 인덱스 덕분에, 동일 키에 대해서는 반드시 동일 job이 반환됩니다. 재시도가 얼마나 일어나든 서버의 상태(= job 레코드)는 1건만 존재합니다.
- **At-least-once (worker 방향)**: 다음 경로가 구조적으로 존재하기 때문입니다.
  1. `submitter`가 worker로 HTTP POST
  2. worker가 job을 생성, 응답을 반환
  3. 네트워크 드롭 / 앱 크래시로 응답이 우리에게 도달하지 못함
  4. job은 PENDING으로 남음 → 다음 tick에 재시도 → worker에 **중복 job 생성**

Mock Worker가 idempotency key를 받지 않으므로 이걸 (4)에서 구조적으로 막을 방법이 없습니다. 클라에게 돌려주는 결과는 한 개지만, worker 쪽에는 orphan job이 남을 수 있습니다. 이 문제는 다음 중 하나가 있어야 해결됩니다:

1. Worker가 클라이언트 idempotency key를 받아 자기 쪽에서 dedup
2. Worker에 "list jobs by tag" 조회 API가 있어 사후 조회로 기존 job을 찾을 수 있음
3. 우리가 pre-commit pattern (outbox) 를 도입

현재 스펙상 (1)(2)가 없고 (3)은 과제 수준에 과합니다. 따라서 **at-least-once를 수용하고 명시**합니다.

---

## 8. 서버 재시작 시 동작

### 8.1 정상 동작

모든 job 상태는 MySQL에 durable 하게 저장됩니다. 재시작 후:

- `PENDING` job → `JobSubmitter`가 다음 tick에 다시 포착, submit 시도
- `IN_PROGRESS` job → `JobPoller`가 다음 tick에 다시 포착, poll 재개
- `COMPLETED` / `FAILED` → 터미널, 건드리지 않음

메모리 상에만 존재하는 상태가 없습니다. 스케줄 큐/in-flight 맵도 없습니다. 이게 재시작 안전성의 전제 조건입니다.

### 8.2 정합성이 깨질 수 있는 지점

1. **Submit 중 크래시 (at-least-once 창구)**
   - worker가 POST를 받았지만 우리가 응답을 persist 하기 전에 프로세스가 죽음
   - 재기동 후 같은 PENDING을 다시 submit → worker에 orphan job 생성
   - 클라이언트 시점에서는 여전히 하나의 job으로 보임 (우리 DB 상태는 1건). 단 외부 worker 사이드이펙트는 중복.

2. **Poll 중 크래시**
   - 상태 변화 없음. 재기동 후 똑같은 IN_PROGRESS를 다시 poll할 뿐. 안전.

3. **API Key 만료 타이밍**
   - 401을 받으면 `MockWorkerApiKeyProvider.refresh()`로 즉시 재발급하고 1회 재시도합니다.
   - refresh 도중 프로세스가 죽으면 다음 기동 시 `props.apiKey()` 우선 → 없으면 재발급. 안전.

4. **DB 커넥션 풀 고갈로 유발되는 이슈는 구조적으로 차단**: 외부 HTTP를 TX 밖에서 호출하기 때문에, worker가 느려져도 DB 커넥션은 즉시 반납되고 풀이 유지됩니다.

### 8.3 재시작 직후 대량 백로그

`nextAttemptAt` 기반으로 due한 것부터 처리하고, 배치 사이즈를 `BATCH_SIZE=10/20`으로 제한해 한번에 너무 많은 외부 호출이 나가지 않도록 합니다.

---

## 9. 동시 요청 발생 시 고려 사항

- **동일 요청 (같은 Idempotency-Key) 동시 수신**: 섹션 5 참고. UNIQUE로 강제.
- **다른 요청 대량 수신**: accept API는 단일 insert만 하고 외부 호출을 하지 않으므로 빠르게 queue에 쌓입니다 (MySQL row). 병목은 DB write가 됩니다.
- **Submitter가 outbound rate limit에 부딪힘**: worker가 429를 주면 transient로 분류되어 백오프. 배치 사이즈와 스케줄 간격이 자연스럽게 throttling 역할을 합니다. 엄격한 token bucket은 지금 수준에서 불필요하다고 판단했습니다.

---

## 10. 트래픽 증가 시 병목 가능 지점

| 지점 | 증상 | 해결 방향 |
|---|---|---|
| MySQL write (`image_job` insert) | accept API 지연 | RDS 수직 확장 → 파티셔닝 → write-through 캐시 |
| MySQL scan (`findDueByStatus`) | 스케줄러 tick 지연 | `status, next_attempt_at` 복합 인덱스 추가 (이미 status+created_at 인덱스 있음, 교체 필요) |
| 단일 인스턴스 스케줄러 | 수평 확장 불가 | SKIP LOCKED 도입 + 앱 인스턴스 다중화 (섹션 6.3) |
| Mock Worker 자체 throughput | poll 대기 증가 | 우리가 제어 불가. 내부 SLA는 deadline과 명확한 FAILED 표현으로만 방어 |
| API key 재발급 spike | 401 동시 다발 시 race 가능 | `issueAndCache`를 `synchronized`로 보호, double-check 패턴 |

---

## 11. 외부 시스템 연동 방식 및 선택 이유

### 11.1 HTTP 동기 + polling

Mock Worker가 webhook/callback을 제공하지 않기 때문에, 우리가 주도적으로 상태를 polling해야 합니다. 대안과 기각 이유:

| 대안 | 기각 이유 |
|---|---|
| Long-polling / SSE | Mock Worker에 해당 엔드포인트 없음 |
| Webhook 등록 후 수동 | 없음. 미지원. |
| Kafka/외부 MQ에 이벤트 발행 | 본 과제 범위 대비 과도. MySQL 하나로 충분 |

### 11.2 왜 Spring `RestClient`인가

- Spring Boot 3.5 기본 제공, 동기 클라이언트, 별도 의존성 불필요
- `WebClient`는 reactive stack 전체 비용 부담 (onNext/onError/Mono) 이 과제엔 과함
- `RestTemplate`은 이미 사용 지양 권고

### 11.3 왜 Kafka/SQS를 도입하지 않았는가

- 과제 스펙이 "외부 API 연동"이라는 단일 문제에 집중합니다. MQ를 도입하면 "그 MQ의 DLQ/retention/운영"이라는 또 다른 문제가 생기고, 과제의 핵심(외부 API 장애 격리와 상태 정합성)이 희석됩니다.
- MySQL 테이블 하나면 durable queue 역할을 충분히 해냅니다. 필요한 건 "due 기반 polling" + "상태 기반 transition" 두 가지입니다.
- 운영 관점에서도 "같은 곳에 데이터가 있다"가 쿼리/디버깅을 훨씬 쉽게 합니다. 실제 장애 대응 시 `SELECT * FROM image_job WHERE status = 'IN_PROGRESS'`가 가장 강력합니다.
- 단, **공고에 SQS/DLQ가 있으므로** 향후 확장 지점은 두었습니다: `JobSubmitter`를 SQS consumer로 바꾸고, 실패 재시도를 DLQ로 넘기는 구조로 리팩토링 가능하도록 layer가 분리돼 있습니다.

---

## 12. 테스트 전략

테스트 위치는 `src/test/java` 입니다. 다음 4종류를 작성했습니다:

1. **`JobStatusTransitionTest`** — 도메인 단위. 허용/비허용 전이 규칙이 엔티티에서 강제되는지. 터미널 상태 freeze.
2. **`ImageJobServiceTest`** — idempotency 경로. 같은 키 → 동일 job, 같은 키 + 다른 payload → 409, 첫 insert 성공.
3. **`JobSubmitterTest`** — 외부 클라이언트 mock. 성공/transient/permanent 세 가지 실패 분기 각각의 최종 상태.
4. **`JobPollerTest`** — 외부 클라이언트 mock. COMPLETED/FAILED/transient/permanent 4가지 분기.

### 왜 이 조합인가

시니어 관점에서 테스트는 "성공 케이스만 덮는 건 품질이 아니다"가 기본입니다. 위 4종은 **실패 흐름과 상태 정합성**을 주로 검증하도록 짜여 있습니다:

- `JobSubmitterTest#permanent_failure_fails_job_immediately`: 4xx는 재시도하면 안 된다는 계약
- `JobPollerTest#transient_poll_failure_keeps_job_in_progress_and_bumps_backoff`: 5xx 노이즈가 job을 잘못 FAILED로 만들지 않는다는 계약
- `ImageJobServiceTest#idempotency_key_reuse_with_different_payload_is_rejected`: 조용한 merge를 하지 않는다는 계약

### 테스트 실행

```bash
./gradlew test
```

H2 인메모리 DB를 쓰므로 별도 인프라 없이 실행됩니다.

---

## 13. 파일/책임 지도

```
src/main/java/com/realteeth/mockworker/
├── MockworkerApplication.java         # @EnableScheduling 포함
├── config/
│   ├── AppConfig.java                 # Clock, TransactionTemplate
│   └── RestClientConfig.java          # 타임아웃 세팅된 RestClient
├── client/                            # 외부 Mock Worker 연동 layer
│   ├── MockWorkerClient.java          # 인터페이스 (서비스는 이 것에만 의존)
│   ├── HttpMockWorkerClient.java      # 실제 HTTP 구현 + 에러 분류
│   ├── MockWorkerApiKeyProvider.java  # issue-key + 401 시 refresh
│   ├── MockWorkerProperties.java      # @ConfigurationProperties
│   ├── MockWorkerException.java       # transient/permanent 플래그
│   ├── WorkerJobStatus.java           # worker의 enum (우리 것과 분리)
│   └── WorkerJobSnapshot.java
├── domain/                            # 도메인 루트
│   ├── ImageJob.java                  # 상태 전이 규칙 강제
│   ├── JobStatus.java                 # canTransitionTo 테이블
│   ├── InvalidJobStateException.java
│   └── ImageJobRepository.java        # findDueByStatus
├── service/
│   ├── ImageJobService.java           # accept/get/list, 멱등 경로
│   ├── JobSubmitter.java              # PENDING → IN_PROGRESS 배치
│   ├── JobPoller.java                 # IN_PROGRESS → COMPLETED/FAILED 배치
│   ├── BackoffCalculator.java
│   ├── IdempotencyConflictException.java
│   └── JobNotFoundException.java
└── web/
    ├── JobController.java             # REST endpoints
    ├── CreateJobRequest.java
    ├── JobResponse.java
    └── GlobalExceptionHandler.java    # 예외 → HTTP 매핑
```

---

## 14. 향후 보강 지점 (스스로 남기는 TODO)

과제 범위를 넘어가거나 평가 환경에서 과하다고 판단해 지금 구현하지 않은 것들입니다. 의도적으로 남겨두고 문서화합니다.

- [ ] `SELECT ... FOR UPDATE SKIP LOCKED`로 수평 확장 가능하게 (섹션 6.3)
- [ ] Circuit breaker (Resilience4j). 지금은 timeout + backoff만으로 방어. 외부 SLA가 장기간 깨지면 fail-fast로 전환 필요.
- [ ] Outbox 패턴 또는 worker 쪽 idempotency key 협상으로 at-least-once → exactly-once 격상
- [ ] Metrics (Micrometer): `status별 job count`, `submit latency`, `poll backlog` 대시보드화
- [ ] Swagger/OpenAPI (`springdoc-openapi`) 추가. 공고에도 요구사항으로 있음.
- [ ] DB 컬럼 인덱스 튜닝: 현재 `(status, created_at)` 복합 인덱스는 조회 정렬 기준(`next_attempt_at`)과 불일치. 트래픽 증가 시 `(status, next_attempt_at)`로 교체 필요.
- [ ] 입력 검증 강화: `imageUrl`이 실제 http/https URL인지 `@URL` 검증 추가 가능 (Hibernate Validator)
