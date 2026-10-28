# Changelog

## 3.1.0 - 2026-10-28

A value that has the requested type but cannot be parsed now returns your default with reason
`ERROR` and error code **`PARSE_ERROR`** (was `TYPE_MISMATCH`) (qfg-9xsl). That is a data problem;
`TYPE_MISMATCH` stays for a caller problem, a read with the method for another type. This matches
openfeature-go and integration-test-data `tests/openfeature/openfeature.yaml` Case 6.

### Changed

- **`PARSE_ERROR` for unparseable values.** These return your default with `PARSE_ERROR`:
  - a malformed stored int or double (`"twelve"`, `"abc"`) read with `getIntegerValue` or
    `getDoubleValue`;
  - an int or double provided by an ENV_VAR holding an unparseable string;
  - a malformed stored duration (`"30s"`) read with `getStringValue`. A well-formed duration read
    with `getStringValue` is still `TYPE_MISMATCH`: durations are native-only.

  The returned value and reason are unchanged. Code that branches on `TYPE_MISMATCH` for these
  cases should also check `PARSE_ERROR`.

### Implementation

sdk-java 2.2.0's details API reports both cases as `TYPE_MISMATCH`; only its `get*OrThrow` family
tells them apart (`QuonfigCoercionException`). On `TYPE_MISMATCH` the provider re-reads through the
matching `get*OrThrow` and maps a coercion failure to `PARSE_ERROR`. The extra read happens only on
that error path. No dependency change.

## 3.0.0 - 2026-10-27

Bump the `com.quonfig:sdk-java` dependency from `2.1.0` to `2.2.0`: the cross-SDK typed-getter
contract (qfg-zvyy.7, qfg-koj9). A typed read that cannot return a value of the requested type
returns your default with reason `ERROR`, and sdk-java logs one WARN per key. Strings are never
coerced, and numbers convert only when the result is exact. Major release: sdk-java took this as a
minor because it mostly widens what the getters accept, but through OpenFeature the changes below
alter results on existing calls (a string read of a duration, malformed int/double values, a
64-bit int read as an integer), so the provider takes a major, like the other Quonfig providers.

### UPGRADE to 3.0

- **`getStringValue` on a `duration` config returns your default with `TYPE_MISMATCH`.** Before,
  it returned the ISO 8601 text (`"PT30S"`, reason `STATIC`). Durations are now native-only: read
  them with `getClient().getDuration()`. `getDoubleValue`, `getIntegerValue` and `getObjectValue`
  on a duration already returned your default.
- **`getDoubleValue` reads an int config** (`30` -> `30.0`), and **`getIntegerValue` reads a
  double config** holding a whole number within +/-(2^53-1) (`3.0` -> `3`). Before, both returned
  your default with `TYPE_MISMATCH`. `3.9`, `2^53` and `1e300` still return your default; nothing
  is truncated.
- **A malformed stored int or double returns your default with `TYPE_MISMATCH`.** Before, a stored
  `"twelve"` or `"abc"` read as `0` / `0.0` with reason `STATIC`.
- **A malformed ENV_VAR value reports `TYPE_MISMATCH`** (was `GENERAL`). The default is returned in
  both versions. An unset ENV_VAR still reports `GENERAL`.
- **`getIntegerValue` on an int config outside the 32-bit range returns your default with
  `TYPE_MISMATCH`** (provider fix). Before, the provider narrowed the SDK's 64-bit value with a
  wrapping cast, so `3000000000` read as `-1294967296`. Read such values with `getDoubleValue` or
  `getClient().getLong()`.
- sdk-java logs one WARN per key when a read returns your default because of a type mismatch, a
  malformed value, an unset ENV_VAR or a decryption failure. The line names the key, never the
  value. A wrong decryption key therefore degrades to your defaults with these WARNs.

These already held under 2.1.0, and the new tests now pin them: a stored string `"true"` /
`"false"` read as a boolean, a stored `"3"` read as a number, an int or bool read as a string, an
int read as a boolean, and a string, bool or duration read as an object all return your default
with `TYPE_MISMATCH`.

### Known issue

- The first `getObjectValue` on a `json` config logs one sdk-java WARN for that key ("is JSON,
  caller expected STRING_LIST"), although the read succeeds. The provider probes `string_list`
  before JSON, and sdk-java 2.2.0 has no type check that does not log. The value is unaffected.

### Tests

- New `TypedGetterContractTest` (32 tests) drives the real provider through the OpenFeature client
  against the integration-test-data `getter.contract.*` fixtures. On sdk-java 2.1.0, 8 fail; on
  2.2.0 with the provider fix, all pass.
- CI runs integration-test-data `v2026.10.26` (was `v2026.05.20`), which has those fixtures.

## 2.1.0 - 2026-10-23

Bump the `com.quonfig:sdk-java` dependency from `2.0.0` to `2.1.0` (requires sdk-java 2.1.0: size
caps on SSE events and config bodies, `callback_errors_total`, init fixes). This provider's own API
and code are unchanged; the changes below come from sdk-java 2.1.0 and reach OpenFeature users
through the provider's evaluations and the native client returned by `getClient()`.

### Upgrade notes

- **`HttpTransportException.bodyExcerpt()` holds at most 1 KiB (inherited, qfg-6vno.20.2).** The
  underlying client reads at most 1 KiB of a non-2xx response body (was 8 KiB). Code that parses a
  longer upstream error body out of the excerpt on the native client sees a shorter string. The body
  is never logged or put in an error message.

### Changed (inherited)

- **SSE events are capped at 4 MiB and config response bodies at 16 MiB (qfg-6vno.20.2).** An event
  over the cap is dropped and counts as a failed stream attempt, so the fallback poller keeps config
  fresh; an oversized config body fails that delivery leg like a 5xx and the client fails over.
  Before, one huge or unterminated event could throw `OutOfMemoryError`.
- **A throwing user callback is no longer counted as a worker restart (qfg-6vno.28).** Caught throws
  from `onConfigUpdate` and `onSseConnectionStateChange` listeners go to a new internal
  `quonfig_sdk_callback_errors_total{callback}` counter, and a throwing connection-state listener is
  now logged at ERROR instead of being swallowed. Both callback paths now catch `Exception`, not
  only `RuntimeException`.

### Fixed (inherited)

- A boot rescued by the secondary delivery leg no longer logs a spurious `[quonfig] init recovered`
  WARN (qfg-6vno.3).
- An HTTP 304 to the first config fetch, with no config installed yet, now counts as a failed
  delivery leg, so init keeps retrying instead of stopping with nothing loaded (qfg-6vno.11). Under
  `OnInitFailure.CONTINUE` the provider now reaches `READY` once the retry loads a config, instead
  of staying on code defaults until SSE delivers one.

## 2.0.0 - 2026-10-22

**BREAKING: major release.** Bump the `com.quonfig:sdk-java` dependency from `1.8.0` to `2.0.0` and map its init-failure modes
to OpenFeature provider status (qfg-9p4i.12; design doc section B.5).

### BREAKING (2.0.0)

- **BREAKING (inherited from sdk-java 2.0.0): `initialize()` now fails when delivery is down.**
  sdk-java 2.0 blocks its constructor until the first config loads and, by default
  (`onInitFailure` = `FAIL`), throws `QuonfigInitException` at `initTimeout` (10s by default; at
  once when every delivery leg rejects the SDK key). So `setProviderAndWait` throws and the
  provider status is `ERROR`. Before, the provider waited up to a fixed 15s and then also failed,
  but threw the client away. Set `.onInitFailure(OnInitFailure.CONTINUE)` to start on code defaults
  instead. Datadir mode is unchanged.

### Added

- `QuonfigProviderOptions.Builder.initTimeout(Duration)` and `.onInitFailure(OnInitFailure)`, passed
  through to sdk-java. Unset, the SDK defaults apply.

### Changed

- **The hard-coded 15s init wait is gone.** `initialize()` waits exactly as long as the SDK's
  `initTimeout`.
- **A failed init keeps the client (no orphan).** Under `FAIL` the provider keeps the client from
  `QuonfigInitException.client()`. It keeps retrying, and when its first config arrives the provider
  emits `PROVIDER_READY` and the status becomes `READY`.
- **`CONTINUE` with no config reports `ERROR`, not `READY`.** `initialize()` returns, then the
  provider emits `PROVIDER_ERROR` (non-fatal; evaluations return your defaults). It emits
  `PROVIDER_READY` once a config arrives. `WAIT` keeps the status `NOT_READY` until the first config.
- **Outage evaluations carry `PROVIDER_NOT_READY`.** sdk-java's new `PROVIDER_NOT_READY` error code
  maps to OpenFeature's `PROVIDER_NOT_READY` (was `GENERAL`).

## 1.5.0 - 2026-10-20

Bump the `com.quonfig:sdk-java` dependency from `1.7.0` to `1.8.0`. This provider's own API and
code are unchanged; the changes below come from sdk-java 1.8.0 and reach OpenFeature users through
the provider's evaluations and the native client returned by `getClient()`.

### Upgrade notes

- **SSE counts as connected at the first frame, not at the 200 response (qfg-goi1.10.9,
  qfg-goi1.10.42).** The underlying client now treats a stream as connected only once it delivers
  a config envelope or a keepalive. Against Quonfig's servers the two arrive together, so you
  should see no difference. Behind a proxy that sends 200 headers early, the native client's
  `connectionState()` and `onSseConnectionStateChange` listeners report `connected` up to one
  keepalive interval (30 s) later. A stream that closes before any frame is now a failed attempt:
  the reconnect delay grows and the fallback poller takes over after its usual threshold, instead
  of the client reconnecting in a tight loop and keeping stale config. This provider does not map
  connection state to OpenFeature events, so `PROVIDER_READY` and `PROVIDER_CONFIGURATION_CHANGED`
  fire as before.

### Fixed (inherited)

- **Weighted rollout variants keep their type (qfg-lrlh).** A json variant inside a
  `weighted_values` rollout now resolves through `getObjectValue` as a structured `Value`; before,
  it came back as the variant's `toString()` text (`"{a=1, b=c}"`). A provided (`ENV_VAR`) variant
  now reads its environment variable, so `getIntegerValue` / `getStringValue` return it instead of
  the default with `TYPE_MISMATCH`.
- **A `getDuration` evaluation no longer drops the telemetry window (qfg-a996).** OpenFeature has
  no duration type, but if your code calls `getDuration` on the native client (`getClient()`), the
  whole telemetry window (evaluation summaries, context shapes, examples) was dropped with a WARN.
  It is now reported as the stored ISO-8601 string.

## 1.4.0 - 2026-10-16

Bump the `com.quonfig:sdk-java` dependency from `1.6.0` to `1.7.0`. This provider's own API is
unchanged, but sdk-java 1.7.0 changes evaluation and connection behavior that OpenFeature users see
through `getBooleanValue`, `getStringValue`, `getObjectValue` and friends. Read the upgrade notes
before you roll it out.

### Upgrade notes

- **Some targeting rules match different users (qfg-goi1.5.8).** A context version of `"2"` or
  `"2.0"` (common for mobile app versions) now compares as `2.0.0` in `PROP_SEMVER_*` rules instead
  of never matching, so a user on `"4.1"` now matches `PROP_SEMVER_GREATER_THAN 4.0.18`. A
  whole-number `Double`/`Float`/`BigDecimal` context value (for example a numeric id in an
  OpenFeature `EvaluationContext`) is compared as plain digits (`1234567`), so
  `PROP_IS_ONE_OF ["1234567"]` now matches `1234567.0`, and lists holding `"1.0"` or exponent
  strings (`"5.0E9"`) stop matching. `IN_INT_RANGE` criteria stored as `int_range` now apply their
  `[start, end)` bounds instead of matching every value. Pass dates to `PROP_BEFORE` /
  `PROP_AFTER` rules as epoch milliseconds or ISO 8601 / RFC 3339 with `Z` or a numeric offset;
  date-only and offset-less strings never match.
- **A rejected SDK key backs off (qfg-goi1.10.27).** When fetches get 401, 403, 404 or 410, the
  underlying client stops SSE and polling and probes once every 150-300s instead, logging one
  ERROR (status and host, never the key). Evaluations keep serving the last good config; the first
  valid response resumes normal streaming. The provider emits no extra OpenFeature event for this.

### Also inherited

- A confidential or `decryptWith` weighted rollout variant is now decrypted for the caller and
  redacted in telemetry; before, `getStringValue` could return the raw ciphertext of a
  `decryptWith` variant (qfg-goi1.10.19).
- `getStringList` / `getJson` on the native client (`getClient()`) return a deep copy per call.
  The provider already converted these into OpenFeature `Value`s, so provider evaluations are
  unaffected (qfg-goi1.10.8).
- Telemetry example contexts are copied at record time, so an odd context value no longer drops
  the telemetry window (qfg-goi1.2.38); the SSE thread survives an `Error` (qfg-goi1.10.7); an
  `ENV_VAR` coercion error no longer contains the variable's value (qfg-goi1.2.32).

## 1.3.0 - 2026-10-09

- Bump the `com.quonfig:sdk-java` dependency from `1.2.0` to `1.6.0` to inherit
  the SDK quality program's Wave 0/1 fixes: a malformed config row is skipped
  instead of dropping the whole envelope, bounded SSE header wait, non-blocking
  pre-init `getLogLevel`, close-during-init fix (qfg-goi1.2.16, qfg-rriw,
  qfg-goi1.1.6) plus the 1.3.0-1.5.x fixes in between. No change to this
  provider's own public API.

## 1.2.0 - 2026-07-08

- Bump the `com.quonfig:sdk-java` dependency from `1.1.0` to `1.2.0` to inherit
  its failover-observability work: a WARN at init when an explicit `apiUrls`
  silently disables secondary failover, and a `failover` telemetry event carrying
  hedge-fired / guard-rejected / resolved-from counters folded into the existing
  periodic flush (qfg-41nh). Both are additive and pull in no new dependencies.
  No change to this provider's own public API — the behavior rides in via the SDK
  bump. Coordinated 1.2.0 version stamp across the Quonfig SDK family.

## 1.1.0 - 2026-07-01

- Bump the `com.quonfig:sdk-java` dependency from `1.0.0` to `1.1.0` to inherit
  the secondary-delivery failover work: request hedging across primary/secondary
  api-delivery, the reject-older generation guard, and the `gen<=0` carve-out
  that unblocks clients talking to pre-watermark servers (qfg-7h5d). No change
  to this provider's own public API — the failover behavior rides in via the
  SDK bump. Coordinated 1.1.0 version stamp across the Quonfig SDK family.

## 1.0.0 - 2026-06-06

- **Stable 1.0.0 release.** The Quonfig OpenFeature provider for Java is now declared
  stable and depends on `com.quonfig:sdk-java` 1.0.0. No API or behavior changes from
  0.0.4 — this is a coordinated 1.0.0 version stamp across the entire Quonfig SDK
  family.

## 0.0.4 - 2026-06-02

- Bump the `com.quonfig:sdk-java` dependency from `0.0.4` to `0.0.5` to inherit dev-context injection default-on (qfg-bw7g.9, via qfg-bw7g.6). No change to this provider's behavior — dev-context lives below the OpenFeature layer, so OpenFeature users now get `quonfig-user.email` injection by default in local dev (gated on the `qfg login` token file; inert in production).

## 0.0.3 - 2026-06-01

- **Fix: `getObjectValue` / `getObjectDetails` now return a navigable structure
  for JSON configs containing integers (qfg-07zr).** sdk-java parses JSON
  integers as `java.lang.Long`, and `Value.objectToValue` throws
  `TypeMismatchError` on `Long` (and `BigDecimal`). The provider previously
  handed the parsed payload to `objectToValue`, so any JSON object with an
  integer field fell into the catch and was returned as a stringified
  `Map.toString()` blob (`isStructure() == false`). The provider now builds the
  `Value` tree itself — `Map` → `MutableStructure`, `List` → `List<Value>`,
  numerics narrowed to `Integer` (when they fit) or `Double` — so nested objects
  with integer fields resolve as real, navigable structures.
- No public API changes; resolved values for already-working configs are
  unchanged.

## 0.0.2 - 2026-05-29

Bump the native SDK dependency from `com.quonfig:sdk-java:0.0.2` to `0.0.4`
(qfg-zinv).

- **Fix: per-environment overrides are now honored in delivery (SdkKey) mode.**
  Provider 0.0.1 depended on sdk-java 0.0.2, which predated the
  environment-override fix shipped in sdk-java 0.0.4 (qfg-xpln/qfg-pinh): the
  client parsed only the base `default` block of the delivery-wire payload and
  ignored the environment-specific override (`meta.environment` is now
  authoritative). Consumers building the provider from an SdkKey now resolve the
  environment override correctly.
- No provider source changes — the fix rides entirely in the bumped sdk-java
  dependency.
- Reason/variant labels for single ALWAYS_TRUE-criterion configs follow
  sdk-java's canonical STATIC/SPLIT semantics (qfg-q7yz): reason `STATIC`,
  variant `static` (previously `TARGETING_MATCH` / `targeting:0`). Resolved
  values are unchanged.

## 0.0.1 - 2026-05-28

First release of the OpenFeature provider for the Quonfig Java SDK
(`com.quonfig:openfeature-server-java`). Wraps `com.quonfig:sdk-java:0.0.2`
and mirrors the reference `openfeature-go` provider in behavior and house
style (qfg-3e6d.3).

- `QuonfigProvider extends EventProvider` — implements the OpenFeature
  `FeatureProvider` surface (boolean/string/integer/double/object evaluations,
  `initialize`, `shutdown`, `getMetadata` returning name `"quonfig"`).
- `QuonfigProviderOptions` — single options object with `sdkKey`, `datadir`,
  `environment`, and `targetingKeyMapping` (default `"user.id"`).
- Context mapping: flat OpenFeature keys map to Quonfig namespaces by splitting
  on the first dot; `targetingKey` resolves via `targetingKeyMapping`; null
  values are skipped; an empty/null context yields no `ContextSet`.
- Reason mapping passes the SDK reason through 1:1; `FLAG_NOT_FOUND` surfaces as
  reason `DEFAULT`, other error codes as `ERROR`. Evaluation never throws —
  errors return the caller's default with the error code/message populated.
- Variant is passed through verbatim from the SDK.
- Events: `PROVIDER_READY` on successful init, `PROVIDER_ERROR` on init failure
  (rethrown), `PROVIDER_CONFIGURATION_CHANGED` wired via the SDK's
  `onConfigUpdate` and guarded against the spurious initial-load callback.
- Native escape hatch: `getClient()` returns the underlying `Quonfig` client.
- Wires `Murmur3WeightedValueResolver` by default so `weighted_values` configs
  resolve (reason `SPLIT`) out of the box, matching sdk-go.
- Package-private constructor `QuonfigProvider(Quonfig client)` for injecting a
  pre-built client in tests.
- Datadir-mode integration tests run against the shared `integration-test-data`
  fixtures (no network).
