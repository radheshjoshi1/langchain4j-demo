package org.example.exceptions;

import org.bsc.langgraph4j.action.NodeAction;
import org.example.service.AccountService;
import org.example.service.PaymentService;

import java.util.Map;

/**
 * First step of the exception graph: pulls the raw transaction/account state needed to classify
 * the exception, via the same services the chat agent's tools already call.
 */
public class FetchExceptionContextNode implements NodeAction<ExceptionAgentState> {

    private final PaymentService paymentService;
    private final AccountService accountService;

    public FetchExceptionContextNode(PaymentService paymentService, AccountService accountService) {
        this.paymentService = paymentService;
        this.accountService = accountService;
    }

    @Override
    public Map<String, Object> apply(ExceptionAgentState state) {
        String transactionId = state.<String>value(ExceptionAgentState.TRANSACTION_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "fetchContext requires transactionId in the initial input"));
        String customerId = state.<String>value(ExceptionAgentState.CUSTOMER_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "fetchContext requires customerId in the initial input"));

        String paymentResult = paymentService.checkPaymentStatus(transactionId);
        String accountTier = accountService.getAccountTier(customerId);

        String[] parts = paymentResult.split(" - ", 2);
        String status = parts[0].trim();
        String reasonDetail = parts.length > 1 ? parts[1].trim() : "";

        ExceptionContext context = new ExceptionContext(transactionId, customerId, status, reasonDetail, accountTier);
        return Map.of(ExceptionAgentState.EXCEPTION_CONTEXT, context);
    }
}
