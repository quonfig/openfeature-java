package com.quonfig.openfeature;

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

  private QuonfigProviderOptions(Builder b) {
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

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String sdkKey;
    private String datadir;
    private String environment;
    private String targetingKeyMapping;

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

    public QuonfigProviderOptions build() {
      return new QuonfigProviderOptions(this);
    }
  }
}
