package org.example.ml;

/**
 * Chooses between the real embedded model and the placeholder based on {@link MlModelConfig},
 * failing safe to the placeholder if the ONNX model can't be loaded rather than blocking startup.
 * Enabling the real model later is a config change ({@code EXCEPTION_ML_ENABLED=true} plus a
 * valid model path), not a code change.
 */
public final class LocalMlModelClientFactory {

    private LocalMlModelClientFactory() {
    }

    public static LocalMlModelClient create(MlModelConfig config) {
        if (!config.enabled()) {
            return new PlaceholderLocalMlModelClient();
        }
        try {
            return new OnnxLocalMlModelClient(config);
        } catch (Exception e) {
            System.err.println("[LocalMlModelClientFactory]: failed to load ONNX model at '"
                    + config.modelPath() + "', falling back to placeholder - " + e.getMessage());
            return new PlaceholderLocalMlModelClient();
        }
    }
}
