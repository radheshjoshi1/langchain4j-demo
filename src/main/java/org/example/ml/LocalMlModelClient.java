package org.example.ml;

import org.example.exceptions.ExceptionContext;

import java.io.Serializable;
import java.util.Map;

/**
 * Runs entirely in this JVM - no network call, no separately hosted model - so a step in the
 * exception graph can classify a transaction in-process. Two implementations back this
 * interface: {@link OnnxLocalMlModelClient}, backed by a real ONNX model file, and
 * {@link PlaceholderLocalMlModelClient}, a safe always-manual-review default used before a
 * trained model exists or when the ONNX one fails to load.
 */
public interface LocalMlModelClient {

    MlPrediction predict(ExceptionContext context);

    String modelVersion();

    record MlPrediction(String label, boolean bypassEligible, double score, Map<String, Object> raw)
            implements Serializable {
    }
}
