package br.com.scriptping;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PingProbeTests {
    // Timing and transport failures moved to engine_test.go because Java no longer sends ICMP.
    @Test void aFailedPingIsNotReportedBeforeItsConfiguredTimeout() {
        var result=new PingProbe().ping("127.0.0.1",80);
        assertThat(result.latency()).isNull();
        assertThat(new PingSample(1,"h","127.0.0.1",0,result.latency(),result.error()).outcome()).isEqualTo(PingSample.ERROR);
    }
    @Test void successfulResponsesAndExecutionFailuresAreDifferentOutcomes() {
        var success=new PingProbe.Result(1.25,null);
        assertThat(new PingSample(1,"h","127.0.0.1",0,success.latency(),success.error()).outcome()).isEqualTo(PingSample.SUCCESS);
        var error=new PingProbe.Result(null,"Coletor: SEND_ERROR");
        assertThat(new PingSample(1,"h","127.0.0.1",0,error.latency(),error.error()).outcome()).isEqualTo(PingSample.ERROR);
    }
    @Test void interruptedCollectionDoesNotBecomePacketLoss() {
        try {
            Thread.currentThread().interrupt();
            var result=new PingProbe().ping("127.0.0.1",100);
            assertThat(new PingSample(1,"h","127.0.0.1",0,result.latency(),result.error()).outcome()).isEqualTo(PingSample.ERROR);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {Thread.interrupted();}
    }
}
