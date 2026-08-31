# One Payment From Fifty Requests

A plain Java 21 reference implementation based on Stripe’s [idempotent-request documentation](https://docs.stripe.com/api/idempotent_requests) and [engineering article](https://stripe.com/blog/idempotency).

## The problem

A payment can complete while its HTTP response is lost. If a client interprets silence as failure and retries a naive `POST`, each attempt performs the side effect again.

```text
50 concurrent attempts × 1 charge per naive attempt = 50 charges
50 concurrent attempts × 1 reserved idempotency key = 1 charge
```

This repository runs both handlers through a real local HTTP server. The idempotent handler reserves one key, fingerprints the request, stores the winning status and body, and replays that response to matching retries.

## Run it

Requires Java 21. The checked-in Gradle wrapper downloads the pinned build tool automatically.

```bash
./gradlew run
```

On Windows PowerShell, use `.\gradlew.bat run`.

To call the HTTP handler yourself, keep it running in one terminal:

```bash
./gradlew run --args="--serve"
```

Then send the payment from another terminal:

```bash
curl --include --request POST http://127.0.0.1:8080/payments \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: order-cart-alpha" \
  --data '{"amountCents":2000,"currency":"usd"}'
```

The first call creates the charge:

```http
HTTP/1.1 201 Created
Content-Type: application/json

{"charge_id":"ch_0001","amount_cents":2000,"currency":"usd"}
```

Run the same `curl` command again. The body is identical and the replay is explicit:

```http
HTTP/1.1 201 Created
Idempotent-Replayed: true
Content-Type: application/json

{"charge_id":"ch_0001","amount_cents":2000,"currency":"usd"}
```

In Windows PowerShell, use `curl.exe` and `.\gradlew.bat run --args="--serve"`.

The command runs the naive and idempotent modes with 50 concurrent requests and writes `benchmark.csv`.

To exercise the real Redis adapter, start Redis separately and run:

```bash
./gradlew run --args="--store redis --redis-url redis://127.0.0.1:6379"
```

Useful defaults can be overridden:

```bash
./gradlew run --args="--requests 50 --amount-cents 2000 --currency usd --ttl-seconds 86400 --csv benchmark.csv"
```

## What it shows

The baseline assigns every HTTP request a new charge. The idempotent mode sends the same key and payload on every request; one caller reserves the key with an atomic create-if-absent operation, and the others receive the stored response.

The Redis adapter uses `SET ... NX PX` for the reservation and a compare-and-set Lua script when replacing the processing marker with the completed response. The default in-memory adapter has the same state machine so the experiment remains one-command and deterministic when Redis is unavailable.

The CSV columns report requests, successful responses, created charges, replayed responses, conflicts, and elapsed time. Latency is diagnostic only; the property under test is the charge count.

## Test

```bash
./gradlew test
```

The tests cover 50 simultaneous attempts, a lost response followed by a retry, payload mismatch, distinct keys, expiration, and a crash between the ledger write and response storage.

## Limits

This is an educational local service, not Stripe’s production implementation. Stripe documents that a request colliding with another request still executing can return a conflict; this demo waits and replays the completed response instead.

The default run uses an in-memory implementation because it requires no daemon. The repository includes a real Redis adapter, but the benchmark numbers in `benchmark.csv` come from the selected `--store` mode.

Most importantly, Redis `SETNX` cannot atomically commit a charge in an external ledger and store the HTTP response. The crash-boundary test deliberately shows two charges when the process fails after the ledger side effect but before response storage. A production design needs the business write and idempotency record in one transactional boundary, or a durable recovery protocol that reconciles the two.
