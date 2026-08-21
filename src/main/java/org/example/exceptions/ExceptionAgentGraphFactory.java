package org.example.exceptions;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.example.ml.LocalMlModelClient;
import org.example.service.AccountService;
import org.example.service.PaymentService;

import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * Builds the exception-triage graph: fetch context -&gt; classify with the embedded local ML
 * model -&gt; route to auto-resolve or manual review. Mirrors a {@code CallMlModelNode} step in a
 * LangGraph exception workflow, adapted to this demo's banking-support domain (failed/pending
 * payments as "exceptions").
 */
public final class ExceptionAgentGraphFactory {

    private ExceptionAgentGraphFactory() {
    }

    public static CompiledGraph<ExceptionAgentState> build(
            PaymentService paymentService, AccountService accountService, LocalMlModelClient mlModelClient)
            throws GraphStateException {

        StateGraph<ExceptionAgentState> graph = new StateGraph<>(ExceptionAgentState::new)
                .addNode("fetchContext", node_async(new FetchExceptionContextNode(paymentService, accountService)))
                .addNode("callMlModel", node_async(new CallMlModelNode(mlModelClient)))
                .addNode("autoResolve", node_async(new AutoResolveNode()))
                .addNode("manualReview", node_async(new ManualReviewNode()))
                .addEdge(StateGraph.START, "fetchContext")
                .addEdge("fetchContext", "callMlModel")
                .addConditionalEdges("callMlModel", edge_async(ExceptionGraphRouting::decideRoute), Map.of(
                        ExceptionGraphRouting.AUTO_RESOLVE, "autoResolve",
                        ExceptionGraphRouting.MANUAL_REVIEW, "manualReview"))
                .addEdge("autoResolve", StateGraph.END)
                .addEdge("manualReview", StateGraph.END);

        return graph.compile();
    }
}
