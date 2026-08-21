package org.example.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.example.exceptions.ExceptionContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Runs a trained classifier exported to ONNX in-process via ONNX Runtime's Java bindings - a
 * pure-JVM library, no Python process or sidecar, no separately hosted endpoint. The model file
 * loads once in the constructor and {@link OrtSession#run} is safe to call concurrently, so this
 * one instance is reused for every {@link #predict} call.
 *
 * <p>I/O contract the exported model must match: one {@code float32} input tensor of shape
 * {@code [1, N]}, columns in the order given by {@code featureOrder}; the <em>last</em> value of
 * the output tensor is read as the bypass-eligible probability.
 */
public class OnnxLocalMlModelClient implements LocalMlModelClient, AutoCloseable {

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final List<String> featureOrder;
    private final double bypassThreshold;
    private final String modelVersion;

    public OnnxLocalMlModelClient(MlModelConfig config) throws OrtException, IOException {
        this.featureOrder = config.featureOrder();
        this.bypassThreshold = config.bypassThreshold();
        this.modelVersion = config.modelVersion();
        this.environment = OrtEnvironment.getEnvironment();
        byte[] modelBytes = readModelBytes(config.modelPath());
        this.session = environment.createSession(modelBytes, new OrtSession.SessionOptions());
        this.inputName = session.getInputNames().iterator().next();
    }

    private static byte[] readModelBytes(String modelPath) throws IOException {
        if (modelPath.startsWith("file:")) {
            return Files.readAllBytes(Path.of(modelPath.substring("file:".length())));
        }
        String classpathPath = modelPath.startsWith("classpath:")
                ? modelPath.substring("classpath:".length())
                : modelPath;
        try (InputStream in = OnnxLocalMlModelClient.class.getClassLoader().getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IOException("ONNX model not found on classpath: " + classpathPath);
            }
            return in.readAllBytes();
        }
    }

    @Override
    public MlPrediction predict(ExceptionContext context) {
        float[] features = toFeatureVector(context);
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(environment, new float[][]{features});
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, inputTensor))) {
            Object rawOutput = result.iterator().next().getValue().getValue();
            double score = extractPositiveClassScore(rawOutput);
            boolean bypassEligible = score >= bypassThreshold;
            String label = bypassEligible ? "AUTO_RESOLVE" : "MANUAL_REVIEW";
            return new MlPrediction(label, bypassEligible, score,
                    Map.of("score", score, "modelVersion", modelVersion, "engine", "onnxruntime-local"));
        } catch (OrtException e) {
            throw new IllegalStateException("Local ONNX model inference failed", e);
        }
    }

    @Override
    public String modelVersion() {
        return modelVersion;
    }

    private float[] toFeatureVector(ExceptionContext context) {
        Map<String, Object> features = context.features();
        float[] vector = new float[featureOrder.size()];
        for (int i = 0; i < featureOrder.size(); i++) {
            Object value = features.get(featureOrder.get(i));
            vector[i] = (value instanceof Number number) ? number.floatValue() : 0f;
        }
        return vector;
    }

    private double extractPositiveClassScore(Object rawOutput) {
        if (rawOutput instanceof float[][] matrix && matrix.length > 0 && matrix[0].length > 0) {
            return matrix[0][matrix[0].length - 1];
        }
        if (rawOutput instanceof float[] vector && vector.length > 0) {
            return vector[vector.length - 1];
        }
        throw new IllegalStateException("Unsupported ONNX output shape: " + rawOutput);
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            System.err.println("[OnnxLocalMlModelClient]: failed to close session - " + e.getMessage());
        }
    }
}
