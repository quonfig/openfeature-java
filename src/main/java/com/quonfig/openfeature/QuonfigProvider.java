package com.quonfig.openfeature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.EvaluationDetails;
import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;
import com.quonfig.sdk.eval.ContextSet;
import com.quonfig.sdk.eval.Murmur3WeightedValueResolver;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
  private volatile boolean ready;
  // True when the client was injected (test constructor); initialize() must not rebuild it.
  private final boolean clientInjected;

  /** Constructs a provider from {@link QuonfigProviderOptions}. */
  public QuonfigProvider(QuonfigProviderOptions options) {
    this.options =
        options != null ? options : QuonfigProviderOptions.builder().build();
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
      this.ready = true;
      emitProviderReady(ProviderEventDetails.builder().build());
      return;
    }
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
      // Wire config-change notifications -> PROVIDER_CONFIGURATION_CHANGED. Guard on `ready`
      // so the spurious initial-load callback fired during construction is suppressed (the
      // Go provider guards the same way).
      b.onConfigUpdate(
          () -> {
            if (ready) {
              emitProviderConfigurationChanged(
                  ProviderEventDetails.builder().flagsChanged(new ArrayList<>()).build());
            }
          });

      Quonfig c = new Quonfig(b.build());
      c.initFuture().get(15, TimeUnit.SECONDS);
      this.client = c;
      this.ready = true;
      emitProviderReady(ProviderEventDetails.builder().build());
    } catch (Exception e) {
      emitProviderError(ProviderEventDetails.builder().message(e.getMessage()).build());
      throw e;
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
    if (d.errorCode() != null || d.value() == null) {
      return build(defaultValue, d);
    }
    return build(d.value(), d);
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
      return build(defaultValue, d);
    }
    return build(d.value().intValue(), d);
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
      return build(defaultValue, d);
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

    // Try string_list first (mirrors the Go provider), then JSON.
    EvaluationDetails<List<String>> listDetails = c.getStringListDetails(key, null, qCtx);
    if (listDetails.errorCode() == null && listDetails.value() != null) {
      List<Value> values = new ArrayList<>(listDetails.value().size());
      for (String s : listDetails.value()) {
        values.add(new Value(s));
      }
      return build(new Value(values), listDetails);
    }

    EvaluationDetails<Object> jsonDetails = c.getJsonDetails(key, null, qCtx);
    if (jsonDetails.errorCode() != null || jsonDetails.value() == null) {
      return build(defaultValue, jsonDetails);
    }
    return build(jsonToValue(jsonDetails.value(), defaultValue), jsonDetails);
  }

  /**
   * Returns the underlying native Quonfig client for features not exposed via OpenFeature (duration
   * values, log levels, {@code keys()}, raw config access). Returns {@code null} before {@link
   * #initialize(EvaluationContext)} has completed.
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
      // Round-trip through Jackson so nested maps/lists become a JSON-shaped object the
      // OpenFeature objectToValue helper understands.
      Object normalized = JSON.convertValue(payload, Object.class);
      return Value.objectToValue(normalized);
    } catch (RuntimeException e) {
      if (payload != null) {
        return new Value(payload.toString());
      }
      return fallback;
    }
  }
}
