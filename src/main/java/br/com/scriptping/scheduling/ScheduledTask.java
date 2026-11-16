package br.com.scriptping.scheduling;

import java.util.Map;

/** Implementations are Spring beans. Never invoke HTTP controllers or depend on browser sessions. */
public interface ScheduledTask {
    String key();
    String label();
    default void validate(Map<String, String> parameters) {
        if (!parameters.isEmpty()) throw new IllegalArgumentException("Esta tarefa não aceita parâmetros.");
    }
    Outcome execute(Map<String, String> parameters) throws Exception;
    default Outcome execute(Map<String, String> parameters, Context context) throws Exception { return execute(parameters); }
    record Detail(String hostId, String hostName, String state, String message) {}
    record Context(String executionId, java.util.function.Consumer<Detail> report) {}
    record Outcome(boolean partial, String message) {
        public static Outcome completed(String message) { return new Outcome(false, message); }
    }
}
