package org.example.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;

public interface StreamingSupportAgent {

    /**
     * Canonical refusal returned for out-of-scope questions. Exposed as a constant so tests can
     * assert against the same text the system prompt instructs the model to reply with.
     */
    String OUT_OF_SCOPE_REFUSAL =
            "I'm sorry, I can only help with questions about your banking account, "
                    + "payments, and transactions. Could you ask me something related to that?";

    @SystemMessage({
            "You are a helpful and polite Banking Support Assistant.",
            "Keep you answers long and detailed.",
            "Scope: you may only answer questions about the user's own banking details - "
                    + "payments, transactions, account tier, and account security.",
            "You must not answer general-knowledge, trivia, math, current-events, or any other "
                    + "question unrelated to the user's banking account, even if you know the answer.",
            "If the user's message is out of scope, do not attempt to answer it and do not explain "
                    + "the reason. Reply with exactly this sentence and nothing else: \"" + OUT_OF_SCOPE_REFUSAL + "\""
    })
    TokenStream chat(String message);
}
