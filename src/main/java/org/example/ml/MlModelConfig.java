package org.example.ml;

import java.util.Arrays;
import java.util.List;

/**
 * Toggle and tuning knobs for the embedded exception classifier, read from the environment so
 * enabling the real ONNX model is a config change, not a code change. See
 * {@link LocalMlModelClientFactory}.
 */
public record MlModelConfig(
        boolean enabled,
        String modelPath,
        List<String> featureOrder,
        double bypassThreshold,
        String modelVersion) {

    private static final String DEFAULT_MODEL_PATH = "models/exception-bypass-classifier.onnx";
    private static final String DEFAULT_FEATURE_ORDER = "statusCode,accountTierCode,reasonLength";

    public static MlModelConfig fromEnvironment() {
        return new MlModelConfig(
                Boolean.parseBoolean(env("EXCEPTION_ML_ENABLED", "false")),
                env("EXCEPTION_ML_MODEL_PATH", DEFAULT_MODEL_PATH),
                parseFeatureOrder(env("EXCEPTION_ML_FEATURE_ORDER", DEFAULT_FEATURE_ORDER)),
                Double.parseDouble(env("EXCEPTION_ML_BYPASS_THRESHOLD", "0.5")),
                env("EXCEPTION_ML_MODEL_VERSION", "unversioned"));
    }

    private static List<String> parseFeatureOrder(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
