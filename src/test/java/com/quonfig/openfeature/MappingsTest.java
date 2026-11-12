package com.quonfig.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.openfeature.sdk.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * Error-code mapping from sdk-java to OpenFeature. sdk-java 2.4.0+ reports an unparseable value
 * (malformed stored int/double/duration, or an ENV_VAR value that does not parse) as {@code
 * PARSE_ERROR} (qfg-wg1n). The provider's floor is above that release, so every SDK constant maps
 * directly, by constant, to its OpenFeature equivalent (qfg-ln7d).
 */
class MappingsTest {

  @Test
  void parseError_passesStraightThrough() {
    assertEquals(
        ErrorCode.PARSE_ERROR, Mappings.errorCodeFor(com.quonfig.sdk.ErrorCode.PARSE_ERROR));
  }

  @Test
  void everySdkCode_mapsOneToOne() {
    assertEquals(
        ErrorCode.FLAG_NOT_FOUND, Mappings.errorCodeFor(com.quonfig.sdk.ErrorCode.FLAG_NOT_FOUND));
    assertEquals(
        ErrorCode.TYPE_MISMATCH, Mappings.errorCodeFor(com.quonfig.sdk.ErrorCode.TYPE_MISMATCH));
    assertEquals(
        ErrorCode.PROVIDER_NOT_READY,
        Mappings.errorCodeFor(com.quonfig.sdk.ErrorCode.PROVIDER_NOT_READY));
    assertEquals(ErrorCode.GENERAL, Mappings.errorCodeFor(com.quonfig.sdk.ErrorCode.GENERAL));
  }

  @Test
  void noError_isNull() {
    assertNull(Mappings.errorCodeFor((com.quonfig.sdk.ErrorCode) null));
  }
}
