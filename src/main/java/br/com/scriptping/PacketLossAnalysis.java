package br.com.scriptping;

public record PacketLossAnalysis(
    SampleStore.Stats stats,
    Quality minute,
    Quality hour,
    int consecutive,
    int maxMinuteSequence,
    int maxHourSequence,
    boolean recurring,
    Level level,
    boolean recoveryConfirmed,
    boolean fresh,
    boolean collectorHealthy
) {
    public enum Quality {
        DADOS_INSUFICIENTES,
        NORMAL,
        EVENTO_ISOLADO,
        ATENCAO,
        DEGRADADO,
        CRITICO,
    }

    public enum Level {
        NORMAL,
        DEGRADADO,
        CRITICO,
    }

    public String diagnostic() {
        return (
            "Perdas/tentativas · 8 h: " +
            stats.lossesEightHours() +
            "/" +
            stats.attemptsEightHours() +
            " · 1 h: " +
            stats.lossesLatencyHour() +
            "/" +
            stats.attemptsLatencyHour() +
            " · 5 min: " +
            stats.lossesFiveMinutes() +
            "/" +
            stats.attemptsFiveMinutes()
        );
    }
}
