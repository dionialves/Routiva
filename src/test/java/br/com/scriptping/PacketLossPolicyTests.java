package br.com.scriptping;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PacketLossPolicyTests {
    @Test void ignoresLossesUpToOnePercentInclusively() {
        var policy=new PacketLossPolicy(1);
        assertThat(policy.visiblePercentage(1,200)).isZero();
        assertThat(policy.visiblePercentage(1,100)).isZero();
        assertThat(policy.significant(1,100)).isFalse();
        assertThat(policy.visiblePercentage(101,10000)).isEqualTo(1.01);
        assertThat(policy.significant(101,10000)).isTrue();
        assertThat(policy.visiblePercentage(0,0)).isNull();
    }

    @Test void thresholdIsEditableAndRejectsInvalidValues() {
        assertThat(new PacketLossPolicy(2).significant(2,100)).isFalse();
        assertThat(new PacketLossPolicy(2).significant(3,100)).isTrue();
        assertThat(new PacketLossPolicy(0).significant(1,100)).isTrue();
        for(double value:new double[]{-1,101,Double.NaN,Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(()->new PacketLossPolicy(value)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
