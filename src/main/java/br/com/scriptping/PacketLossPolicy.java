package br.com.scriptping;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PacketLossPolicy {
    private final double ignorePercent;

    public PacketLossPolicy(@Value("${monitor.packet-loss-ignore-percent:1}") double ignorePercent) {
        if (!Double.isFinite(ignorePercent) || ignorePercent < 0 || ignorePercent > 100) {
            throw new IllegalArgumentException("monitor.packet-loss-ignore-percent deve estar entre 0 e 100.");
        }
        this.ignorePercent = ignorePercent;
    }

    public Double visiblePercentage(long losses, long attempts) {
        if (attempts == 0) return null;
        double percentage = 100.0 * losses / attempts;
        return percentage <= ignorePercent ? 0.0 : percentage;
    }

    public boolean significant(long losses, long attempts) {
        Double percentage = visiblePercentage(losses, attempts);
        return percentage != null && percentage > 0;
    }
}
