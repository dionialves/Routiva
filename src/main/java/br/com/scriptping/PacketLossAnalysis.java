package br.com.scriptping;

public record PacketLossAnalysis(SampleStore.Stats stats,Quality minute,Quality hour,int consecutive,
        int maxMinuteSequence,int maxHourSequence,boolean recurring,Level level,boolean recoveryConfirmed,
        boolean fresh,boolean collectorHealthy) {
    public enum Quality {DADOS_INSUFICIENTES,NORMAL,EVENTO_ISOLADO,ATENCAO,DEGRADADO,CRITICO}
    public enum Level {NORMAL,DEGRADADO,CRITICO}
    public PacketLossAnalysis withLevel(Level value){return new PacketLossAnalysis(stats,minute,hour,consecutive,maxMinuteSequence,maxHourSequence,recurring,value,recoveryConfirmed,fresh,collectorHealthy);}
    public boolean recentIssue(){return level!=Level.NORMAL || minute==Quality.ATENCAO || minute==Quality.DEGRADADO || minute==Quality.CRITICO;}
    public String diagnostic(){return "1 min: "+minute+" · 1 h: "+hour+" · sequência máxima min/h: "+maxMinuteSequence+"/"+maxHourSequence;}
}
