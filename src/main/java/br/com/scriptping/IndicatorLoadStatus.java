package br.com.scriptping;

/** Coverage of a published snapshot, not a guarantee that the collector produced every ping. */
public record IndicatorLoadStatus(String phase, Long loadedFrom, Long evaluatedAt,
    long targetFrom, long liveCursor, long committedBoundary) {
    public boolean complete() { return "COMPLETE".equals(phase); }
    public String description() {
        if (evaluatedAt == null) return "Carregando indicadores…";
        if (complete()) return "Histórico carregado";
        if ("CATCHING_UP".equals(phase)) return "Dados parciais · Atualizando registros recentes";
        String coverage = loadedFrom == null ? "Carregando os registros mais recentes"
            : "Histórico carregado desde " + java.time.Instant.ofEpochMilli(loadedFrom);
        return "Dados parciais · " + coverage + " · Recuperação do histórico em andamento";
    }
}
