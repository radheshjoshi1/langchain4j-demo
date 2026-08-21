package org.example.ml;

import org.example.exceptions.ExceptionContext;

import java.util.Map;

/**
 * Safe default used before a trained model file exists, or when the real client fails to load.
 * Always routes to manual review - never auto-bypasses without a real prediction behind it.
 */
public class PlaceholderLocalMlModelClient implements LocalMlModelClient {

    @Override
    public MlPrediction predict(ExceptionContext context) {
        return new MlPrediction("MANUAL_REVIEW", false, 0.0, Map.of("engine", "placeholder"));
    }

    @Override
    public String modelVersion() {
        return "placeholder";
    }
}
