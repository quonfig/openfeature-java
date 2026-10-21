package com.quonfig.openfeature;

import com.quonfig.sdk.OnInitFailure;
import java.time.Duration;

/**
 * Configures a {@link QuonfigProvider}. Build via {@link #builder()}.
 *
 * <p>Mirrors {@code openfeature-go}'s {@code Options} struct: a single options object carrying the
 * SDK key (or datadir + environment for offline mode) and the targeting-key mapping.
 */
public final class QuonfigProviderOptions {

  private final String sdkKey;
  private final String datadir;
  private final String environment;
  private final String targetingKeyMapping;
  private final Duration initTimeout;
  private final OnInitFailure onInitFailure;

  private QuonfigProviderOptions(Builder b) {
    this.initTimeout = b.initTimeout;
    this.onInitFailure = b.onInitFailure;
    this.sdkKey = b.sdkKey;
    this.datadir = b.datadir;
    this.environment = b.environment;
    this.targetingKeyMapping =
        (b.targetingKeyMapping == null || b.targetingKeyMapping.isEmpty())
            ? "user.id"
            : b.targetingKeyMapping;
  }

  /** The Quonfig API key (e.g. {@code "qf_sk_production_..."}). Mutually exclusive with datadir. */
  public String sdkKey() {
    return sdkKey;
  }

  /** Local Quonfig workspace directory for offline/test mode. Mutually exclusive with sdkKey. */
  public String datadir() {
    return datadir;
  }

  /** Which environment to evaluate (e.g. {@code "Production"}). */
  public String environment() {
    return environment;
  }

  /**
   * How the OpenFeature {@code targetingKey} maps to a Quonfig context property, in dot-notation.
   * {@code "user.id"} means namespace {@code "user"}, property {@code "id"}. Defaults to {@code
   * "user.id"}.
   */
  public String targetingKeyMapping() {
    return targetingKeyMapping;
  }

  /**
   * How long {@link QuonfigProvider#initialize} waits for the first config before applying {@link
   * #onInitFailure()}. Passed through to sdk-java's {@code Options.initTimeout}. {@code null} (the
   * default) uses the SDK default (10s). A value of zero or less runs exactly one attempt.
   */
  public Duration initTimeout() {
    return initTimeout;
  }

  /**
   * What happens when no config has loaded by {@link #initTimeout()}. Passed through to sdk-java's
   * {@code Options.onInitFailure}. {@code null} (the default) uses the SDK default, {@link
   * OnInitFailure#FAIL}.
   *
   * <ul>
   *   <li>{@code FAIL}: {@code initialize} throws and the provider status is ERROR. The client keeps
   *       retrying; when a config arrives the provider emits {@code PROVIDER_READY}.
   *   <li>{@code WAIT}: {@code initialize} blocks until a config loads (status NOT_READY).
   *   <li>{@code CONTINUE}: {@code initialize} returns, then the provider emits {@code
   *       PROVIDER_ERROR} (status ERROR, evaluations return code defaults with {@code
   *       PROVIDER_NOT_READY}) until a config arrives and it emits {@code PROVIDER_READY}.
   * </ul>
   */
  public OnInitFailure onInitFailure() {
    return onInitFailure;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String sdkKey;
    private String datadir;
    private String environment;
    private String targetingKeyMapping;
    private Duration initTimeout;
    private OnInitFailure onInitFailure;

    public Builder sdkKey(String v) {
      this.sdkKey = v;
      return this;
    }

    public Builder datadir(String v) {
      this.datadir = v;
      return this;
    }

    public Builder environment(String v) {
      this.environment = v;
      return this;
    }

    public Builder targetingKeyMapping(String v) {
      this.targetingKeyMapping = v;
      return this;
    }

    /** See {@link QuonfigProviderOptions#initTimeout()}. */
    public Builder initTimeout(Duration v) {
      this.initTimeout = v;
      return this;
    }

    /** See {@link QuonfigProviderOptions#onInitFailure()}. */
    public Builder onInitFailure(OnInitFailure v) {
      this.onInitFailure = v;
      return this;
    }

    public QuonfigProviderOptions build() {
      return new QuonfigProviderOptions(this);
    }
  }
}
