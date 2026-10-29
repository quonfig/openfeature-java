package com.quonfig.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.openfeature.sdk.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * Error-code mapping from sdk-java to OpenFeature. A newer sdk-java reports an unparseable value
 * (malformed stored int/double/duration, or an ENV_VAR value that does not parse) as {@code
 * PARSE_ERROR} (qfg-wg1n). The released sdk-java this provider compiles against has no such enum
 * constant, so the SDK result is faked here by its constant name.
 */
class MappingsTest {

  @Test
  void parseError_passesStraightThrough() {
    assertEquals(ErrorCode.PARSE_ERROR, Mappings.errorCodeForName("PARSE_ERROR"));
  }

  @Test
  void knownCodes_mapOneToOne() {
    assertEquals(ErrorCode.FLAG_NOT_FOUND, Mappings.errorCodeForName("FLAG_NOT_FOUND"));
    assertEquals(ErrorCode.TYPE_MISMATCH, Mappings.errorCodeForName("TYPE_MISMATCH"));
    assertEquals(ErrorCode.PROVIDER_NOT_READY, Mappings.errorCodeForName("PROVIDER_NOT_READY"));
    assertEquals(ErrorCode.GENERAL, Mappings.errorCodeForName("GENERAL"));
  }

  @Test
  void unknownCode_isGeneral() {
    assertEquals(ErrorCode.GENERAL, Mappings.errorCodeForName("SOMETHING_NEW"));
  }
}
