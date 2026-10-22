package br.com.scriptping;

import br.com.scriptping.collector.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** One bounded persistence consumer. gRPC callbacks never execute database work. */
@Component("collector")
public class CollectorClient implements HealthIndicator {
    private record Delivery(Batch batch,long generation) {}
    private final SampleStore store;
    private final String address,ca,cert,key,executable;
    private final long interval,timeout;
    private final boolean enabled;
    private final BlockingQueue<Delivery> queue=new ArrayBlockingQueue<>(8);
    private final ScheduledExecutorService control=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService writer=Executors.newSingleThreadExecutor();
    private ManagedChannel channel;
    private EpollEventLoopGroup loops;
    private Process process;
    private StreamObserver<Command> commands;
    private ClientCallStreamObserver<Command> inbound;
    private long generation;
    private volatile boolean connected,closed;
    private volatile List<Host> hosts=List.of();
    private volatile Map<String,Double> metrics=Map.of();
    private volatile long lastHealth,committed,writeFailures;
    private volatile String failure="Aguardando coletor";
    private int retries;
    private long nextConnect;

    public CollectorClient(SampleStore store,
            @Value("${collector.address:unix:///run/routiva/collector.sock}") String address,
            @Value("${collector.tls-ca:}") String ca,@Value("${collector.tls-cert:}") String cert,
            @Value("${collector.tls-key:}") String key,@Value("${collector.executable:}") String executable,
            @Value("${collector.enabled:true}") boolean enabled,
            @Value("${monitor.interval-ms:1000}") long interval,@Value("${monitor.timeout-ms:1500}") long timeout) {
        if(interval<100||interval>3600000||timeout<100||timeout>60000)throw new IllegalArgumentException("Invalid collector interval/timeout");
        this.store=store;this.address=address;this.ca=ca;this.cert=cert;this.key=key;this.executable=executable;this.enabled=enabled;this.interval=interval;this.timeout=timeout;
    }
    @PostConstruct public void start() {
        if(!enabled)return;
        if(!executable.isBlank())try{process=new ProcessBuilder(executable).inheritIO().start();}catch(Exception e){throw new IllegalStateException("Cannot start collector",e);}
        writer.submit(this::persist);
        control.scheduleWithFixedDelay(this::maintain,0,1,TimeUnit.SECONDS);
    }
    public synchronized void synchronize(List<Host> hosts) {this.hosts=List.copyOf(hosts);if(connected)sendConfiguration();}
    private void sendConfiguration() {
        var config=Configuration.newBuilder().setReplace(true);
        for(Host host:hosts)if(IpAddress.isValid(host.ip())&&!host.ip().contains(":"))
            config.addHosts(HostConfig.newBuilder().setHostId(host.id()).setTarget(host.ip()).setIntervalMs(interval).setTimeoutMs(timeout).setEnabled(true).setLatencyLimitMs(store.latencyLimit()));
        commands.onNext(Command.newBuilder().setProtocolVersion(1).setConfig(config).build());
    }
    private void maintain() {
        if(closed)return;
        try {
            synchronized(this){if(!connected&&System.nanoTime()>=nextConnect)connect();}
            if(channel!=null&&connected){var health=CollectorGrpc.newBlockingStub(channel).withDeadlineAfter(2,TimeUnit.SECONDS).health(HealthRequest.getDefaultInstance());metrics=health.getMetricsMap();lastHealth=System.currentTimeMillis();}
        }catch(Exception e){synchronized(this){disconnect(generation,"Coletor indisponível: "+e.getClass().getSimpleName());}}
    }
    private synchronized void connect() throws Exception {
        if(channel==null){
            NettyChannelBuilder builder;
            if(address.startsWith("unix://")){
                if(!io.netty.channel.epoll.Epoll.isAvailable())throw new IllegalStateException("Unix Domain Socket requires Linux epoll");
                loops=new EpollEventLoopGroup(1);
                builder=NettyChannelBuilder.forAddress(new DomainSocketAddress(address.substring(7))).eventLoopGroup(loops).channelType(EpollDomainSocketChannel.class).usePlaintext();
            }else if(address.startsWith("https://")){
                var uri=java.net.URI.create(address);
                builder=NettyChannelBuilder.forAddress(uri.getHost(),uri.getPort()).sslContext(GrpcSslContexts.forClient().trustManager(new File(ca)).keyManager(new File(cert),new File(key)).build());
            }else throw new IllegalArgumentException("Use unix:// or https:// with mutual TLS");
            channel=builder.maxInboundMessageSize(4*1024*1024).build();
        }
        long current=++generation;
        commands=CollectorGrpc.newStub(channel).connect(new ClientResponseObserver<Command,Batch>() {
            @Override public void beforeStart(ClientCallStreamObserver<Command> request){request.disableAutoRequestWithInitial(1);inbound=request;}
            @Override public void onNext(Batch batch){
                if(batch.getProtocolVersion()!=1||batch.getResultsCount()>5000||batch.getCollectorId().isBlank()||batch.getBatchId()<=0){synchronized(CollectorClient.this){disconnect(current,"Lote inválido");}return;}
                if(!queue.offer(new Delivery(batch,current)))synchronized(CollectorClient.this){disconnect(current,"Fila de persistência cheia");}
            }
            @Override public void onError(Throwable t){synchronized(CollectorClient.this){disconnect(current,"Stream interrompido");}}
            @Override public void onCompleted(){synchronized(CollectorClient.this){disconnect(current,"Stream encerrado");}}
        });
        connected=true;sendConfiguration();
    }
    private synchronized void disconnect(long current,String reason){
        if(current!=generation)return;
        connected=false;failure=reason;generation++;
        if(inbound!=null){inbound.cancel(reason,null);inbound=null;}
        nextConnect=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(30000,500L<<Math.min(retries++,6))+ThreadLocalRandom.current().nextLong(250));
    }
    private void persist(){
        while(!closed||!queue.isEmpty())try{
            Delivery d=queue.poll(1,TimeUnit.SECONDS);if(d==null)continue;
            boolean saved=false;
            while(!saved&&!Thread.currentThread().isInterrupted())try{store.saveBatch(d.batch());saved=true;committed+=d.batch().getResultsCount();}catch(Exception e){writeFailures++;failure="Falha na persistência";LoggerFactory.getLogger(getClass()).warn("Falha ao gravar lote ICMP; mantendo para repetição",e);Thread.sleep(1000);}
            synchronized(this){if(saved&&connected&&d.generation()==generation){commands.onNext(Command.newBuilder().setProtocolVersion(1).setAck(Ack.newBuilder().setCollectorId(d.batch().getCollectorId()).setBatchId(d.batch().getBatchId())).build());if(!closed)inbound.request(1);retries=0;}}
        }catch(InterruptedException e){Thread.currentThread().interrupt();return;}catch(Exception e){synchronized(this){disconnect(generation,"Falha no consumidor");}}
    }
    @Override public Health health(){
        if(!enabled)return Health.unknown().withDetail("enabled",false).build();
        var result=connected&&System.currentTimeMillis()-lastHealth<10000?Health.up():Health.down();
        return result.withDetail("message",connected?"Conectado":failure).withDetail("queueBatches",queue.size()).withDetail("committedResults",committed).withDetail("writeFailures",writeFailures).withDetail("engine",metrics).build();
    }
    @PreDestroy public void close(){
        closed=true;control.shutdownNow();
        writer.shutdown();try{if(!writer.awaitTermination(10,TimeUnit.SECONDS))writer.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();writer.shutdownNow();}
        synchronized(this){disconnect(generation,"Encerramento");}
        if(channel!=null)channel.shutdownNow();if(loops!=null)loops.shutdownGracefully();if(process!=null)process.destroy();
        if(!queue.isEmpty())LoggerFactory.getLogger(getClass()).warn("Encerramento com {} lotes não confirmados",queue.size());
    }
}
