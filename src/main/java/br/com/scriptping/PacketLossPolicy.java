package br.com.scriptping;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Regras de classificação; percentuais apresentados nunca são suprimidos. */
@Component
@ConfigurationProperties(prefix = "monitor.loss")
public class PacketLossPolicy {

    private long minuteWindowSeconds = 60;
    private long hourWindowSeconds = 3600;
    private double hourNormalMaxPercent = 0.33;
    private double hourCriticalMinPercent = 1.0;
    private double minimumCoverageRatio = 0.8;
    private int minimumSamples = 2;
    private double maxGapMultiplier = 3;
    private long cacheMillis = 1000;

    public long getMinuteWindowSeconds() {
        return minuteWindowSeconds;
    }

    public void setMinuteWindowSeconds(long value) {
        this.minuteWindowSeconds = value;
    }

    public long getHourWindowSeconds() {
        return hourWindowSeconds;
    }

    public void setHourWindowSeconds(long value) {
        this.hourWindowSeconds = value;
    }

    public double getHourNormalMaxPercent() {
        return hourNormalMaxPercent;
    }

    public void setHourNormalMaxPercent(double value) {
        this.hourNormalMaxPercent = value;
    }

    public double getHourCriticalMinPercent() {
        return hourCriticalMinPercent;
    }

    public void setHourCriticalMinPercent(double value) {
        this.hourCriticalMinPercent = value;
    }

    public double getMinimumCoverageRatio() {
        return minimumCoverageRatio;
    }

    public void setMinimumCoverageRatio(double value) {
        this.minimumCoverageRatio = value;
    }

    public int getMinimumSamples() {
        return minimumSamples;
    }

    public void setMinimumSamples(int value) {
        this.minimumSamples = value;
    }

    public double getMaxGapMultiplier() {
        return maxGapMultiplier;
    }

    public void setMaxGapMultiplier(double value) {
        this.maxGapMultiplier = value;
    }

    public long getCacheMillis() {
        return cacheMillis;
    }

    public void setCacheMillis(long value) {
        this.cacheMillis = value;
    }

    public Double visiblePercentage(long losses, long attempts) {
        return attempts == 0 ? null : (100.0 * losses) / attempts;
    }

    public PacketLossAnalysis.Quality windowQuality(long losses, long attempts) {
        if (attempts == 0) return PacketLossAnalysis.Quality.DADOS_INSUFICIENTES;
        double percentage = (100.0 * losses) / attempts;
        if (percentage >= hourCriticalMinPercent) return PacketLossAnalysis.Quality.CRITICO;
        return percentage > hourNormalMaxPercent
            ? PacketLossAnalysis.Quality.ATENCAO
            : PacketLossAnalysis.Quality.NORMAL;
    }

    public void validate() {
        if (
            minuteWindowSeconds < 1 ||
            hourWindowSeconds < minuteWindowSeconds ||
            cacheMillis < 1 ||
            minimumSamples < 1
        ) {
            throw new IllegalArgumentException("Janelas e contagens de monitor.loss inválidas.");
        }
        if (
            !(minimumCoverageRatio >= 0 && minimumCoverageRatio <= 1) ||
            !Double.isFinite(maxGapMultiplier) ||
            maxGapMultiplier < 1 ||
            !(
                hourNormalMaxPercent >= 0 &&
                hourNormalMaxPercent < hourCriticalMinPercent &&
                hourCriticalMinPercent <= 100
            )
        ) {
            throw new IllegalArgumentException(
                "Limites de monitor.loss inválidos ou fora de ordem."
            );
        }
    }
}
