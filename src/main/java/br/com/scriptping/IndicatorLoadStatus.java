package br.com.scriptping;

/** Coverage of a published snapshot, not a guarantee that the collector produced every ping. */
public record IndicatorLoadStatus(String phase, Long loadedFrom, Long evaluatedAt,
    long targetFrom, long liveCursor, long committedBoundary) {
    public boolean complete() { return "COMPLETE".equals(phase); }
    public String description() {
        if (evaluatedAt == null) return "Carregando indicadores…";
        if (complete()) return "Indicadores atualizados";
        return "Dados parciais · Atualizando registros recentes";
    }
}
