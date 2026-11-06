package br.com.scriptping.availability;

public record PacketLossAnalysis(
    Stats stats,
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
    public record Stats(
        Double hour,
        Double minute,
        Double current,
        Long measuredAt,
        String error,
        long attempts,
        long failures,
        long attemptsLastHour,
        long lossesLastHour,
        long attemptsLastMinute,
        long lossesLastMinute,
        Double eightHours,
        Double latencyHour,
        Double fiveMinutes,
        long attemptsEightHours,
        long lossesEightHours,
        long attemptsLatencyHour,
        long lossesLatencyHour,
        long attemptsFiveMinutes,
        long lossesFiveMinutes
    ) {}

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
