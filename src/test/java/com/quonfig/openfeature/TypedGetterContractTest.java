package com.quonfig.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Typed-getter contract (qfg-zvyy.7), driven through the real provider and the OpenFeature client
 * against the integration-test-data {@code getter.contract.*} fixtures. No mocks: this pins what
 * provider users inherit from {@code com.quonfig:sdk-java} 2.2.0.
 *
 * <p>The rule: a flag read with the method for another type returns the caller's default with
 * reason ERROR and error code TYPE_MISMATCH. Strings are never coerced (a stored "false" is not a
 * bool, "3" is not a number, an int is not a string). Numbers convert only when exact: an int config
 * reads as a double, and a double config reads as an integer only when it is whole and within
 * +/-(2^53-1). A malformed stored or ENV_VAR number also returns the default with TYPE_MISMATCH.
 */
class TypedGetterContractTest {

  private static final String DOMAIN = "typed-getter-contract";

  // ENV_VAR values the getter.contract.provided.* fixtures look up. Injected through the SDK's
  // envLookup seam so the test does not depend on (or mutate) the JVM environment.
  private static final Map<String, String> ENV =
      Map.of(
          "QUONFIG_ITD_GETTER_BOOL", "true",
          "QUONFIG_ITD_GETTER_INT", "twelve",
          "QUONFIG_ITD_GETTER_DOUBLE", "abc");

  private static Client client;

  private static String integrationTestDataDir() {
    Path base = Paths.get(System.getProperty("user.dir"));
    return base.resolve("../integration-test-data/data/integration-tests")
        .normalize()
        .toAbsolutePath()
        .toString();
  }

  private static QuonfigProvider newProvider(String datadir) {
    return new QuonfigProvider(
        QuonfigProviderOptions.builder().datadir(datadir).environment("Production").build(),
        b -> b.disableTelemetry(true).envLookup(k -> Optional.ofNullable(ENV.get(k))));
  }

  @BeforeAll
  static void setUp() throws Exception {
    OpenFeatureAPI.getInstance().setProviderAndWait(DOMAIN, newProvider(integrationTestDataDir()));
    client = OpenFeatureAPI.getInstance().getClient(DOMAIN);
  }

  @AfterAll
  static void tearDown() {
    OpenFeatureAPI.getInstance().getProvider(DOMAIN).shutdown();
  }

  private static <T> void assertError(FlagEvaluationDetails<T> d, T def, ErrorCode code) {
    assertEquals(def, d.getValue(), "value");
    assertEquals(Reason.ERROR.name(), d.getReason(), "reason");
    assertEquals(code, d.getErrorCode(), "errorCode");
  }

  private static <T> void assertTypeMismatch(FlagEvaluationDetails<T> d, T def) {
    assertError(d, def, ErrorCode.TYPE_MISMATCH);
  }

  private static <T> void assertResolved(FlagEvaluationDetails<T> d, T expected) {
    assertEquals(expected, d.getValue(), "value");
    assertEquals(Reason.STATIC.name(), d.getReason(), "reason");
    assertNull(d.getErrorCode(), "errorCode");
  }

  private static Value objectDefault() {
    return new Value(new MutableStructure().add("d", true));
  }

  // --- Wrong type -> default + TYPE_MISMATCH ---

  @Test
  void boolean_onStoredStringFalse_returnsDefault() {
    assertTypeMismatch(client.getBooleanDetails("getter.contract.string.false", true), true);
  }

  @Test
  void boolean_onStoredStringTrue_returnsDefault() {
    assertTypeMismatch(client.getBooleanDetails("getter.contract.string.true", false), false);
  }

  @Test
  void integer_onStoredStringNumber_returnsDefault() {
    assertTypeMismatch(client.getIntegerDetails("getter.contract.string.number", 7), 7);
  }

  @Test
  void double_onStoredStringNumber_returnsDefault() {
    assertTypeMismatch(client.getDoubleDetails("getter.contract.string.number", 7.5), 7.5);
  }

  @Test
  void string_onStoredInt_returnsDefaultNotItsText() {
    assertTypeMismatch(client.getStringDetails("getter.contract.int", "fallback"), "fallback");
  }

  @Test
  void string_onStoredBool_returnsDefault() {
    assertTypeMismatch(client.getStringDetails("getter.contract.bool", "fallback"), "fallback");
  }

  @Test
  void boolean_onStoredInt_returnsDefault() {
    assertTypeMismatch(client.getBooleanDetails("getter.contract.int", true), true);
  }

  @Test
  void integer_onFractionalDouble_returnsDefaultNotTruncated() {
    assertTypeMismatch(client.getIntegerDetails("getter.contract.double", 7), 7);
  }

  @Test
  void integer_onUnsafeDouble_returnsDefault() {
    assertTypeMismatch(client.getIntegerDetails("getter.contract.double.unsafe", 7), 7);
  }

  @Test
  void integer_onHugeDouble_returnsDefault() {
    assertTypeMismatch(client.getIntegerDetails("getter.contract.double.huge", 7), 7);
  }

  @Test
  void string_onDuration_returnsDefault() {
    // Durations have no OpenFeature read path: use getClient().getDuration(). sdk-java 2.1 returned
    // the ISO-8601 text ("PT30S") here.
    assertTypeMismatch(client.getStringDetails("getter.contract.duration", "PT5S"), "PT5S");
  }

  @Test
  void double_onDuration_returnsDefault() {
    assertTypeMismatch(client.getDoubleDetails("getter.contract.duration", 5.0), 5.0);
  }

  @Test
  void integer_onDuration_returnsDefault() {
    assertTypeMismatch(client.getIntegerDetails("getter.contract.duration", 5), 5);
  }

  @Test
  void object_onStoredString_returnsDefault() {
    assertTypeMismatch(
        client.getObjectDetails("getter.contract.string", objectDefault()), objectDefault());
  }

  @Test
  void object_onStoredBool_returnsDefault() {
    assertTypeMismatch(
        client.getObjectDetails("getter.contract.bool", objectDefault()), objectDefault());
  }

  @Test
  void object_onDuration_returnsDefault() {
    assertTypeMismatch(
        client.getObjectDetails("getter.contract.duration", objectDefault()), objectDefault());
  }

  // --- Matching and safely convertible types resolve ---

  @Test
  void boolean_onBoolConfig() {
    assertResolved(client.getBooleanDetails("getter.contract.bool", false), true);
  }

  @Test
  void string_onStringConfig() {
    assertResolved(client.getStringDetails("getter.contract.string", ""), "fast");
  }

  @Test
  void string_onStringHoldingDurationText_isJustAString() {
    assertResolved(client.getStringDetails("getter.contract.string.duration", ""), "PT1S");
  }

  @Test
  void integer_onIntConfig() {
    assertResolved(client.getIntegerDetails("getter.contract.int", 0), 30);
  }

  @Test
  void double_onIntConfig_converts() {
    assertResolved(client.getDoubleDetails("getter.contract.int", 0.0), 30.0);
  }

  @Test
  void double_onDoubleConfig() {
    assertResolved(client.getDoubleDetails("getter.contract.double", 0.0), 3.9);
  }

  @Test
  void integer_onWholeDouble_converts() {
    assertResolved(client.getIntegerDetails("getter.contract.double.whole", 0), 3);
  }

  @Test
  void object_onJsonConfig() {
    FlagEvaluationDetails<Value> d = client.getObjectDetails("getter.contract.json", new Value());
    assertNull(d.getErrorCode());
    assertEquals(Reason.STATIC.name(), d.getReason());
    assertEquals(1, d.getValue().asStructure().getValue("a").asInteger());
  }

  @Test
  void object_onStringListConfig() {
    assertResolved(
        client.getObjectDetails("getter.contract.string_list", new Value()),
        new Value(List.of(new Value("a"), new Value("b"))));
  }

  @Test
  void boolean_onEnvVarProvidedBool() {
    assertResolved(client.getBooleanDetails("getter.contract.provided.bool", false), true);
  }

  // --- Malformed values and resolution errors return the default ---

  @Test
  void double_onMalformedStoredDouble_returnsDefaultWithTypeMismatch() {
    assertTypeMismatch(client.getDoubleDetails("getter.contract.double.malformed", 1.5), 1.5);
  }

  @Test
  void integer_onMalformedStoredInt_returnsDefaultWithTypeMismatch() {
    assertTypeMismatch(client.getIntegerDetails("getter.contract.int.malformed", 12), 12);
  }

  @Test
  void integer_onMalformedEnvVar_returnsDefaultWithTypeMismatch() {
    assertTypeMismatch(client.getIntegerDetails("getter.contract.provided.int.malformed", 4), 4);
  }

  @Test
  void double_onMalformedEnvVar_returnsDefaultWithTypeMismatch() {
    assertTypeMismatch(
        client.getDoubleDetails("getter.contract.provided.double.malformed", 2.5), 2.5);
  }

  @Test
  void boolean_onUnsetEnvVar_returnsDefaultWithGeneral() {
    assertError(
        client.getBooleanDetails("getter.contract.provided.bool.missing", true),
        true,
        ErrorCode.GENERAL);
  }

  // --- Provider-side narrowing: Quonfig ints are 64-bit, OpenFeature integers are 32-bit ---

  @Test
  void integer_onIntOutsideIntRange_returnsDefaultNotWrapped(@TempDir Path dir) throws Exception {
    // An int config above Integer.MAX_VALUE has no exact OpenFeature Integer. The provider must
    // not wrap it (3000000000 -> -1294967296); it returns the default with TYPE_MISMATCH, the same
    // "only exact conversions" rule the SDK applies to doubles. getDoubleValue still reads it.
    Files.writeString(dir.resolve("quonfig.json"), "{\"environments\": [\"Production\"]}");
    Path configs = Files.createDirectories(dir.resolve("configs"));
    Files.writeString(
        configs.resolve("of.int.big.json"),
        "{\"id\":\"1\",\"projectId\":\"1\",\"key\":\"of.int.big\",\"type\":\"config\","
            + "\"valueType\":\"int\",\"sendToClientSdk\":false,\"default\":{\"rules\":[{"
            + "\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],"
            + "\"value\":{\"type\":\"int\",\"value\":\"3000000000\"}}]}}");
    String domain = DOMAIN + "-big-int";
    OpenFeatureAPI.getInstance().setProviderAndWait(domain, newProvider(dir.toString()));
    try {
      Client c = OpenFeatureAPI.getInstance().getClient(domain);
      assertTypeMismatch(c.getIntegerDetails("of.int.big", 7), 7);
      assertResolved(c.getDoubleDetails("of.int.big", 0.0), 3.0e9);
    } finally {
      OpenFeatureAPI.getInstance().getProvider(domain).shutdown();
    }
  }
}
