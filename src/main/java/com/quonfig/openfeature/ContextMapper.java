package com.quonfig.openfeature;

import com.quonfig.sdk.eval.ContextSet;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.Value;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts an OpenFeature flat {@link EvaluationContext} into a Quonfig {@link ContextSet}.
 *
 * <p>OpenFeature uses a flat key-value evaluation context; Quonfig uses a nested namespace model.
 * The mapping uses dot-notation, splitting on the first dot only:
 *
 * <ul>
 *   <li>{@code "user.email"} -&gt; namespace {@code "user"}, key {@code "email"}
 *   <li>{@code "country"} (no dot) -&gt; namespace {@code ""}, key {@code "country"}
 *   <li>{@code "user.ip.address"} -&gt; namespace {@code "user"}, key {@code "ip.address"} (split on
 *       first dot only)
 *   <li>{@code targetingKey} -&gt; resolved via {@code targetingKeyMapping} (default {@code
 *       "user.id"})
 * </ul>
 *
 * <p>This mirrors {@code openfeature-go}'s {@code context.go} {@code MapContext} behavior exactly.
 */
final class ContextMapper {

  private ContextMapper() {}

  /**
   * Builds a Quonfig {@link ContextSet} from an OpenFeature {@link EvaluationContext}.
   *
   * @param ctx the OpenFeature evaluation context (may be null)
   * @param targetingKeyMapping how the OpenFeature targetingKey maps to a Quonfig property
   *     (dot-notation, e.g. {@code "user.id"})
   * @return a populated {@link ContextSet}, or {@code null} when the context is null/empty or holds
   *     only null values
   */
  static ContextSet mapContext(EvaluationContext ctx, String targetingKeyMapping) {
    if (ctx == null) {
      return null;
    }

    Map<String, Value> flat = ctx.asMap();
    String targetingKey = ctx.getTargetingKey();
    if ((flat == null || flat.isEmpty()) && (targetingKey == null || targetingKey.isEmpty())) {
      return null;
    }

    Map<String, Map<String, Object>> namespaces = new LinkedHashMap<>();

    // targetingKey is a top-level field on EvaluationContext, not part of asMap(); map it
    // explicitly via targetingKeyMapping (mirrors the Go provider, which sees it as a flat
    // "targetingKey" entry).
    if (targetingKey != null && !targetingKey.isEmpty()) {
      String[] parts = splitFirst(targetingKeyMapping);
      addToNamespace(namespaces, parts[0], parts[1], targetingKey);
    }

    if (flat != null) {
      for (Map.Entry<String, Value> e : flat.entrySet()) {
        String key = e.getKey();
        Value value = e.getValue();
        if (value == null || value.isNull()) {
          continue;
        }
        // Skip the targetingKey field if it surfaces in asMap() — already handled above.
        if (EvaluationContext.TARGETING_KEY.equals(key)) {
          continue;
        }
        Object raw = value.asObject();
        if (raw == null) {
          continue;
        }
        String[] parts = splitFirst(key);
        addToNamespace(namespaces, parts[0], parts[1], raw);
      }
    }

    if (namespaces.isEmpty()) {
      return null;
    }

    ContextSet ctxSet = new ContextSet();
    for (Map.Entry<String, Map<String, Object>> e : namespaces.entrySet()) {
      ctxSet.withNamedContext(e.getKey(), e.getValue());
    }
    return ctxSet;
  }

  /**
   * Splits {@code s} on the first {@code '.'}. If there is no dot, returns {@code ["", s]} so the
   * whole key ends up in the empty-string namespace.
   */
  static String[] splitFirst(String s) {
    int idx = s.indexOf('.');
    if (idx < 0) {
      return new String[] {"", s};
    }
    return new String[] {s.substring(0, idx), s.substring(idx + 1)};
  }

  private static void addToNamespace(
      Map<String, Map<String, Object>> namespaces, String ns, String prop, Object value) {
    namespaces.computeIfAbsent(ns, k -> new LinkedHashMap<>()).put(prop, value);
  }
}
