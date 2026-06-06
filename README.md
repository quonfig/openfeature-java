# openfeature-java

OpenFeature provider for [Quonfig](https://quonfig.com) — wraps the
[`com.quonfig:sdk-java`](https://github.com/quonfig/sdk-java) native SDK.

## Installation

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("com.quonfig:openfeature-server-java:1.0.0")
    implementation("dev.openfeature:sdk:1.20.2")
}
```

Maven:

```xml
<dependency>
  <groupId>com.quonfig</groupId>
  <artifactId>openfeature-server-java</artifactId>
  <version>0.0.1</version>
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

For features not exposed via OpenFeature (duration values, log levels, raw
config access):

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
| `int`         | `getIntegerValue`      | SDK stores 64-bit; narrowed to `int` |
| `double`      | `getDoubleValue`       | Direct                               |
| `string_list` | `getObjectValue`       | Returns a `Value` list               |
| `json`        | `getObjectValue`       | Returns a parsed `Value` tree        |
| `duration`    | N/A                    | Use the native client                |
| `log_level`   | N/A                    | Native SDK only                      |

Object evaluation tries `string_list` first, then JSON (same precedence as the
Go provider).

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
`TYPE_MISMATCH`, `GENERAL` -> `GENERAL`. Calls made before the provider is
initialized return the default with `PROVIDER_NOT_READY`.

## Events

- `PROVIDER_READY` is emitted after a successful `initialize()`.
- `PROVIDER_ERROR` is emitted if initialization fails (and the exception is
  rethrown from `initialize()`).
- `PROVIDER_CONFIGURATION_CHANGED` is emitted when the SDK receives a config
  update (via the SDK's `onConfigUpdate` hook). The spurious initial-load
  callback is suppressed until the provider is ready.
- `STALE` is not emitted in this version.

## What you lose vs. the native SDK

The OpenFeature spec covers common flag types. Some Quonfig-native features
require the native SDK directly (via `getClient()`):

1. **Log levels** (`shouldLog`, `getLogLevel`) — native SDK only.
2. **`duration` configs** are not accessible via OpenFeature.
3. **`keys()`** and raw config access — native SDK only.
4. Context keys must use dot-notation (`"user.email"`, not nested objects).
5. `targetingKey` maps to `user.id` by default.

## Reason note (Java vs Go)

The Java SDK reports `TARGETING_MATCH` for a rule whose only criterion is
`ALWAYS_TRUE`, where the Go SDK reports `STATIC` for the same fixture. The
provider passes the SDK reason through verbatim, so behavior tracks whichever
SDK you wrap. This is an SDK-level difference, not a provider decision.
