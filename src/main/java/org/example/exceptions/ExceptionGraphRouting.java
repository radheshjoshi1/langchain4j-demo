package org.example.exceptions;

/**
 * Decides where the graph goes after {@link CallMlModelNode}. A workflow error (inference
 * failure) always wins over whatever the prediction says - fail safe, never auto-resolve on a
 * failed classification.
 */
public final class ExceptionGraphRouting {

    public static final String AUTO_RESOLVE = "AUTO_RESOLVE";
    public static final String MANUAL_REVIEW = "MANUAL_REVIEW";

    private ExceptionGraphRouting() {
    }

    public static String decideRoute(ExceptionAgentState state) {
        if (!state.workflowErrors().isEmpty()) {
            return MANUAL_REVIEW;
        }
        return state.mlResult()
                .map(prediction -> prediction.bypassEligible() ? AUTO_RESOLVE : MANUAL_REVIEW)
                .orElse(MANUAL_REVIEW);
    }
}
