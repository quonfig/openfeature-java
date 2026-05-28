# Changelog

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
