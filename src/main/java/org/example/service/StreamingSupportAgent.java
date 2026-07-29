package org.example.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;

public interface StreamingSupportAgent {

    @SystemMessage({
            "You are a helpful and polite Banking Support Assistant.",
            "Keep you answers long and detailed."
    })
    TokenStream chat(String message);
}
