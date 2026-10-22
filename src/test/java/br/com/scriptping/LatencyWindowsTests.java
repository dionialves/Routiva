package br.com.scriptping;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LatencyWindowsTests {
    @Test void movingWindowsExcludeTheirLowerBoundaryErrorsAndFutureSamples() {
        long now=40_000_000L;
        var history=new RollingPingHistory();
        history.add(new PingSample(1,"h","127.0.0.1",now-8*3600000L,1000d,null));
        history.add(new PingSample(2,"h","127.0.0.1",now-3600000L,30d,null));
        history.add(new PingSample(3,"h","127.0.0.1",now-300000L,20d,null));
        history.add(new PingSample(4,"h","127.0.0.1",now-1000,10d,null));
        history.add(new PingSample(5,"h","127.0.0.1",now,null,"Tempo esgotado"));
        history.add(new PingSample(6,"h","127.0.0.1",now,null,"Falha ao executar ping"));
        history.add(new PingSample(7,"h","127.0.0.1",now+1000,1000d,null));
        var analyzer=new PacketLossAnalyzer(new PacketLossPolicy(),1000,2000);
        var stats=analyzer.analyze(history,now).stats();
        assertThat(analyzer.lookbackMs()).isGreaterThanOrEqualTo(8*3600000L);
        assertThat(stats.eightHours()).isEqualTo(20d);
        assertThat(stats.latencyHour()).isEqualTo(15d);
        assertThat(stats.fiveMinutes()).isEqualTo(10d);
        assertThat(stats.attemptsEightHours()).isEqualTo(4);
        assertThat(stats.lossesEightHours()).isEqualTo(1);
        assertThat(stats.attemptsLastHour()).isEqualTo(3);
        assertThat(stats.attemptsFiveMinutes()).isEqualTo(2);
        assertThat(stats.lossesFiveMinutes()).isEqualTo(1);
    }
}
