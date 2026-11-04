package br.com.scriptping;

/** Legacy value adapter. ICMP execution belongs exclusively to the Go collector. */
public class PingProbe {

    public record Result(Double latency, String error) {}

    @Deprecated
    public Result ping(String ip, int timeoutMs) {
        return new Result(
            null,
            IpAddress.isValid(ip)
                ? "Coleta ICMP disponível somente pelo coletor Go"
                : "IP ausente ou inválido"
        );
    }
}
