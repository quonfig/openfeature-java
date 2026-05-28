package com.quonfig.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.eval.ContextSet;
import dev.openfeature.sdk.MutableContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ContextMapper}, mirroring openfeature-go's MapContext tests. */
class ContextMapperTest {

  @Test
  void dotNotation_splitsIntoNamespaceAndKey() {
    MutableContext ctx = new MutableContext().add("user.email", "alice@co.com");
    ContextSet qCtx = ContextMapper.mapContext(ctx, "user.id");
    assertNotNull(qCtx);
    Map<String, Map<String, Object>> data = qCtx.data();
    assertEquals("alice@co.com", data.get("user").get("email"));
  }

  @Test
  void noDot_goesToEmptyNamespace() {
    MutableContext ctx = new MutableContext().add("country", "US");
    ContextSet qCtx = ContextMapper.mapContext(ctx, "user.id");
    assertNotNull(qCtx);
    assertEquals("US", qCtx.data().get("").get("country"));
  }

  @Test
  void targetingKey_mapsViaDefaultMapping() {
    MutableContext ctx = new MutableContext().setTargetingKey("user-123");
    ContextSet qCtx = ContextMapper.mapContext(ctx, "user.id");
    assertNotNull(qCtx);
    assertEquals("user-123", qCtx.data().get("user").get("id"));
  }

  @Test
  void targetingKey_customMapping() {
    MutableContext ctx = new MutableContext().setTargetingKey("org-456");
    ContextSet qCtx = ContextMapper.mapContext(ctx, "org.key");
    assertNotNull(qCtx);
    assertEquals("org-456", qCtx.data().get("org").get("key"));
  }

  @Test
  void targetingKey_mappingWithoutDot_goesToEmptyNamespace() {
    MutableContext ctx = new MutableContext().setTargetingKey("abc");
    ContextSet qCtx = ContextMapper.mapContext(ctx, "userid");
    assertNotNull(qCtx);
    assertEquals("abc", qCtx.data().get("").get("userid"));
  }

  @Test
  void multiDot_splitsOnFirstDotOnly() {
    MutableContext ctx = new MutableContext().add("user.ip.address", "1.2.3.4");
    ContextSet qCtx = ContextMapper.mapContext(ctx, "user.id");
    assertNotNull(qCtx);
    assertEquals("1.2.3.4", qCtx.data().get("user").get("ip.address"));
  }

  @Test
  void emptyContext_returnsNull() {
    ContextSet qCtx = ContextMapper.mapContext(new MutableContext(), "user.id");
    assertNull(qCtx);
  }

  @Test
  void nullContext_returnsNull() {
    assertNull(ContextMapper.mapContext(null, "user.id"));
  }

  @Test
  void multipleNamespaces_allPresent() {
    MutableContext ctx =
        new MutableContext().add("user.email", "a@co.com").add("org.tier", "enterprise");
    ContextSet qCtx = ContextMapper.mapContext(ctx, "user.id");
    assertNotNull(qCtx);
    assertEquals("a@co.com", qCtx.data().get("user").get("email"));
    assertEquals("enterprise", qCtx.data().get("org").get("tier"));
  }

  @Test
  void splitFirst_noDot() {
    String[] parts = ContextMapper.splitFirst("country");
    assertEquals("", parts[0]);
    assertEquals("country", parts[1]);
  }

  @Test
  void splitFirst_withDot() {
    String[] parts = ContextMapper.splitFirst("user.email");
    assertEquals("user", parts[0]);
    assertEquals("email", parts[1]);
  }

  @Test
  void targetingKeyEntry_inMapIsNotDoublyMapped() {
    // A targetingKey set via setTargetingKey should map to user.id, and only there.
    MutableContext ctx = new MutableContext().setTargetingKey("xyz").add("plan", "pro");
    ContextSet qCtx = ContextMapper.mapContext(ctx, "user.id");
    assertNotNull(qCtx);
    assertEquals("xyz", qCtx.data().get("user").get("id"));
    assertEquals("pro", qCtx.data().get("").get("plan"));
    assertTrue(qCtx.data().get("").get("targetingKey") == null);
  }
}
