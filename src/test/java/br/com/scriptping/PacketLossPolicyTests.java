package br.com.scriptping;

import static br.com.scriptping.PacketLossAnalysis.Quality;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PacketLossPolicyTests {

    @Test
    void percentagesAreAlwaysRealEvenBelowPreviousTolerance() {
        var rules = new PacketLossPolicy();
        assertThat(rules.visiblePercentage(1, 200)).isEqualTo(.5);
        assertThat(rules.visiblePercentage(1, 3600)).isEqualTo(100.0 / 3600);
        assertThat(rules.visiblePercentage(1, 100)).isEqualTo(1d);
        assertThat(rules.visiblePercentage(0, 0)).isNull();
    }

    @Test
    void hourlyLimitsUseExactUnroundedPercentages() {
        var engine = new PacketLossPolicy();
        assertThat(engine.windowQuality(33, 10000)).isEqualTo(Quality.NORMAL);
        assertThat(engine.windowQuality(330001, 100000000)).isEqualTo(Quality.ATENCAO);
        assertThat(engine.windowQuality(999, 100000)).isEqualTo(Quality.ATENCAO);
        assertThat(engine.windowQuality(1, 100)).isEqualTo(Quality.CRITICO);
        assertThat(engine.windowQuality(2, 100)).isEqualTo(Quality.CRITICO);
        assertThat(engine.windowQuality(0, 0)).isEqualTo(Quality.DADOS_INSUFICIENTES);
    }

    @Test
    void limitsAreConfigurableAndValidated() {
        var rules = new PacketLossPolicy();
        rules.setHourNormalMaxPercent(.05);
        assertThat(rules.windowQuality(1, 1000)).isEqualTo(
            Quality.ATENCAO
        );
        rules.setMinimumCoverageRatio(2);
        assertThatThrownBy(rules::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
