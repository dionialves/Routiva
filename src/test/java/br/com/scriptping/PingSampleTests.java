package br.com.scriptping;

import br.com.scriptping.ingestion.PingSample;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class PingSampleTests {
    @ParameterizedTest(name = "latency={0}, error={1} => outcome={2}")
    @CsvSource(value = {
        "0,,0", "1.25,,0", "1.25,Tempo esgotado,0",
        ",Tempo esgotado,1", ",Sem resposta ICMP,1",
        ",,2", ",Coletor: SEND_ERROR,2", "-1,,2",
        "NaN,,2", "Infinity,,2", "-Infinity,,2",
        "NaN,Tempo esgotado,1", "-1,Sem resposta ICMP,1"
    })
    void classifiesPersistedMeasurementsWithoutInventingLoss(Double latency, String error, byte expected) {
        var sample = new PingSample(1, "host", "127.0.0.1", 1000, latency, error);
        assertThat(sample.outcome()).isEqualTo(expected);
    }
}
