# openfeature-java

OpenFeature provider for [Quonfig](https://quonfig.com) — wraps the
[`com.quonfig:sdk-java`](https://github.com/quonfig/sdk-java) native SDK.

## Installation

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("com.quonfig:openfeature-server-java:2.2.0")
    implementation("dev.openfeature:sdk:1.20.2")
}
```

Maven:

```xml
<dependency>
  <groupId>com.quonfig</groupId>
  <artifactId>openfeature-server-java</artifactId>
  <version>2.2.0</version>
</dependency>
<dependency>
  <groupId>dev.openfeature</groupId>
  <artifactId>sdk</artifactId>
  <version>1.20.2</version>
</dependency>
```

## Usage

```java
import com.quonfig.openfeature.QuonfigProvider;
import com.quonfig.openfeature.QuonfigProviderOptions;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;

QuonfigProvider provider = new QuonfigProvider(
    QuonfigProviderOptions.builder()
        .sdkKey("qf_sk_production_...")
        .targetingKeyMapping("user.id") // default
        .build());

// Register and wait for initialization (calls initialize()).
OpenFeatureAPI.getInstance().setProviderAndWait(provider);

Client client = OpenFeatureAPI.getInstance().getClient();

// Boolean flag
boolean enabled = client.getBooleanValue("my-flag", false);

// String flag with context
MutableContext ctx = new MutableContext("user-123")
    .add("user.email", "alice@co.com")
    .add("org.tier", "enterprise");
String plan = client.getStringValue("billing.plan", "free", ctx);
```

## Context mapping

OpenFeature uses a flat key-value evaluation context. Quonfig uses a nested
namespace model. The provider maps between them using dot-notation, splitting
on the first dot only:

| OpenFeature key      | Quonfig namespace | Quonfig property                |
|----------------------|-------------------|---------------------------------|
| `user.email`         | `user`            | `email`                         |
| `org.tier`           | `org`             | `tier`                          |
| `country` (no dot)   | `` (default)      | `country`                       |
| `user.ip.address`    | `user`            | `ip.address` (split on 1st dot) |
| `targetingKey`       | `user`            | `id` (via `targetingKeyMapping`)|

To use a different targeting key property:

```java
QuonfigProvider provider = new QuonfigProvider(
    QuonfigProviderOptions.builder()
        .sdkKey("qf_sk_...")
        .targetingKeyMapping("org.id") // targetingKey -> org namespace, id property
        .build());
```

## Local / offline mode

Use `datadir` instead of `sdkKey` to load config from a local directory. This
makes no network calls.

```java
QuonfigProvider provider = new QuonfigProvider(
    QuonfigProviderOptions.builder()
        .datadir("/path/to/workspace")
        .environment("Production")
        .build());
```

## Native SDK escape hatch

For features not exposed via OpenFeature (parsed `java.time.Duration` values,
log levels, raw config access):

```java
com.quonfig.sdk.Quonfig nativeClient = provider.getClient();
java.time.Duration ttl = nativeClient.getDuration("cache.ttl", java.time.Duration.ZERO);
```

`getClient()` returns `null` before the provider has been initialized.

## Type mapping

| Quonfig type  | OpenFeature method     | Notes                                |
|---------------|------------------------|--------------------------------------|
| `bool`        | `getBooleanValue`      | Direct                               |
| `string`      | `getStringValue`       | Direct                               |
| `int`         | `getIntegerValue`      | 64-bit in the SDK; outside the `int` range returns your default with `TYPE_MISMATCH` |
| `int`         | `getDoubleValue`       | Converts exactly (`30` -> `30.0`)    |
| `double`      | `getDoubleValue`       | Direct                               |
| `double`      | `getIntegerValue`      | Only a whole number within +/-(2^53-1) and the `int` range (`3.0` -> `3`); `3.9` returns your default |
| `string_list` | `getObjectValue`       | Returns a `Value` list               |
| `json`        | `getObjectValue`       | Returns a parsed `Value` tree        |
| `duration`    | N/A                    | Native SDK only: `getClient().getDuration()` |
| `log_level`   | N/A                    | Native SDK only                      |

Object evaluation tries `string_list` first, then JSON (same precedence as the
Go provider).

### Typed-getter contract

The provider follows the typed-getter contract shared by every Quonfig SDK
(sdk-java 2.2.0). Reading a flag with the method for another type returns your
default with reason `ERROR` and error code `TYPE_MISMATCH`, and sdk-java logs one
WARN per key. Strings are never coerced: a stored `"true"` is not a boolean, a
stored `"3"` is not a number, and an int or bool config is not a string. Numbers
convert only when the result is exact (see the table above). A malformed stored
int or double, or a malformed ENV_VAR value, also returns your default with
`TYPE_MISMATCH`. An unset ENV_VAR returns your default with `GENERAL`.

Known issue: the first `getObjectValue` on a `json` config logs one sdk-java
WARN for that key (`read of "<key>" returned no value ...`, "is JSON, caller
expected STRING_LIST"), although the read succeeds. It comes from the
`string_list` probe above and does not affect the value.

## Reason and error mapping

Reasons are passed through from the SDK 1:1:

| Quonfig reason     | OpenFeature reason |
|--------------------|--------------------|
| `STATIC`           | `STATIC`           |
| `TARGETING_MATCH`  | `TARGETING_MATCH`  |
| `SPLIT`            | `SPLIT`            |
| `DEFAULT`          | `DEFAULT`          |
| anything else      | `UNKNOWN`          |

On error, `FLAG_NOT_FOUND` surfaces as reason `DEFAULT` (with error code
`FLAG_NOT_FOUND`); other error codes surface as reason `ERROR`. Evaluation never
throws — on error or a null value the caller's default is returned with the
error code/message populated on the `ProviderEvaluation`.

Error codes map: `FLAG_NOT_FOUND` -> `FLAG_NOT_FOUND`, `TYPE_MISMATCH` ->
`TYPE_MISMATCH`, `PROVIDER_NOT_READY` -> `PROVIDER_NOT_READY`, `GENERAL` ->
`GENERAL`. Calls made before the provider is initialized, or while no config has
loaded yet (see "Init failure"), return the default with `PROVIDER_NOT_READY`.

## Init failure

`initialize()` builds the sdk-java client, which blocks until the first config
loads and retries through blips until `initTimeout` (SDK default 10s). What
happens when delivery is down is set by `onInitFailure`; both options pass
straight through to sdk-java:

```java
QuonfigProviderOptions.builder()
    .sdkKey("qf_sk_production_...")
    .initTimeout(Duration.ofSeconds(5))          // default: SDK default (10s)
    .onInitFailure(OnInitFailure.CONTINUE)       // default: SDK default (FAIL)
    .build();
```

| `onInitFailure`      | `initialize()`                         | Provider status | On heal          |
|----------------------|----------------------------------------|-----------------|------------------|
| `FAIL` (default)     | Throws (`setProviderAndWait` throws)   | `ERROR`         | `PROVIDER_READY` |
| `WAIT`               | Blocks until the first config loads    | `NOT_READY`     | returns, `READY` |
| `CONTINUE`           | Returns, then emits `PROVIDER_ERROR`   | `ERROR`         | `PROVIDER_READY` |

Under `FAIL` and `CONTINUE` the provider keeps the client, which keeps retrying
in the background; evaluations return your default with `PROVIDER_NOT_READY`
until a config arrives. `ERROR` is non-fatal: evaluations still run. Datadir
mode loads synchronously and is unaffected.

## Events

- `PROVIDER_READY` is emitted after a successful `initialize()`, and when a
  client that started without config (`FAIL` or `CONTINUE` above) receives its
  first config.
- `PROVIDER_ERROR` is emitted if initialization fails (and the exception is
  rethrown from `initialize()`), and after `initialize()` returns under
  `CONTINUE` with no config.
- `PROVIDER_CONFIGURATION_CHANGED` is emitted when the SDK receives a config
  update (via the SDK's `onConfigUpdate` hook). The spurious initial-load
  callback is suppressed until the provider is ready.
- `STALE` is not emitted: sdk-java is a server SDK and keeps no cached config.

## What you lose vs. the native SDK

The OpenFeature spec covers common flag types. Some Quonfig-native features
require the native SDK directly (via `getClient()`):

1. **Log levels** (`shouldLog`, `getLogLevel`) — native SDK only.
2. **`duration` configs** are native-only: use `getClient().getDuration()` for a
   `java.time.Duration`. `getStringValue()` on a duration config returns your
   default with `TYPE_MISMATCH` (before 2.2.0 it returned the ISO 8601 text).
3. **`keys()`** and raw config access — native SDK only.
4. Context keys must use dot-notation (`"user.email"`, not nested objects).
5. `targetingKey` maps to `user.id` by default.

## Reason note (`ALWAYS_TRUE`)

A flag whose only rule is an unconditional match (`ALWAYS_TRUE`) resolves with
reason `STATIC`, the same as the Go SDK — `sdk-java` adopted the canonical
`STATIC`/`SPLIT` reason semantics in 0.0.3. The provider passes the SDK reason
through verbatim.
