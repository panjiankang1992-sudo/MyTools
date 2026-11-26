# Verified outbound messaging v2

Status: implemented for local testing, disabled by default; no real recipient has been contacted.

The legacy `/internal/v1/deliveries` API remains unchanged. The v2 API owns verified destinations, frozen content, durable scheduling intent and provider attempts. News owns editions, subscription targets and per-part dispatch intent.

## Authentication and ownership

Base path: `/internal/v2/messaging`. Bearer tokens are independent, at least 32 characters, and must differ:

- `MESSAGING_OUTBOUND_CLIENT_TOKEN`: News server; list/disable destinations and create/read/cancel/retry deliveries.
- `MESSAGING_OUTBOUND_ADMIN_TOKEN`: trusted provisioning and manual reconciliation only. Never distribute to a browser or worker.
- `MESSAGING_OUTBOUND_WORKER_TOKEN`: Executor package; execute only with a live Scheduler execution ID and lease token.

`ownerId` is supplied by a trusted service, never copied from the public browser request. All reads/mutations enforce owner equality. Responses contain an opaque destination ID and label, never a recipient address, Webhook, signing secret, full news content, or provider reference. Public routing must authenticate through Gateway.

## Destination binding

`POST /admin/destinations` (admin):

```json
{
  "ownerId": 123,
  "bindingKey": "personal-mail-v1",
  "channel": "EMAIL",
  "label": "Personal inbox",
  "address": "recipient@example.com",
  "signingSecret": "",
  "verificationReference": "verification-record-123",
  "ownershipVerified": true
}
```

This endpoint records an administrator's **completed verification**; it does not verify an address by sending a code. The administrator must first confirm the target and authority to use it. Keep only an opaque verification reference, not credentials or message contents. Self-service email OTP and bot-conversation binding are future extensions.

EMAIL requires a single validated mailbox. FEISHU accepts only `https://open.feishu.cn/open-apis/bot/v2/hook/<token>`; query strings, alternate hosts and redirects are forbidden. An optional signing secret is supported. QQ scheduled binding returns `MESSAGING_021`: Tencent's official documentation states proactive push was discontinued on 2025-04-21. No old `msg_id` is reused to simulate daily push.

The address/secret is encrypted with AES-256-GCM and a fresh nonce. AAD binds owner, destination ID and channel. `MESSAGING_DESTINATION_ENCRYPTION_KEY` is a base64-encoded 32-byte key. Back up this key separately; changing it without re-encrypting rows makes existing bindings unusable. No automatic rotation is implemented.

Bindings are immutable. Replaying `(ownerId,bindingKey)` requires the same channel, label and credentials. Change an address by creating a new binding; disabling one does not re-enable it on replay.

`GET /destinations?ownerId=123` lists up to 100 newest bindings.
`POST /destinations/{id}/disable?ownerId=123` disables the binding and cancels unstarted deliveries. A provider call already in progress may finish.

## Frozen delivery contract

`POST /deliveries` (client):

```json
{
  "ownerId": 123,
  "idempotencyKey": "news-part-stable-key",
  "destinationId": "00000000-0000-4000-8000-000000000001",
  "content": {"subject": "Daily news", "text": "Frozen plain text", "html": null},
  "payloadHash": "<64 lowercase hex characters>",
  "expiresAt": "2026-10-19T00:00:00Z"
}
```

The example hash is a placeholder. Compute SHA-256 over the UTF-8 bytes of `subject`, `text`, and `html` (null becomes empty), **each preceded by its 4-byte big-endian byte length**. The server recomputes it. The idempotency key is owner scoped, at most 128 characters. A replay must match destination, content, hash and expiry (millisecond precision). Replays return the original record even after service sending is disabled.

New requests expire within 48 hours. Subject is at most 200 characters and cannot contain CR/LF. Text is at most 200,000 UTF-16 code units; HTML at most 400,000. EMAIL supports plain text or multipart/alternative. FEISHU rejects HTML and enforces a serialized JSON budget of 16 KiB including signature overhead. The downstream adapter neutralizes `<at>` markup. News must generate escaped HTML and immutable, bounded parts before submission.

Creation commits content and the scheduling intent together. It does not wait for Scheduler. Response fields:

`id`, `destinationId`, `channel`, `payloadHash`, `status`, `generation`, `errorCode`, `expiresAt`, `updatedAt`, and optional `contentExpiresAt` (omitted when absent).

## State, cancellation and retry

`ACCEPTED -> SENDING -> PROVIDER_ACCEPTED | FAILED | UNKNOWN`.

`ACCEPTED` may also become `CANCELLED` or `EXPIRED`. A task that ends before any provider call becomes `FAILED` with `SCHEDULER_ENDED_WITHOUT_SEND`. Task submission failures retain `ACCEPTED` and the same identity, with bounded polling intervals; delivery expiry bounds its lifetime.

- `GET /deliveries/{id}?ownerId=123`: read status.
- `POST /deliveries/{id}/cancel?ownerId=123`: only unstarted requests become cancelled. In-flight requests return their actual state, not a false cancellation success.
- `POST /deliveries/{id}/retry?ownerId=123` with `{"expectedGeneration":1,"acknowledgeDuplicateRisk":false}`: retry a known failure before expiry. UNKNOWN requires `true`.
- `POST /admin/deliveries/{id}/resolve?ownerId=123` with `{"outcome":"PROVIDER_ACCEPTED"|"NOT_SENT","reference":"provider-audit-id"}`: settle UNKNOWN using external evidence. `NOT_SENT` becomes FAILED and permits safe explicit retry.

A replay carrying the same `expectedGeneration` returns the already-created next generation, even if that attempt has finished. Each new retry advances `generation` (maximum 100) and records the previous resolution. It creates a new Scheduler task identity but preserves the frozen content. A late old attempt cannot overwrite a manually resolved or newer attempt. UNKNOWN is never automatically resent. An abandoned SENDING attempt older than 180 seconds becomes UNKNOWN; its late result may settle it until manual resolution or explicit resend.

`PROVIDER_ACCEPTED` means SMTP/custom bot acknowledged the request, not delivery to an inbox, reading, or exactly-once delivery. SMTP uses a stable Message-ID per generation. Feishu response requires numeric `code=0`; nonzero business code and HTTP 4xx are known rejection. Network failure, unreadable/oversized response, 5xx and SMTP ambiguity become UNKNOWN. Feishu response is capped at 8 KiB and an overall 25-second wait. Provider error bodies are not persisted or exposed.

`POST /deliveries/cancel-by-key` accepts the original frozen create payload. It atomically cancels an existing record or inserts a CANCELLED tombstone under the same owner/idempotency key. It never creates a sendable record; a late create can only replay the tombstone. This works while sending is disabled and after expiry.

## Scheduler and Executor

Task `message_send_channel_message@1.0.0`, step `send_channel`, business type `MESSAGE_OUTBOUND`. Key: `message-outbound:{deliveryId}:{generation}`. Parameters: only `deliveryId` and integer `generation`; no recipients, content or secrets.

Scheduler checks the authenticated `messaging-service` identity for this task domain. The worker invokes:

`POST /jobs/deliveries/{id}/execute?generation=1`

with its worker Bearer token, `X-Task-Execution-Id`, and `X-Task-Lease-Token`. Messaging validates the live Scheduler lease and requires at least 30 seconds remaining before claiming a provider attempt. Current task, business ID, generation, cancellation, newest fencing token and running state are checked. The transaction reserves one attempt before calling the provider outside the transaction. Worker terminal output is only `{deliveryId,status}`. Task completion means the atomic attempt was recorded; consumers must inspect the delivery status, not equate task success with delivered news.

Scheduler migration V176 seeds the task disabled in the existing messaging cluster. Messaging V18 creates independent destination/delivery/attempt tables; V19 adds a shared hashed-Webhook rate bucket. Feishu starts reserve at least 1.1 seconds apart, including different bindings to the same Webhook. Reservations over ten seconds ahead are rejected before contacting the provider. Waiting occurs outside transactions. Executor env mappings contain only the worker token and service URL for this package. The old email task and API are unchanged.

## Activation and remaining integration

1. Apply and validate V18–V22 on real MySQL; validate V176 against the target Scheduler schema.
2. Configure the three distinct tokens, encryption key, existing SMTP settings and Messaging Scheduler business identity.
3. Publish the immutable package and enable V176 only on an authorized Executor; keep normal lease renewal enabled.
4. Provision a verified personal destination with explicit authorization. Set `MESSAGING_OUTBOUND_ENABLED=true` only for controlled acceptance.
5. Verify provider receipt, cancellation, lost-response/UNKNOWN and manual reconciliation against a specifically authorized target before enabling daily dispatch.

News target storage, frozen rendering, per-part outbox, Gateway, the web panel, provider pacing and operational retention are implemented and locally tested. Release assembly and real acceptance remain work in the active daily-news goal. The transport adapter alone does not make daily delivery operational. No QQ active endpoint or connector fallback has been added.

Sources checked 2026-10-18: [Feishu custom bot guide](https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot), [Tencent official send-message documentation](https://github.com/tencent-connect/bot-docs/blob/main/docs/develop/api-v2/server-inter/message/send-receive/send.md).


### Web integration status (batch 9)

The authenticated news page now supports verified target selection, per-target FULL/BRIEF mode, revision conflicts, part status, stop requests, explicit retry risk, and original-request recovery. Browser requests carry no owner, destination address or provider secret. Local verification is recorded in `docs/verification/daily-news/2026-10-18-news-delivery-web.md`; no real provider delivery is claimed.


## Retention (V20, batch 11)

Retention defaults off independently of sending. `MESSAGING_OUTBOUND_RETENTION_ENABLED=false`, `MESSAGING_OUTBOUND_RETENTION_TIMER_ENABLED=true`, `MESSAGING_OUTBOUND_CONTENT_RETENTION_DAYS=90`, `MESSAGING_OUTBOUND_ATTEMPT_RETENTION_DAYS=90`, `MESSAGING_OUTBOUND_RETENTION_BATCH_SIZE=250`. Day values allow 1–365, batch size 1–500. When enabled, the timer runs one bounded batch every minute under a persistent 120-second lease and 30-second transaction.

`GET /admin/retention` requires the admin token and returns `enabled`, `observedAt`, `policy`, `due` (bodies/attempts), `protected` (unknown/sending), and the last checkpoint. `POST /admin/retention/sweep` returns per-table batch counts, or `{}` when another lease is held. Sending/client/worker credentials grant no access. Disabled mutation returns 503/MESSAGING_024; cleanup failures return MESSAGING_025 without underlying data. Responses use no-store/private.

For deliveries without an explicit content deadline, only expired, old, known terminal deliveries without a live relay lease lose their content. Unresolved UNKNOWN and SENDING retain evidence under this ordinary policy; explicit V22 deadlines below override body retention only. Old finished attempts are removed only after the associated delivery body is compacted and the delivery is stable. Original owner/key/destination/hash/expiry/status and a strict content-identity hash remain; matching replay works even after compaction, including null-versus-empty HTML distinction. Missing content cannot be sent or retried. These rules do not alter the legacy email/inbound tables.

After any compaction, rollback requires a V20-aware application; disabling retention does not restore released content. Real MySQL migration and operator reconciliation remain acceptance requirements. V21 bounds new v2 identities and retained payload admission; unresolved states still retain evidence and continue to occupy their original reservation. This is not a physical disk quota.


## Capacity admission (V21, batch 19)

Always-on finite defaults: `MESSAGING_OUTBOUND_CAPACITY_DESTINATION_ROWS=1000`, `MESSAGING_OUTBOUND_CAPACITY_DELIVERY_ROWS=100000`, `MESSAGING_OUTBOUND_CAPACITY_CANCEL_ONLY_ROWS=100000`, `MESSAGING_OUTBOUND_CAPACITY_PAYLOAD_BYTES=536870912`. Destination rows allow 1–100000, delivery/cancel rows 1–10000000, payload bytes 1–100000000000. All instances sharing a database must use the same limits. Limits are global, not per-owner fairness or a physical disk size guarantee.

The capacity singleton is locked before destinations and deliveries. New admission, content writes and ledger changes commit together. Duplicate requests re-read their identity under the lock and do not consume a second reservation. Capacity exhaustion returns HTTP 503 / `MESSAGING_026`; News keeps the same request for later reconciliation. `MESSAGING_027` means accounting is invalid and requires investigation, not an automatic reset. No provider/Scheduler call holds the capacity lock.

A never-created cancellation uses its own finite `CANCEL_ONLY` reservation, stores only the exact content-identity hash and existing metadata, and directly enters CANCELLED with no content. Null and empty HTML remain distinct. Database constraints forbid making these rows sendable. Ordinary message saturation cannot consume this reserve. If the cancellation reserve is also exhausted, an unknown-key cancellation returns 503; the caller must keep its cancellation pending and must not claim success. Existing-key cancellation, disable, read, execution, retry and UNKNOWN resolution require no new capacity reservation.

Body compaction releases only the stored UTF-8 JSON byte reservation in the same transaction. Destination/delivery/cancellation identities are retained permanently for replay safety. UNKNOWN and SENDING retain their content reservation unless an explicit V22 content deadline expires. The existing 100-generation limit is now checked in both delivery and attempt tables; unique delivery/generation and the new attempt-to-delivery foreign key bound retained attempts to at most 100 per delivery. The byte limit excludes ciphertext, identifiers, index/engine overhead, legacy mail/inbound data and rate buckets. Row limits bound new metadata; existing counts above a lowered limit remain intact.

`GET /admin/retention` additionally returns `capacity: {scope, used, measured, accountingMatches, limits}`. Only the existing admin identity can inspect it. Inspect at 80% usage and on any mismatch. This batch adds no new public metrics endpoint or Prometheus scrape configuration.

Stop all v2 writers before V21 migration. It conservatively classifies existing rows as DELIVERY, backfills actual body byte lengths and does not alter their content or status. Orphan attempts or generations outside 1–100 fail migration for operator investigation. Never silently delete them or repair Flyway history. V21 blocks old unmetered inserts; rollback must keep a V21-aware application. Disabling sending/retention does not erase reservations or restore compacted content.


## Explicit content deadline and bodyless cancellation (V22, batch 21)

Create may include `contentExpiresAt`, an immutable, millisecond-precision UTC instant. A new deadline must be in the future and no more than 730 days ahead. It is independent of the 48-hour send window and may be earlier or later. Omitting it preserves ordinary retention for old clients and migrated rows; the response omits the field when absent. Matching replay, including after cleanup, cannot add, remove, extend or shorten the deadline. This field enforces a caller-supplied content policy, not a grant of source rights.

Expired content cannot begin a provider attempt or a new retry. Retry returns HTTP 410 / `MESSAGING_028`; an unstarted delivery becomes EXPIRED with that code. The provider rechecks the deadline immediately before SMTP or HTTP invocation, including after MIME preparation and Feishu rate waiting. A call already started before the deadline can still finish; the service records its actual result. Deleting local content does not recall external copies or erase content already held by an in-flight call.

The independent deadline timer runs a bounded batch every minute while the application is running, even when sending and ordinary retention are disabled. It uses the existing 1–500 batch-size policy, a separate persistent lease/checkpoint and a short transaction. At the deadline, stored bodies and their capacity byte reservations are removed together. UNKNOWN/SENDING status, attempts, owner/key, destination, payload hash, strict identity hash and deadline remain available for reconciliation. ACCEPTED alone becomes EXPIRED. Hard-deadline cleanup does not remove attempts. Physical removal is asynchronous and may lag behind a backlog or outage; the execution/retry gates do not wait for cleanup.

`GET /admin/retention` adds `deadlineCleanup: {enabled:true,due,checkpoint}`. `POST /admin/retention/deadlines/sweep` runs one hard-deadline batch with the admin token, independent of the ordinary retention switch. An active lease returns `{}`; no due body returns `{"deadlineBodies":0}`. Its checkpoint is separate so an empty hard-deadline poll cannot hide an ordinary-retention failure. A normal enabled sweep also processes one hard-deadline batch.

`POST /deliveries/cancel-by-identity` accepts only frozen metadata:

```json
{
  "ownerId": 123,
  "idempotencyKey": "news-part-stable-key",
  "destinationId": "00000000-0000-4000-8000-000000000001",
  "payloadHash": "<original 64 lowercase hex characters>",
  "contentIdentityHash": "<original strict 64 lowercase hex characters>",
  "expiresAt": "2026-10-19T00:00:00Z",
  "contentExpiresAt": "2026-10-18T18:00:00Z"
}
```

The strict identity hash is SHA-256 over UTF-8 `outbound-content-identity-v1`, followed by one byte (0 for null HTML, 1 otherwise), followed by the 32 decoded payload-hash bytes. Keep the exact original hash and optional deadline before deleting content. The endpoint validates owner/destination and every frozen identity field, atomically cancels an existing unstarted message or reserves a compact CANCEL_ONLY tombstone, and never creates sendable content. It is valid with sending disabled and after expiry; active or unknown provider attempts retain their actual states. The finite cancellation reserve and 503 behavior remain unchanged. Existing `cancel-by-key` is compatible and delegates to this identity operation.

V22 preserves all old rows and adds no deadline to them. Upgrade all Messaging writers before clients use explicit deadlines. Once deadlines are accepted, rollback must retain V22 checks and the deadline timer; an older V21 binary could send retained content past its declared deadline. No feature switch disables these declared obligations. News does not yet freeze or propagate per-source derived-content deadlines in this batch; that remains separate implementation work.
