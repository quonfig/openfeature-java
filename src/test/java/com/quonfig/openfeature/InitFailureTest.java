package com.quonfig.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.OnInitFailure;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderState;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Provider status mapping for sdk-java 2.0's init-failure modes (qfg-9p4i.12; design doc
 * project/plans/2026-10-05-init-failure-design.md section B.5). A local HTTP server stands in for
 * delivery: it answers 503 until the test flips it to serve a config, which is how a test drives
 * an outage and then the heal.
 */
class InitFailureTest {

  private static final String ENVELOPE =
      "{\"configs\":[{\"id\":\"c1\",\"key\":\"my.flag\",\"type\":\"feature_flag\","
          + "\"valueType\":\"bool\",\"default\":{\"rules\":[{\"criteria\":[],"
          + "\"value\":{\"type\":\"bool\",\"value\":true}}]}}],"
          + "\"meta\":{\"version\":\"v3\",\"environment\":\"production\",\"workspaceId\":\"ws\","
          + "\"generation\":3}}";

  private final List<HttpServer> servers = new ArrayList<>();
  private final List<QuonfigProvider> providers = new ArrayList<>();

  @AfterEach
  void cleanup() {
    for (QuonfigProvider p : providers) p.shutdown();
    for (HttpServer s : servers) s.stop(0);
  }

  /** Config endpoint answering {@code status} (0 = the envelope). SSE always 503. */
  private HttpServer server(AtomicInteger status) throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.setExecutor(Executors.newCachedThreadPool());
    s.createContext(
        "/api/v2/configs",
        (HttpExchange ex) -> {
          int st = status.get();
          if (st != 0) {
            ex.sendResponseHeaders(st, -1);
            ex.close();
            return;
          }
          byte[] b = ENVELOPE.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, b.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
          }
        });
    s.createContext(
        "/api/v2/sse/config",
        (HttpExchange ex) -> {
          ex.sendResponseHeaders(503, -1);
          ex.close();
        });
    s.start();
    servers.add(s);
    return s;
  }

  private QuonfigProvider provider(HttpServer s, OnInitFailure mode, Duration initTimeout) {
    String base = "http://127.0.0.1:" + s.getAddress().getPort();
    QuonfigProviderOptions.Builder o =
        QuonfigProviderOptions.builder().sdkKey("test-sdk").initTimeout(initTimeout);
    if (mode != null) {
      o.onInitFailure(mode);
    }
    QuonfigProvider p =
        new QuonfigProvider(
            o.build(),
            b ->
                b.apiUrls(List.of(base, base))
                    .streamUrls(List.of(base))
                    .telemetryUrl(base)
                    .fallbackPollEnabled(false)
                    .disableTelemetry(true));
    providers.add(p);
    return p;
  }

  private static String domain() {
    return "qfg-init-" + UUID.randomUUID();
  }

  private static void eventually(BooleanSupplier c, String what) throws InterruptedException {
    long deadline = System.nanoTime() + 15_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (c.getAsBoolean()) return;
      Thread.sleep(20);
    }
    throw new AssertionError("timed out waiting for: " + what);
  }

  @Test
  void fail_default_initializeThrows_statusError_clientKeptAlive_healEmitsReady()
      throws Exception {
    AtomicInteger status = new AtomicInteger(503);
    // No onInitFailure set: the SDK default (FAIL) applies.
    QuonfigProvider p = provider(server(status), null, Duration.ofMillis(300));
    String domain = domain();
    Client client = OpenFeatureAPI.getInstance().getClient(domain);
    AtomicInteger readyEvents = new AtomicInteger();
    client.onProviderReady(e -> readyEvents.incrementAndGet());

    long start = System.nanoTime();
    assertThrows(
        Exception.class, () -> OpenFeatureAPI.getInstance().setProviderAndWait(domain, p));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    // initTimeout is passed through: a 300ms budget, not the SDK's 10s default nor a fixed 15s.
    assertTrue(elapsedMs < 5000, "initialize took " + elapsedMs + "ms");

    assertEquals(ProviderState.ERROR, client.getProviderState());
    // The failed construction's client survives and keeps retrying (no orphan).
    assertNotNull(p.getClient(), "client must be kept after a caught FAIL");
    ProviderEvaluation<Boolean> d = p.getBooleanEvaluation("my.flag", false, null);
    assertFalse(d.getValue());
    assertEquals(ErrorCode.PROVIDER_NOT_READY, d.getErrorCode());

    status.set(0);
    eventually(() -> client.getProviderState() == ProviderState.READY, "status READY after heal");
    eventually(() -> readyEvents.get() >= 1, "PROVIDER_READY after heal");
    assertTrue(client.getBooleanValue("my.flag", false));
  }

  @Test
  void continue_noConfig_initializeResolves_statusError_healEmitsReady() throws Exception {
    AtomicInteger status = new AtomicInteger(503);
    QuonfigProvider p = provider(server(status), OnInitFailure.CONTINUE, Duration.ofMillis(300));
    String domain = domain();
    Client client = OpenFeatureAPI.getInstance().getClient(domain);

    OpenFeatureAPI.getInstance().setProviderAndWait(domain, p);

    // Every evaluation is a code default, so READY would be a lie: ERROR (non-fatal).
    eventually(() -> client.getProviderState() == ProviderState.ERROR, "status ERROR");
    ProviderEvaluation<Boolean> d = p.getBooleanEvaluation("my.flag", false, null);
    assertFalse(d.getValue());
    assertEquals(ErrorCode.PROVIDER_NOT_READY, d.getErrorCode());

    status.set(0);
    eventually(() -> client.getProviderState() == ProviderState.READY, "status READY after heal");
    assertTrue(client.getBooleanValue("my.flag", false));
  }

  @Test
  void wait_initializeBlocks_statusNotReady_thenReady() throws Exception {
    AtomicInteger status = new AtomicInteger(503);
    QuonfigProvider p = provider(server(status), OnInitFailure.WAIT, Duration.ofMillis(200));
    String domain = domain();
    Client client = OpenFeatureAPI.getInstance().getClient(domain);

    OpenFeatureAPI.getInstance().setProvider(domain, p); // async: initialize runs in background
    Thread.sleep(800); // well past initTimeout: WAIT keeps blocking
    assertEquals(ProviderState.NOT_READY, client.getProviderState());

    status.set(0);
    eventually(() -> client.getProviderState() == ProviderState.READY, "status READY");
    assertTrue(client.getBooleanValue("my.flag", false));
  }

  @Test
  void healthyBoot_isReady() throws Exception {
    AtomicInteger status = new AtomicInteger(0);
    QuonfigProvider p = provider(server(status), null, Duration.ofSeconds(5));
    String domain = domain();
    Client client = OpenFeatureAPI.getInstance().getClient(domain);

    OpenFeatureAPI.getInstance().setProviderAndWait(domain, p);

    assertEquals(ProviderState.READY, client.getProviderState());
    assertTrue(client.getBooleanValue("my.flag", false));
  }
}
