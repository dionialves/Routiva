package br.com.scriptping;

import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Regras de classificação; percentuais apresentados nunca são suprimidos. */
@Component
@ConfigurationProperties(prefix="monitor.loss")
public class PacketLossPolicy {
    private long minuteWindowSeconds = 60;
    private long hourWindowSeconds = 3600;
    private int minuteReferencePackets = 60;
    private double minuteAttentionLosses = 2;
    private double minuteDegradedLosses = 3;
    private double minuteCriticalLosses = 6;
    private double hourNormalMaxPercent = 0.05;
    private double hourDegradedMinPercent = 0.1;
    private double hourCriticalMinPercent = 0.5;
    private int consecutiveCritical = 3;
    private int recurrenceBuckets = 3;
    private int recurrenceRequiredBuckets = 2;
    private double recurrenceBucketLosses = 2;
    private long recurrenceBucketSeconds = 60;
    private long recoverySeconds = 300;
    private double minimumCoverageRatio = 0.8;
    private int minimumSamples = 2;
    private double maxGapMultiplier = 3;
    private long cacheMillis = 1000;
    public long getMinuteWindowSeconds() {return minuteWindowSeconds;}
    public void setMinuteWindowSeconds(long value) {this.minuteWindowSeconds=value;}
    public long getHourWindowSeconds() {return hourWindowSeconds;}
    public void setHourWindowSeconds(long value) {this.hourWindowSeconds=value;}
    public int getMinuteReferencePackets() {return minuteReferencePackets;}
    public void setMinuteReferencePackets(int value) {this.minuteReferencePackets=value;}
    public double getMinuteAttentionLosses() {return minuteAttentionLosses;}
    public void setMinuteAttentionLosses(double value) {this.minuteAttentionLosses=value;}
    public double getMinuteDegradedLosses() {return minuteDegradedLosses;}
    public void setMinuteDegradedLosses(double value) {this.minuteDegradedLosses=value;}
    public double getMinuteCriticalLosses() {return minuteCriticalLosses;}
    public void setMinuteCriticalLosses(double value) {this.minuteCriticalLosses=value;}
    public double getHourNormalMaxPercent() {return hourNormalMaxPercent;}
    public void setHourNormalMaxPercent(double value) {this.hourNormalMaxPercent=value;}
    public double getHourDegradedMinPercent() {return hourDegradedMinPercent;}
    public void setHourDegradedMinPercent(double value) {this.hourDegradedMinPercent=value;}
    public double getHourCriticalMinPercent() {return hourCriticalMinPercent;}
    public void setHourCriticalMinPercent(double value) {this.hourCriticalMinPercent=value;}
    public int getConsecutiveCritical() {return consecutiveCritical;}
    public void setConsecutiveCritical(int value) {this.consecutiveCritical=value;}
    public int getRecurrenceBuckets() {return recurrenceBuckets;}
    public void setRecurrenceBuckets(int value) {this.recurrenceBuckets=value;}
    public int getRecurrenceRequiredBuckets() {return recurrenceRequiredBuckets;}
    public void setRecurrenceRequiredBuckets(int value) {this.recurrenceRequiredBuckets=value;}
    public double getRecurrenceBucketLosses() {return recurrenceBucketLosses;}
    public void setRecurrenceBucketLosses(double value) {this.recurrenceBucketLosses=value;}
    public long getRecurrenceBucketSeconds() {return recurrenceBucketSeconds;}
    public void setRecurrenceBucketSeconds(long value) {this.recurrenceBucketSeconds=value;}
    public long getRecoverySeconds() {return recoverySeconds;}
    public void setRecoverySeconds(long value) {this.recoverySeconds=value;}
    public double getMinimumCoverageRatio() {return minimumCoverageRatio;}
    public void setMinimumCoverageRatio(double value) {this.minimumCoverageRatio=value;}
    public int getMinimumSamples() {return minimumSamples;}
    public void setMinimumSamples(int value) {this.minimumSamples=value;}
    public double getMaxGapMultiplier() {return maxGapMultiplier;}
    public void setMaxGapMultiplier(double value) {this.maxGapMultiplier=value;}
    public long getCacheMillis() {return cacheMillis;}
    public void setCacheMillis(long value) {this.cacheMillis=value;}
    public Double visiblePercentage(long losses,long attempts) {return attempts==0?null:100.0*losses/attempts;}
    public PacketLossAnalysis.Quality windowQuality(long losses,long attempts) {
        if(attempts==0)return PacketLossAnalysis.Quality.DADOS_INSUFICIENTES;
        double percentage=100.0*losses/attempts;
        if(percentage>=hourCriticalMinPercent)return PacketLossAnalysis.Quality.CRITICO;
        if(percentage>=hourDegradedMinPercent)return PacketLossAnalysis.Quality.DEGRADADO;
        return percentage>hourNormalMaxPercent?PacketLossAnalysis.Quality.ATENCAO:PacketLossAnalysis.Quality.NORMAL;
    }
    public void validate() {
        if (minuteWindowSeconds<1 || hourWindowSeconds<minuteWindowSeconds || recurrenceBucketSeconds<1 || recoverySeconds<1 || cacheMillis<1 || minimumSamples<1 || minuteReferencePackets<1 || consecutiveCritical<1 || recurrenceBuckets<1 || recurrenceBuckets>60 || recurrenceRequiredBuckets<1 || recurrenceRequiredBuckets>recurrenceBuckets)
            throw new IllegalArgumentException("Janelas e contagens de monitor.loss precisam ser positivas e coerentes.");
        if (!Double.isFinite(minuteAttentionLosses) || !Double.isFinite(minuteDegradedLosses) || !Double.isFinite(minuteCriticalLosses) || !(minimumCoverageRatio>=0 && minimumCoverageRatio<=1) || !Double.isFinite(maxGapMultiplier) || maxGapMultiplier<1 || !(minuteAttentionLosses>1 && minuteDegradedLosses>minuteAttentionLosses && minuteCriticalLosses>minuteDegradedLosses) || !(hourNormalMaxPercent>=0 && hourNormalMaxPercent<hourDegradedMinPercent && hourDegradedMinPercent<hourCriticalMinPercent && hourCriticalMinPercent<=100) || !Double.isFinite(recurrenceBucketLosses) || recurrenceBucketLosses<=0)
            throw new IllegalArgumentException("Limites de monitor.loss inválidos ou fora de ordem.");
    }
}
