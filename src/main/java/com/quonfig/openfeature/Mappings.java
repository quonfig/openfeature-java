package com.quonfig.openfeature;

import com.quonfig.sdk.EvaluationDetails;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.Reason;
import java.util.Map;

/**
 * Maps Quonfig {@link EvaluationDetails} fields onto their OpenFeature equivalents (reason string,
 * error code, flag metadata). Mirrors {@code openfeature-go}'s {@code errors.go} /
 * {@code provider.go} mapping exactly.
 */
final class Mappings {

  private Mappings() {}

  /**
   * Maps a Quonfig {@link EvaluationDetails} to the OpenFeature reason string.
   *
   * <p>On error, {@code FLAG_NOT_FOUND} surfaces as {@link Reason#DEFAULT} (the spec lets providers
   * pick DEFAULT or ERROR for missing flags; we pick DEFAULT to match the Go provider). Other error
   * codes surface as {@link Reason#ERROR}. On success the reason is mapped 1:1 from the SDK's {@link
   * com.quonfig.sdk.Reason}.
   */
  static String reasonFor(EvaluationDetails<?> details) {
    com.quonfig.sdk.ErrorCode code = details.errorCode();
    if (code == com.quonfig.sdk.ErrorCode.FLAG_NOT_FOUND) {
      return Reason.DEFAULT.name();
    }
    if (code != null) {
      return Reason.ERROR.name();
    }
    return evalReasonToOf(details.reason()).name();
  }

  /** Maps a Quonfig {@link com.quonfig.sdk.Reason} to an OpenFeature {@link Reason}. */
  static Reason evalReasonToOf(com.quonfig.sdk.Reason r) {
    switch (r) {
      case STATIC:
        return Reason.STATIC;
      case TARGETING_MATCH:
        return Reason.TARGETING_MATCH;
      case SPLIT:
        return Reason.SPLIT;
      case DEFAULT:
        return Reason.DEFAULT;
      default:
        return Reason.UNKNOWN;
    }
  }

  /**
   * Maps a Quonfig {@link com.quonfig.sdk.ErrorCode} to an OpenFeature {@link ErrorCode}. Returns
   * {@code null} when there is no error.
   */
  static ErrorCode errorCodeFor(EvaluationDetails<?> details) {
    com.quonfig.sdk.ErrorCode code = details.errorCode();
    if (code == null) {
      return null;
    }
    switch (code) {
      case FLAG_NOT_FOUND:
        return ErrorCode.FLAG_NOT_FOUND;
      case TYPE_MISMATCH:
        return ErrorCode.TYPE_MISMATCH;
      case GENERAL:
      default:
        return ErrorCode.GENERAL;
    }
  }

  /** Copies Quonfig flag metadata into an OpenFeature {@link ImmutableMetadata}. */
  static ImmutableMetadata flagMetadataFor(EvaluationDetails<?> details) {
    ImmutableMetadata.ImmutableMetadataBuilder b = ImmutableMetadata.builder();
    Map<String, Object> md = details.metadata();
    if (md != null) {
      for (Map.Entry<String, Object> e : md.entrySet()) {
        Object v = e.getValue();
        if (v instanceof String) {
          b.addString(e.getKey(), (String) v);
        } else if (v instanceof Integer) {
          b.addInteger(e.getKey(), (Integer) v);
        } else if (v instanceof Long) {
          b.addLong(e.getKey(), (Long) v);
        } else if (v instanceof Double) {
          b.addDouble(e.getKey(), (Double) v);
        } else if (v instanceof Float) {
          b.addFloat(e.getKey(), (Float) v);
        } else if (v instanceof Boolean) {
          b.addBoolean(e.getKey(), (Boolean) v);
        } else if (v != null) {
          b.addString(e.getKey(), v.toString());
        }
      }
    }
    return b.build();
  }
}
