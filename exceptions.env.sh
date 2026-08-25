#!/usr/bin/env bash
# Env vars for the embedded exception-triage ML model (see exception_ml_graph_flow.md, section 5).
# Source this in the same shell before running the `exceptions` mode, alongside `sdk env`:
#
#   source exceptions.env.sh
#   mvn exec:java -Dexec.args="exceptions"
#
# Not sourcing this at all is also valid: EXCEPTION_ML_ENABLED defaults to false, which routes
# every transaction through the safe PlaceholderLocalMlModelClient instead.

export EXCEPTION_ML_ENABLED=true
export EXCEPTION_ML_MODEL_VERSION=fixture-v1

# Left at their defaults (see MlModelConfig.java) - uncomment to override:
# export EXCEPTION_ML_MODEL_PATH=models/exception-bypass-classifier.onnx
# export EXCEPTION_ML_FEATURE_ORDER=statusCode,accountTierCode,reasonLength
# export EXCEPTION_ML_BYPASS_THRESHOLD=0.5

echo "[exceptions.env.sh]: EXCEPTION_ML_ENABLED=$EXCEPTION_ML_ENABLED (model=$EXCEPTION_ML_MODEL_VERSION)"
