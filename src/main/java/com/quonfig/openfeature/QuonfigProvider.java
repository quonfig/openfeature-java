package com.quonfig.openfeature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.EvaluationDetails;
import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;
import com.quonfig.sdk.eval.ContextSet;
import com.quonfig.sdk.eval.Murmur3WeightedValueResolver;
import com.quonfig.sdk.eval.ValueType;
import com.quonfig.sdk.exceptions.QuonfigCoercionException;
import com.quonfig.sdk.exceptions.QuonfigInitException;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * An OpenFeature provider that wraps the {@code com.quonfig:sdk-java} native SDK.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * QuonfigProvider provider = new QuonfigProvider(
 *     QuonfigProviderOptions.builder()
 *         .sdkKey("qf_sk_production_...")
 *         .build());
 * OpenFeatureAPI.getInstance().setProviderAndWait(provider);
 * Client client = OpenFeatureAPI.getInstance().getClient();
 * boolean enabled = client.getBooleanValue("my-flag", false);
 * }</pre>
 *
 * <p>Behavior mirrors the reference {@code openfeature-go} provider: flat OpenFeature context keys
 * map to Quonfig namespaces by splitting on the first dot, evaluation never throws (errors are
 * surfaced via {@link ProviderEvaluation#getErrorCode()} with the caller's default value), and the
 * Quonfig variant string is passed through unchanged.
 */
public class QuonfigProvider extends EventProvider {

  private static final String PROVIDER_NAME = "quonfig";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final QuonfigProviderOptions options;
  private volatile Quonfig client;
  // True once the client has config and the provider has reported READY.
  private volatile boolean ready;
  // True once initialize() has decided its outcome; config callbacks before that are the
  // initial load and are suppressed.
  private boolean initDecided;
  private final Object stateLock = new Object();
  // True when the client was injected (test constructor); initialize() must not rebuild it.
  private final boolean clientInjected;
  // Test seam; null in production.
  private final Consumer<Options.Builder> sdkOptions;

  /** Constructs a provider from {@link QuonfigProviderOptions}. */
  public QuonfigProvider(QuonfigProviderOptions options) {
    this(options, null);
  }

  /**
   * Test seam: {@code sdkOptions} tweaks the sdk-java {@link Options.Builder} after the provider
   * has applied {@link QuonfigProviderOptions} (e.g. to point delivery at a local test server).
   */
  QuonfigProvider(QuonfigProviderOptions options, Consumer<Options.Builder> sdkOptions) {
    this.options =
        options != null ? options : QuonfigProviderOptions.builder().build();
    this.sdkOptions = sdkOptions;
    this.clientInjected = false;
  }

  /**
   * Test seam: injects an already-built {@link Quonfig} client. The provider is considered ready
   * immediately (the SDK installs datadir/datafile config synchronously at construction), and
   * {@link #initialize(EvaluationContext)} will not rebuild the client.
   */
  QuonfigProvider(Quonfig client) {
    this.options = QuonfigProviderOptions.builder().build();
    this.client = client;
    this.clientInjected = true;
    this.sdkOptions = null;
    this.ready = client != null;
  }

  @Override
  public Metadata getMetadata() {
    return () -> PROVIDER_NAME;
  }

  @Override
  public void initialize(EvaluationContext evaluationContext) throws Exception {
    if (clientInjected) {
      // Client was supplied by the test constructor — it's already installed and ready.
      synchronized (stateLock) {
        this.initDecided = true;
        this.ready = true;
      }
      emitProviderReady(ProviderEventDetails.builder().build());
      return;
    }
    Options sdkOptionsBuilt;
    try {
      Options.Builder b = Options.builder();
      // Wire the Murmur3 bucketing strategy so weighted_values configs resolve (SPLIT) out of
      // the box, matching sdk-go's default. The Java SDK leaves weightedValueResolver null
      // unless the caller sets it.
      b.weightedValueResolver(new Murmur3WeightedValueResolver());
      if (options.sdkKey() != null && !options.sdkKey().isEmpty()) {
        b.sdkKey(options.sdkKey());
      }
      if (options.datadir() != null && !options.datadir().isEmpty()) {
        b.datadir(options.datadir());
      }
      if (options.environment() != null && !options.environment().isEmpty()) {
        b.environment(options.environment());
      }
      if (options.initTimeout() != null) {
        b.initTimeout(options.initTimeout());
      }
      if (options.onInitFailure() != null) {
        b.onInitFailure(options.onInitFailure());
      }
      // Config-change notifications. The callback that fires during construction (the initial
      // load) is suppressed (the Go provider guards the same way). After initialize() has
      // decided, the first config to arrive on a client that had none is the heal ->
      // PROVIDER_READY; every later one is PROVIDER_CONFIGURATION_CHANGED.
      b.onConfigUpdate(this::onConfigUpdate);

      if (sdkOptions != null) {
        sdkOptions.accept(b);
      }
      sdkOptionsBuilt = b.build();
    } catch (Exception e) {
      emitProviderError(ProviderEventDetails.builder().message(e.getMessage()).build());
      throw e;
    }

    // sdk-java 2.0 blocks in the constructor until the first config loads, retrying until
    // initTimeout, then applies onInitFailure (FAIL throws, WAIT keeps blocking, CONTINUE returns
    // with no config). There is no separate wait here: initTimeout is the SDK's.
    Quonfig c;
    try {
      c = new Quonfig(sdkOptionsBuilt);
    } catch (QuonfigInitException e) {
      // FAIL: keep the client the constructor built. It keeps retrying in the background, and
      // its first config emits PROVIDER_READY (design B.5; no orphaned client).
      if (decide(e.client())) {
        emitProviderReady(ProviderEventDetails.builder().build()); // healed in the gap
        return;
      }
      emitProviderError(
          ProviderEventDetails.builder()
              .message(e.getMessage())
              .errorCode(ErrorCode.PROVIDER_NOT_READY)
              .build());
      throw e;
    } catch (Exception e) {
      emitProviderError(ProviderEventDetails.builder().message(e.getMessage()).build());
      throw e;
    }

    if (decide(c)) {
      emitProviderReady(ProviderEventDetails.builder().build());
    } else {
      // CONTINUE with no config: initialize resolves, but every evaluation is a code default, so
      // the status is ERROR (non-fatal), not READY. The emit is applied after OpenFeature marks
      // the provider READY on return (setProviderAndWait holds the API lock until then).
      emitProviderError(
          ProviderEventDetails.builder()
              .message(
                  "[quonfig] no config loaded by initTimeout (onInitFailure=CONTINUE); serving"
                      + " code defaults until delivery recovers")
              .errorCode(ErrorCode.PROVIDER_NOT_READY)
              .build());
    }
  }

  /**
   * Records the client initialize() ended with and whether it has config. Returns that readiness.
   * Synchronized with {@link #onConfigUpdate()} so a heal that lands while initialize() is
   * deciding is reported exactly once.
   */
  private boolean decide(Quonfig c) {
    synchronized (stateLock) {
      this.client = c;
      this.initDecided = true;
      this.ready = c != null && c.isReady();
      return this.ready;
    }
  }

  private void onConfigUpdate() {
    boolean healed;
    synchronized (stateLock) {
      if (!initDecided) {
        return; // initial load during construction
      }
      healed = !ready;
      ready = true;
    }
    // Emit outside the lock (the emit itself is asynchronous in the OpenFeature SDK).
    if (healed) {
      emitProviderReady(ProviderEventDetails.builder().build());
    } else {
      emitProviderConfigurationChanged(
          ProviderEventDetails.builder().flagsChanged(new ArrayList<>()).build());
    }
  }

  @Override
  public void shutdown() {
    Quonfig c = this.client;
    if (c != null) {
      c.close();
    }
  }

  @Override
  public ProviderEvaluation<Boolean> getBooleanEvaluation(
      String key, Boolean defaultValue, EvaluationContext ctx) {
    Quonfig c = this.client;
    if (c == null) {
      return notReady(defaultValue);
    }
    ContextSet qCtx = ContextMapper.mapContext(ctx, options.targetingKeyMapping());
    EvaluationDetails<Boolean> d = c.getBooleanDetails(key, defaultValue, qCtx);
    if (d.errorCode() != null || d.value() == null) {
      return build(defaultValue, d);
    }
    return build(d.value(), d);
  }

  @Override
  public ProviderEvaluation<String> getStringEvaluation(
      String key, String defaultValue, EvaluationContext ctx) {
    Quonfig c = this.client;
    if (c == null) {
      return notReady(defaultValue);
    }
    ContextSet qCtx = ContextMapper.mapContext(ctx, options.targetingKeyMapping());
    EvaluationDetails<String> d = c.getStringDetails(key, defaultValue, qCtx);
    if (d.errorCode() == null && d.value() != null) {
      return build(d.value(), d);
    }
    if (d.errorCode() == com.quonfig.sdk.ErrorCode.TYPE_MISMATCH) {
      // A duration config read as a string returns its stored ISO 8601 text (openfeature.yaml
      // Case 2, openfeature-go's stringOf). getStringDetails rejects a duration at its type check,
      // before evaluating, so it records no evaluation telemetry; the untyped read below is the
      // one evaluation (and one telemetry record) for this call.
      EvaluationDetails<Object> raw = c.evaluateDetails(key, qCtx);
      if (raw.valueType() == ValueType.DURATION) {
        return durationAsString(key, defaultValue, raw);
      }
    }
    return build(defaultValue, d);
  }

  @Override
  public ProviderEvaluation<Integer> getIntegerEvaluation(
      String key, Integer defaultValue, EvaluationContext ctx) {
    Quonfig c = this.client;
    if (c == null) {
      return notReady(defaultValue);
    }
    ContextSet qCtx = ContextMapper.mapContext(ctx, options.targetingKeyMapping());
    // Quonfig has no int getter — values are 64-bit Long; narrow to Integer for OpenFeature.
    Long def = defaultValue == null ? null : defaultValue.longValue();
    EvaluationDetails<Long> d = c.getIntDetails(key, def, qCtx);
    if (d.errorCode() != null || d.value() == null) {
      return buildFailure(key, "int", defaultValue, d, () -> c.getLongOrThrow(key, qCtx));
    }
    long v = d.value();
    if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
      // No exact Integer for this value: return the default rather than wrap it, the same
      // "only exact conversions" rule the SDK applies to doubles (qfg-zvyy.7).
      return ProviderEvaluation.<Integer>builder()
          .value(defaultValue)
          .variant("default")
          .reason(dev.openfeature.sdk.Reason.ERROR.name())
          .errorCode(ErrorCode.TYPE_MISMATCH)
          .errorMessage(
              "config \"" + key + "\" is outside the 32-bit integer range; read it with"
                  + " getDoubleValue or getClient().getLong")
          .flagMetadata(Mappings.flagMetadataFor(d))
          .build();
    }
    return build((int) v, d);
  }

  @Override
  public ProviderEvaluation<Double> getDoubleEvaluation(
      String key, Double defaultValue, EvaluationContext ctx) {
    Quonfig c = this.client;
    if (c == null) {
      return notReady(defaultValue);
    }
    ContextSet qCtx = ContextMapper.mapContext(ctx, options.targetingKeyMapping());
    EvaluationDetails<Double> d = c.getDoubleDetails(key, defaultValue, qCtx);
    if (d.errorCode() != null || d.value() == null) {
      return buildFailure(key, "double", defaultValue, d, () -> c.getDoubleOrThrow(key, qCtx));
    }
    return build(d.value(), d);
  }

  @Override
  public ProviderEvaluation<Value> getObjectEvaluation(
      String key, Value defaultValue, EvaluationContext ctx) {
    Quonfig c = this.client;
    if (c == null) {
      return notReady(defaultValue);
    }
    ContextSet qCtx = ContextMapper.mapContext(ctx, options.targetingKeyMapping());

    // One untyped read, then pick the shape from the config's stored type (qfg-tzby). Trying the
    // typed getters in turn would log sdk-java's once-per-key type-mismatch WARN for a json config
    // that the provider then returns.
    EvaluationDetails<Object> raw = c.evaluateDetails(key, qCtx);
    ValueType vt = raw.valueType();
    if (vt == ValueType.STRING_LIST || vt == ValueType.JSON) {
      if (raw.errorCode() != null || raw.value() == null) {
        return build(defaultValue, raw);
      }
      if (vt == ValueType.STRING_LIST && raw.value() instanceof List) {
        List<?> list = (List<?>) raw.value();
        List<Value> values = new ArrayList<>(list.size());
        for (Object s : list) {
          values.add(new Value(String.valueOf(s)));
        }
        return build(new Value(values), raw);
      }
      return build(jsonToValue(raw.value(), defaultValue), raw);
    }
    if (raw.errorCode() != null && vt == null) {
      // Not found, not ready, closed or an evaluation failure: the SDK result as-is.
      return build(defaultValue, raw);
    }
    // Not an object type: the typed json read reports the caller's TYPE_MISMATCH (and its WARN).
    // It fails at the SDK's type check, before evaluating.
    EvaluationDetails<Object> jsonDetails = c.getJsonDetails(key, null, qCtx);
    return build(defaultValue, jsonDetails);
  }

  /**
   * Returns the underlying native Quonfig client for features not exposed via OpenFeature (parsed
   * {@code java.time.Duration} values, log levels, {@code keys()}, raw config access). Returns
   * {@code null} before {@link #initialize(EvaluationContext)} has completed.
   */
  public Quonfig getClient() {
    return this.client;
  }

  // --- private helpers ---

  private static <T> ProviderEvaluation<T> build(T value, EvaluationDetails<?> d) {
    return ProviderEvaluation.<T>builder()
        .value(value)
        .variant(d.variant())
        .reason(Mappings.reasonFor(d))
        .errorCode(Mappings.errorCodeFor(d))
        .errorMessage(d.errorMessage())
        .flagMetadata(Mappings.flagMetadataFor(d))
        .build();
  }

  /**
   * Builds the result for a failed typed read. sdk-java's details API reports both a wrong-type
   * read and a value of the right type that does not parse as {@code TYPE_MISMATCH}; only its
   * {@code get*OrThrow} family tells them apart, with {@link QuonfigCoercionException} for the
   * latter. So on {@code TYPE_MISMATCH} the provider re-reads through {@code probe} (the matching
   * {@code get*OrThrow}) and reports a coercion failure as {@code PARSE_ERROR} (qfg-9xsl), the same
   * split openfeature-go makes. The probe runs only on this error path.
   */
  private static <T> ProviderEvaluation<T> buildFailure(
      String key, String type, T defaultValue, EvaluationDetails<?> d, Runnable probe) {
    ProviderEvaluation<T> result = build(defaultValue, d);
    if (d.errorCode() == com.quonfig.sdk.ErrorCode.TYPE_MISMATCH && isUnparseable(probe)) {
      result.setErrorCode(ErrorCode.PARSE_ERROR);
      // The message names the key, never the value.
      result.setErrorMessage("config \"" + key + "\" has a malformed " + type + " value");
    }
    return result;
  }

  /**
   * Maps an untyped read of a duration config to a string result: well-formed ISO 8601 text is
   * returned exactly as stored (never re-normalized, so "PT90S" stays "PT90S") with the
   * evaluation's reason, variant and metadata; a stored or ENV_VAR value that does not parse as a
   * duration is the caller's default with {@code PARSE_ERROR}.
   */
  private static ProviderEvaluation<String> durationAsString(
      String key, String defaultValue, EvaluationDetails<Object> raw) {
    if (raw.errorCode() == null && raw.value() instanceof String) {
      String text = (String) raw.value();
      try {
        java.time.Duration.parse(text);
        return build(text, raw);
      } catch (java.time.DateTimeException e) {
        // fall through to PARSE_ERROR
      }
    } else if (raw.errorCode() != com.quonfig.sdk.ErrorCode.TYPE_MISMATCH) {
      // Not a parse problem (no value matched, evaluation failure): pass the SDK result through.
      return build(defaultValue, raw);
    }
    ProviderEvaluation<String> result = build(defaultValue, raw);
    result.setVariant("default");
    result.setReason(dev.openfeature.sdk.Reason.ERROR.name());
    result.setErrorCode(ErrorCode.PARSE_ERROR);
    // The message names the key, never the value.
    result.setErrorMessage("config \"" + key + "\" has a malformed duration value");
    return result;
  }

  private static boolean isUnparseable(Runnable probe) {
    try {
      probe.run();
      return false;
    } catch (QuonfigCoercionException e) {
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static <T> ProviderEvaluation<T> notReady(T defaultValue) {
    return ProviderEvaluation.<T>builder()
        .value(defaultValue)
        .variant("default")
        .reason(dev.openfeature.sdk.Reason.ERROR.name())
        .errorCode(ErrorCode.PROVIDER_NOT_READY)
        .errorMessage("provider not initialized")
        .build();
  }

  /**
   * Converts a parsed Quonfig JSON payload into an OpenFeature {@link Value} tree. Falls back to the
   * caller's default if the payload can't be represented (pragmatic; mirrors the Go provider's
   * best-effort approach).
   */
  private static Value jsonToValue(Object payload, Value fallback) {
    try {
      // Round-trip through Jackson so nested maps/lists become plain Map/List/Number/String/Boolean
      // values, then build the Value tree ourselves. We can't hand the result to
      // Value.objectToValue: sdk-java parses JSON integers as java.lang.Long, and objectToValue
      // throws TypeMismatchError on Long (and BigDecimal), which would stringify the whole object.
      Object normalized = JSON.convertValue(payload, Object.class);
      return buildValue(normalized);
    } catch (RuntimeException e) {
      if (payload != null) {
        return new Value(payload.toString());
      }
      return fallback;
    }
  }

  /**
   * Recursively builds an OpenFeature {@link Value} from a plain Jackson-normalized object tree
   * (Map / List / Number / String / Boolean / null). Numerics are narrowed to the two types
   * OpenFeature's {@link Value} supports: a {@link Long} that fits in an {@code int} becomes an
   * {@link Integer}, everything else (including {@link Long} that overflows int) becomes a {@link
   * Double}.
   */
  private static Value buildValue(Object node) {
    if (node == null) {
      return new Value();
    }
    if (node instanceof java.util.Map) {
      dev.openfeature.sdk.MutableStructure structure = new dev.openfeature.sdk.MutableStructure();
      for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) node).entrySet()) {
        structure.add(String.valueOf(entry.getKey()), buildValue(entry.getValue()));
      }
      return new Value(structure);
    }
    if (node instanceof List) {
      List<?> list = (List<?>) node;
      List<Value> values = new ArrayList<>(list.size());
      for (Object element : list) {
        values.add(buildValue(element));
      }
      return new Value(values);
    }
    if (node instanceof Boolean) {
      return new Value((Boolean) node);
    }
    if (node instanceof String) {
      return new Value((String) node);
    }
    if (node instanceof Number) {
      Number number = (Number) node;
      if (number instanceof Integer) {
        return new Value((Integer) number);
      }
      if (number instanceof Double || number instanceof Float) {
        return new Value(number.doubleValue());
      }
      // Long, BigInteger, BigDecimal, etc. — narrow to Integer when it fits, else Double.
      long asLong = number.longValue();
      if (number.doubleValue() == asLong && asLong >= Integer.MIN_VALUE && asLong <= Integer.MAX_VALUE) {
        return new Value((int) asLong);
      }
      return new Value(number.doubleValue());
    }
    // Unknown leaf type — represent as its string form rather than dropping it.
    return new Value(node.toString());
  }
}
