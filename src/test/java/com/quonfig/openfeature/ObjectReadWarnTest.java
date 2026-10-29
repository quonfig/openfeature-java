package com.quonfig.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/**
 * An OpenFeature object read must not log sdk-java's once-per-key type-mismatch WARN for a config
 * the provider then returns (qfg-tzby). Each test gets a fresh client, since the SDK logs that WARN
 * at most once per key per client.
 *
 * <p>The capturing logger is a dynamic proxy of {@link Logger} that records every {@code warn} call.
 */
class ObjectReadWarnTest {

  private static final String DOMAIN = "object-read-warn";
  private final List<String> warns = new CopyOnWriteArrayList<>();
  private Client client;

  private static String integrationTestDataDir() {
    Path base = Paths.get(System.getProperty("user.dir"));
    return base.resolve("../integration-test-data/data/integration-tests")
        .normalize()
        .toAbsolutePath()
        .toString();
  }

  private void start() throws Exception {
    QuonfigProvider provider =
        new QuonfigProvider(
            QuonfigProviderOptions.builder()
                .datadir(integrationTestDataDir())
                .environment("Production")
                .build(),
            b -> {
              b.disableTelemetry(true);
              b.logger(capturingLogger());
            });
    OpenFeatureAPI.getInstance().setProviderAndWait(DOMAIN, provider);
    client = OpenFeatureAPI.getInstance().getClient(DOMAIN);
  }

  @AfterEach
  void tearDown() {
    OpenFeatureAPI.getInstance().getProvider(DOMAIN).shutdown();
  }

  private boolean warnedAbout(String key) {
    return warns.stream().anyMatch(w -> w.contains("\"" + key + "\""));
  }

  @Test
  void jsonConfig_resolvesWithoutTypeMismatchWarn() throws Exception {
    start();
    FlagEvaluationDetails<Value> d = client.getObjectDetails("getter.contract.json", new Value());
    assertNull(d.getErrorCode());
    assertEquals(Reason.STATIC.name(), d.getReason());
    assertEquals(1, d.getValue().asStructure().getValue("a").asInteger());
    assertTrue(!warnedAbout("getter.contract.json"), "unexpected WARN: " + warns);
  }

  @Test
  void stringListConfig_resolvesWithoutWarn() throws Exception {
    start();
    FlagEvaluationDetails<Value> d =
        client.getObjectDetails("getter.contract.string_list", new Value());
    assertNull(d.getErrorCode());
    assertEquals(new Value(List.of(new Value("a"), new Value("b"))), d.getValue());
    assertTrue(!warnedAbout("getter.contract.string_list"), "unexpected WARN: " + warns);
  }

  @Test
  void wrongTypeConfig_stillTypeMismatchAndWarns() throws Exception {
    start();
    Value def = new Value("d");
    FlagEvaluationDetails<Value> d = client.getObjectDetails("getter.contract.bool", def);
    assertEquals(def, d.getValue());
    assertEquals(Reason.ERROR.name(), d.getReason());
    assertEquals(ErrorCode.TYPE_MISMATCH, d.getErrorCode());
    assertTrue(warnedAbout("getter.contract.bool"), "expected the caller-problem WARN: " + warns);
  }

  // --- capturing slf4j logger (dynamic proxy) ---

  private Logger capturingLogger() {
    return (Logger) proxy(Logger.class);
  }

  private Object proxy(Class<?> type) {
    return Proxy.newProxyInstance(
        type.getClassLoader(),
        new Class<?>[] {type},
        (self, method, args) -> {
          if (method.getName().equals("warn") && args != null && args.length > 0) {
            StringBuilder sb = new StringBuilder(String.valueOf(args[0]));
            for (int i = 1; i < args.length; i++) {
              sb.append(" | ").append(args[i]);
            }
            warns.add(sb.toString());
          }
          Class<?> rt = method.getReturnType();
          if (rt == boolean.class) {
            return true;
          }
          if (rt == String.class) {
            return "capture";
          }
          if (rt.isInterface()) {
            return proxy(rt);
          }
          return null;
        });
  }
}
