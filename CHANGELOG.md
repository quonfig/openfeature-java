# Changelog

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
