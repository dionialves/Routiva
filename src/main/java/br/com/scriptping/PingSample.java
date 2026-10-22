package br.com.scriptping;

public record PingSample(long id,String hostId,String target,long measuredAt,Double latency,String error) {
    public static final byte SUCCESS=0, LOSS=1, ERROR=2;
    public byte outcome() {
        if(latency!=null && Double.isFinite(latency) && latency>=0) return SUCCESS;
        return "Tempo esgotado".equals(error) || "Sem resposta ICMP".equals(error) ? LOSS : ERROR;
    }
}
