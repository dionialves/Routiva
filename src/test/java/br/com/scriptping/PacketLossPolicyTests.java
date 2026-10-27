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
        var engine = new PacketLossAnalyzer(new PacketLossPolicy(), 1000, 2000);
        assertThat(engine.hourQuality(33, 10000, true)).isEqualTo(Quality.NORMAL);
        assertThat(engine.hourQuality(330001, 100000000, true)).isEqualTo(Quality.ATENCAO);
        assertThat(engine.hourQuality(999, 100000, true)).isEqualTo(Quality.ATENCAO);
        assertThat(engine.hourQuality(1, 100, true)).isEqualTo(Quality.CRITICO);
        assertThat(engine.hourQuality(2, 100, true)).isEqualTo(Quality.CRITICO);
        assertThat(engine.hourQuality(0, 0, true)).isEqualTo(Quality.DADOS_INSUFICIENTES);
        assertThat(engine.hourQuality(1, 1000, false)).isEqualTo(Quality.DADOS_INSUFICIENTES);
    }

    @Test
    void limitsAreConfigurableAndValidated() {
        var rules = new PacketLossPolicy();
        rules.setHourNormalMaxPercent(.05);
        assertThat(new PacketLossAnalyzer(rules, 1000, 2000).hourQuality(1, 1000, true)).isEqualTo(
            Quality.ATENCAO
        );
        rules.setMinimumCoverageRatio(2);
        assertThatThrownBy(rules::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
