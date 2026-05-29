package com.quonfig.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class QuonfigProviderTest {

  /** Absolute path to the integration-test-data fixtures (sibling repo of openfeature-java). */
  private static String integrationTestDataDir() {
    // Gradle pins the test working directory to the project dir; integration-test-data is a
    // sibling of this repo.
    Path base = Paths.get(System.getProperty("user.dir"));
    return base.resolve("../integration-test-data/data/integration-tests")
        .normalize()
        .toAbsolutePath()
        .toString();
  }

  private static Quonfig newDatadirClient() {
    Quonfig c =
        new Quonfig(
            Options.builder()
                .datadir(integrationTestDataDir())
                .environment("Production")
                .disableTelemetry(true)
                .build());
    return c;
  }

  /** Builds a provider backed by a datadir client and initializes it. */
  private static QuonfigProvider newDatadirProvider() throws Exception {
    QuonfigProvider provider =
        new QuonfigProvider(
            QuonfigProviderOptions.builder()
                .datadir(integrationTestDataDir())
                .environment("Production")
                .build());
    provider.initialize(new MutableContext());
    return provider;
  }

  // --- Metadata ---

  @Test
  void metadataName_isQuonfig() {
    QuonfigProvider provider =
        new QuonfigProvider(QuonfigProviderOptions.builder().build());
    assertEquals("quonfig", provider.getMetadata().getName());
  }

  // --- Not-initialized path ---

  @Test
  void notInitialized_returnsDefaultWithProviderNotReady() {
    QuonfigProvider provider =
        new QuonfigProvider(
            QuonfigProviderOptions.builder()
                .datadir("/nonexistent/path")
                .environment("Production")
                .build());
    // Do NOT initialize.
    ProviderEvaluation<Boolean> b = provider.getBooleanEvaluation("some-flag", true, null);
    assertTrue(b.getValue());
    assertEquals(Reason.ERROR.name(), b.getReason());
    assertEquals(ErrorCode.PROVIDER_NOT_READY, b.getErrorCode());

    assertEquals("default", provider.getStringEvaluation("some-flag", "default", null).getValue());
    assertEquals(42, provider.getIntegerEvaluation("some-flag", 42, null).getValue());
    assertEquals(3.14, provider.getDoubleEvaluation("some-flag", 3.14, null).getValue(), 0.001);
  }

  // --- Injected-client constructor ---

  @Test
  void injectedClientConstructor_works() throws Exception {
    Quonfig client = newDatadirClient();
    QuonfigProvider provider = new QuonfigProvider(client);
    provider.initialize(new MutableContext());

    ProviderEvaluation<Boolean> b = provider.getBooleanEvaluation("always.true", false, null);
    assertTrue(b.getValue());
    // A single ALWAYS_TRUE-criterion config resolves with the canonical STATIC reason (sdk-java
    // 0.0.3+ aligned to the canonical STATIC/SPLIT semantics, qfg-q7yz). Reason is passed through
    // from the SDK verbatim, so the provider follows the SDK here.
    assertEquals(Reason.STATIC.name(), b.getReason());
    assertNotNull(provider.getClient());
    provider.shutdown();
  }

  // --- Integration: typed evaluations ---

  @Test
  void integration_booleanFlag_alwaysTrue() throws Exception {
    QuonfigProvider provider = newDatadirProvider();
    ProviderEvaluation<Boolean> d = provider.getBooleanEvaluation("always.true", false, null);
    assertTrue(d.getValue());
    // See note in injectedClientConstructor_works: a single ALWAYS_TRUE-criterion config is STATIC.
    assertEquals(Reason.STATIC.name(), d.getReason());
    provider.shutdown();
  }

  @Test
  void integration_stringFlag() throws Exception {
    QuonfigProvider provider = newDatadirProvider();
    ProviderEvaluation<String> d =
        provider.getStringEvaluation("brand.new.string", "default", null);
    assertEquals("hello.world", d.getValue());
    assertEquals(Reason.STATIC.name(), d.getReason());
    provider.shutdown();
  }

  @Test
  void integration_intFlag() throws Exception {
    QuonfigProvider provider = newDatadirProvider();
    ProviderEvaluation<Integer> d = provider.getIntegerEvaluation("brand.new.int", 0, null);
    assertEquals(123, d.getValue());
    provider.shutdown();
  }

  @Test
  void integration_unknownFlag_returnsDefaultWithFlagNotFound() throws Exception {
    QuonfigProvider provider = newDatadirProvider();
    ProviderEvaluation<Boolean> d =
        provider.getBooleanEvaluation("this-flag-does-not-exist", true, null);
    assertTrue(d.getValue());
    assertEquals(Reason.DEFAULT.name(), d.getReason());
    assertEquals(ErrorCode.FLAG_NOT_FOUND, d.getErrorCode());

    ProviderEvaluation<String> s =
        provider.getStringEvaluation("nonexistent-flag-xyz", "fallback", null);
    assertEquals("fallback", s.getValue());
    assertEquals(Reason.DEFAULT.name(), s.getReason());
    assertEquals(ErrorCode.FLAG_NOT_FOUND, s.getErrorCode());
    provider.shutdown();
  }

  @Test
  void integration_dotNotationContext() throws Exception {
    QuonfigProvider provider = newDatadirProvider();

    ProviderEvaluation<String> noCtx =
        provider.getStringEvaluation("my-test-key", "default", null);
    assertEquals("my-test-value", noCtx.getValue());

    EvaluationContext ctx = new MutableContext().add("namespace.key", "present");
    ProviderEvaluation<String> withCtx =
        provider.getStringEvaluation("my-test-key", "default", ctx);
    assertEquals("namespace-value", withCtx.getValue());
    provider.shutdown();
  }

  // --- Integration: reason field ---

  @Test
  void integration_reasonPassthrough_alwaysTrueRule() throws Exception {
    QuonfigProvider provider = newDatadirProvider();
    ProviderEvaluation<String> d =
        provider.getStringEvaluation("brand.new.string", "default", null);
    assertEquals("hello.world", d.getValue());
    // brand.new.string's single rule has an ALWAYS_TRUE criterion -> canonical STATIC reason
    // (sdk-java 0.0.3+, qfg-q7yz). Provider passes the SDK reason through.
    assertEquals(Reason.STATIC.name(), d.getReason());
    provider.shutdown();
  }

  @Test
  void integration_targetingMatchReason_ruleMatches() throws Exception {
    QuonfigProvider provider = newDatadirProvider();
    EvaluationContext ctx = new MutableContext().add("user.plan", "pro");
    ProviderEvaluation<Boolean> d = provider.getBooleanEvaluation("of.targeting", false, ctx);
    assertTrue(d.getValue());
    assertEquals(Reason.TARGETING_MATCH.name(), d.getReason());
    provider.shutdown();
  }

  @Test
  void integration_targetingMatchReason_fallthrough() throws Exception {
    QuonfigProvider provider = newDatadirProvider();
    // No user.plan="pro" -> falls through to the ALWAYS_TRUE rule (value false), TARGETING_MATCH.
    ProviderEvaluation<Boolean> d = provider.getBooleanEvaluation("of.targeting", true, null);
    assertFalse(d.getValue());
    assertEquals(Reason.TARGETING_MATCH.name(), d.getReason());
    provider.shutdown();
  }

  @Test
  void integration_splitReason_weightedValue() throws Exception {
    QuonfigProvider provider = newDatadirProvider();
    // of.weighted hashes by user.id -> targetingKey maps to user.id by default.
    EvaluationContext ctx = new MutableContext().setTargetingKey("92a202f2");
    ProviderEvaluation<String> d = provider.getStringEvaluation("of.weighted", "default", ctx);
    assertNotEquals("default", d.getValue());
    assertEquals(Reason.SPLIT.name(), d.getReason());
    provider.shutdown();
  }

  @Test
  void integration_targetingKeyContext_inSegment() throws Exception {
    QuonfigProvider provider = newDatadirProvider();
    EvaluationContext in = new MutableContext().add("user.key", "jeffrey");
    ProviderEvaluation<Boolean> d =
        provider.getBooleanEvaluation("feature-flag.in-segment.positive", false, in);
    assertTrue(d.getValue());

    EvaluationContext out = new MutableContext().add("user.key", "unknown-user");
    ProviderEvaluation<Boolean> dOut =
        provider.getBooleanEvaluation("feature-flag.in-segment.positive", true, out);
    assertFalse(dOut.getValue());
    provider.shutdown();
  }

  @Test
  void integration_getClient_exposesNativeKeys() throws Exception {
    QuonfigProvider provider = newDatadirProvider();
    Quonfig client = provider.getClient();
    assertNotNull(client);
    assertTrue(client.keys().size() > 0);
    provider.shutdown();
  }

  @Test
  void integration_variantPassthrough() throws Exception {
    QuonfigProvider provider = newDatadirProvider();
    ProviderEvaluation<String> d =
        provider.getStringEvaluation("brand.new.string", "default", null);
    // Variant is passed through verbatim from the SDK. A single ALWAYS_TRUE-criterion config
    // resolves STATIC (sdk-java 0.0.3+, qfg-q7yz), so the synthetic variant is "static".
    assertEquals("static", d.getVariant());
    provider.shutdown();
  }

  @Test
  void integration_objectEvaluation_stringList() throws Exception {
    QuonfigProvider provider = newDatadirProvider();
    ProviderEvaluation<Value> d =
        provider.getObjectEvaluation(
            "my-string-list-key", new Value("default"), null);
    assertNotNull(d.getValue());
    assertTrue(d.getValue().isList());
    provider.shutdown();
  }
}
