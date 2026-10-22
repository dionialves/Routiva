package br.com.scriptping;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class PingProbeTests {
    static class Completed extends Process {
        final int exit;final String output;
        Completed(int exit,String output){this.exit=exit;this.output=output;}
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public InputStream getInputStream(){return new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));}
        public InputStream getErrorStream(){return InputStream.nullInputStream();}
        public int waitFor(){return exit;}
        public boolean waitFor(long timeout,TimeUnit unit)throws InterruptedException{return true;}
        public int exitValue(){return exit;}
        public void destroy(){}
    }
    PingProbe probe(Process process){return new PingProbe(){@Override protected Process start(ProcessBuilder ignored){return process;}};}
    @Test void aFailedPingIsNotReportedBeforeItsConfiguredTimeout() {
        long started=System.nanoTime();
        var result=probe(new Completed(1,"unreachable")).ping("127.0.0.1",80);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isGreaterThanOrEqualTo(75);
        assertThat(result.latency()).isNull();assertThat(result.error()).isEqualTo("Sem resposta ICMP");
    }
    @Test void successfulResponsesAndExecutionFailuresAreDifferentOutcomes() {
        var success=probe(new Completed(0,"64 bytes time=1.25 ms")).ping("127.0.0.1",100);
        assertThat(success.latency()).isEqualTo(1.25);
        var error=probe(new Completed(2,"permission denied")).ping("127.0.0.1",100);
        assertThat(error.error()).isEqualTo("Falha ao executar ping");
        assertThat(new PingSample(1,"h","127.0.0.1",0,error.latency(),error.error()).outcome()).isEqualTo(PingSample.ERROR);
    }
    @Test void interruptedCollectionDoesNotBecomePacketLoss() {
        Process interrupted=new Completed(0,""){@Override public boolean waitFor(long timeout,TimeUnit unit)throws InterruptedException{throw new InterruptedException();}};
        try {
            var result=probe(interrupted).ping("127.0.0.1",100);
            assertThat(result.error()).isEqualTo("Interrompido");assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {Thread.interrupted();}
    }
}
