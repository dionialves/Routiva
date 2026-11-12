package br.com.scriptping.ingestion;

import br.com.scriptping.zabbix.Host;
import br.com.scriptping.IpAddress;
import br.com.scriptping.collector.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** One bounded persistence consumer. gRPC callbacks never execute database work. */
@Component("collector")
public class CollectorClient implements HealthIndicator {

    private record Delivery(Batch batch, long generation, long receivedAt) {}

    private final SampleIngestionService store;
    private final String address, ca, cert, key, executable;
    private final long interval, timeout;
    private final boolean enabled;
    private final BlockingQueue<Delivery> queue = new ArrayBlockingQueue<>(64);
    @Value("${collector.send-window:16}")
    private int sendWindow = 16;
    @Value("${collector.persistence-max-results:5000}")
    private int maxGroupResults = 5000;
    @Value("${collector.persistence-wait-ms:100}")
    private long groupWaitMs = 100;
    private volatile long committedGroups, lastGroupBatches, lastDatabaseNanos, lastCommitNanos;
    private volatile long lastQueueNanos, lastLockNanos, lastInsertNanos;
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private ManagedChannel channel;
    private EpollEventLoopGroup loops;
    private Process process;
    private StreamObserver<Command> commands;
    private ClientCallStreamObserver<Command> inbound;
    private long generation;
    private volatile boolean connected, closed, persistenceFailure;
    private volatile long rejected;
    private volatile List<Host> hosts = List.of();
    private volatile Map<String, Double> metrics = Map.of();
    private volatile long lastHealth, committed, writeFailures;
    private volatile String failure = "Aguardando coletor";
    private ClientPingSessions clientPings;
    private long clientPingRevision = -1;
    @org.springframework.beans.factory.annotation.Autowired
    public void setClientPings(ClientPingSessions clientPings) { this.clientPings = clientPings; }

    public boolean enabled() { return enabled; }

    public synchronized void refreshClientPings() {
        if (!connected) return;
        try { sendConfiguration(); }
        catch (RuntimeException error) { disconnect(generation, "Falha ao atualizar alvos temporários"); }
    }

    private int retries;
    private long nextConnect;

    public CollectorClient(
        SampleIngestionService store,
        @Value("${collector.address:unix:///run/routiva/collector.sock}") String address,
        @Value("${collector.tls-ca:}") String ca,
        @Value("${collector.tls-cert:}") String cert,
        @Value("${collector.tls-key:}") String key,
        @Value("${collector.executable:}") String executable,
        @Value("${collector.enabled:true}") boolean enabled,
        @Value("${monitor.interval-ms:1000}") long interval,
        @Value("${monitor.timeout-ms:1500}") long timeout
    ) {
        if (
            interval < 100 || interval > 3600000 || timeout < 100 || timeout > 60000
        ) throw new IllegalArgumentException("Invalid collector interval/timeout");
        this.store = store;
        this.address = address;
        this.ca = ca;
        this.cert = cert;
        this.key = key;
        this.executable = executable;
        this.enabled = enabled;
        this.interval = interval;
        this.timeout = timeout;
    }

    @PostConstruct
    public void start() {
        if (sendWindow < 1 || sendWindow > 32 || maxGroupResults < 5000 || maxGroupResults > 20000
            || groupWaitMs < 0 || groupWaitMs > 1000) throw new IllegalArgumentException("Invalid collector persistence limits");
        if (!enabled) return;
        if (!executable.isBlank()) try {
            process = new ProcessBuilder(executable).inheritIO().start();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start collector", e);
        }
        writer.submit(this::persist);
        control.scheduleWithFixedDelay(this::maintain, 0, 1, TimeUnit.SECONDS);
    }

    public synchronized void synchronize(List<Host> hosts) {
        this.hosts = hosts.stream().filter(h -> !h.acknowledgedIcmpProblem()).toList();
        if (connected) sendConfiguration();
    }

    private void sendConfiguration() {
        var config = Configuration.newBuilder().setReplace(true);
        for (Host host : hosts)
            if (IpAddress.isValid(host.ip()) && !host.ip().contains(":")) config.addHosts(
                HostConfig.newBuilder()
                    .setHostId(host.id())
                    .setTarget(host.ip())
                    .setIntervalMs(interval)
                    .setTimeoutMs(timeout)
                    .setEnabled(true)
                    .setLatencyLimitMs(store.latencyLimit())
            );
        if (clientPings != null) {
            clientPingRevision = clientPings.revision();
            config.addAllHosts(clientPings.configurations(interval, timeout, store.latencyLimit()));
        }
        commands.onNext(Command.newBuilder().setProtocolVersion(1).setConfig(config).build());
    }

    private void maintain() {
        if (closed) return;
        try {
            synchronized (this) {
                if (!connected && System.nanoTime() >= nextConnect) connect();
                if (connected && clientPings != null && clientPings.revision() != clientPingRevision) sendConfiguration();
            }
            if (channel != null && connected) {
                var health = CollectorGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(2, TimeUnit.SECONDS)
                    .health(HealthRequest.getDefaultInstance());
                metrics = health.getMetricsMap();
                lastHealth = System.currentTimeMillis();
            }
        } catch (Exception e) {
            synchronized (this) {
                disconnect(generation, "Coletor indisponível: " + e.getClass().getSimpleName());
            }
        }
    }

    private synchronized void connect() throws Exception {
        if (channel == null) {
            NettyChannelBuilder builder;
            if (address.startsWith("unix://")) {
                if (!io.netty.channel.epoll.Epoll.isAvailable()) throw new IllegalStateException(
                    "Unix Domain Socket requires Linux epoll"
                );
                loops = new EpollEventLoopGroup(1);
                builder = NettyChannelBuilder.forAddress(
                    new DomainSocketAddress(address.substring(7))
                )
                    .eventLoopGroup(loops)
                    .channelType(EpollDomainSocketChannel.class)
                    .usePlaintext();
            } else if (address.startsWith("https://")) {
                var uri = java.net.URI.create(address);
                builder = NettyChannelBuilder.forAddress(uri.getHost(), uri.getPort()).sslContext(
                    GrpcSslContexts.forClient()
                        .trustManager(new File(ca))
                        .keyManager(new File(cert), new File(key))
                        .build()
                );
            } else throw new IllegalArgumentException("Use unix:// or https:// with mutual TLS");
            channel = builder.maxInboundMessageSize(4 * 1024 * 1024).build();
        }
        long current = ++generation;
        var headers = new Metadata();
        headers.put(Metadata.Key.of("x-routiva-window", Metadata.ASCII_STRING_MARSHALLER), Integer.toString(sendWindow));
        commands = CollectorGrpc.newStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers)).connect(
            new ClientResponseObserver<Command, Batch>() {
                @Override
                public void beforeStart(ClientCallStreamObserver<Command> request) {
                    request.disableAutoRequestWithInitial(sendWindow);
                    inbound = request;
                }

                @Override
                public void onNext(Batch batch) {
                    if (
                        batch.getProtocolVersion() != 1 ||
                        batch.getResultsCount() > 5000 ||
                        batch.getCollectorId().isBlank() || batch.getCollectorId().length() > 64 ||
                        batch.getBatchId() <= 0
                    ) {
                        synchronized (CollectorClient.this) {
                            disconnect(current, "Lote inválido");
                        }
                        return;
                    }
                    synchronized (CollectorClient.this) {
                        if (current != generation || !connected) return;
                        if (!queue.offer(new Delivery(batch, current, System.nanoTime())))
                            disconnect(current, "Fila de persistência cheia");
                    }
                }

                @Override
                public void onError(Throwable t) {
                    synchronized (CollectorClient.this) {
                        disconnect(current, "Stream interrompido");
                    }
                }

                @Override
                public void onCompleted() {
                    synchronized (CollectorClient.this) {
                        disconnect(current, "Stream encerrado");
                    }
                }
            }
        );
        connected = true;
        sendConfiguration();
    }

    private synchronized void disconnect(long current, String reason) {
        if (current != generation) return;
        connected = false;
        failure = reason;
        generation++;
        if (inbound != null) {
            inbound.cancel(reason, null);
            inbound = null;
        }
        nextConnect =
            System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(
                Math.min(30000, 500L << Math.min(retries++, 6)) +
                    ThreadLocalRandom.current().nextLong(250)
            );
    }

    private void persist() {
        Delivery carry = null;
        while (!closed || carry != null || !queue.isEmpty()) {
            try {
                Delivery first = carry != null ? carry : queue.poll(1, TimeUnit.SECONDS);
                carry = null;
                if (first == null) continue;
                var deliveries = new ArrayList<Delivery>();
                deliveries.add(first);
                int results = first.batch().getResultsCount();
                long groupStarted = System.nanoTime();
                long deadline = groupStarted + TimeUnit.MILLISECONDS.toNanos(groupWaitMs);
                while (deliveries.size() < sendWindow && results < maxGroupResults) {
                    Delivery next = queue.poll();
                    if (next == null && !closed && System.nanoTime() < deadline)
                        next = queue.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (next == null) break;
                    if (results + next.batch().getResultsCount() > maxGroupResults) {
                        carry = next;
                        break;
                    }
                    deliveries.add(next);
                    results += next.batch().getResultsCount();
                }
                long formingNanos = System.nanoTime() - groupStarted;
                List<Batch> batches = deliveries.stream().map(Delivery::batch).toList();
                boolean saved = false;
                while (!saved && !Thread.currentThread().isInterrupted()) {
                    try {
                        long started = System.nanoTime();
                        var write = store.saveBatches(batches);
                        lastDatabaseNanos = System.nanoTime() - started;
                        lastQueueNanos = started - first.receivedAt();
                        lastGroupBatches = batches.size();
                        lastCommitNanos = write.commitNanos;
                        lastLockNanos = write.lockNanos;
                        lastInsertNanos = write.insertNanos;
                        committed += write.results;
                        rejected += write.rejectedResults;
                        committedGroups++;
                        persistenceFailure = false;
                        saved = true;
                        LoggerFactory.getLogger("routiva.indicators").info(
                            "event=RAW_GROUP_COMMITTED batches={} results={} duplicate_batches={} rejected_results={} queue_wait_ms={} group_wait_ms={} lock_ms={} prepare_ms={} insert_ms={} commit_ms={} database_ms={}",
                            batches.size(), write.results, write.duplicateBatches, write.rejectedResults,
                            lastQueueNanos / 1000000d, formingNanos / 1000000d,
                            write.lockNanos / 1000000d, write.prepareNanos / 1000000d,
                            write.insertNanos / 1000000d, write.commitNanos / 1000000d,
                            lastDatabaseNanos / 1000000d);
                    } catch (Exception e) {
                        writeFailures++;
                        persistenceFailure = true;
                        failure = "Falha na persistência";
                        LoggerFactory.getLogger(getClass()).warn(
                            "Falha ao gravar grupo ICMP; mantendo {} lotes para repetição", batches.size(), e);
                        Thread.sleep(1000);
                    }
                }
                synchronized (this) {
                    if (saved && clientPings != null) clientPings.accept(batches);
                    int confirmed = 0;
                    for (var delivery : deliveries) {
                        if (saved && connected && delivery.generation() == generation) {
                            commands.onNext(Command.newBuilder().setProtocolVersion(1)
                                .setAck(Ack.newBuilder().setCollectorId(delivery.batch().getCollectorId())
                                    .setBatchId(delivery.batch().getBatchId())).build());
                            confirmed++;
                        }
                    }
                    if (confirmed > 0) {
                        if (!closed) inbound.request(confirmed);
                        retries = 0;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                synchronized (this) { disconnect(generation, "Falha no consumidor"); }
            }
        }
    }

    @Override
    public Health health() {
        if (!enabled) return Health.unknown().withDetail("enabled", false).build();
        var result =
            connected && !persistenceFailure && metrics.getOrDefault("persistence_healthy", 1d) > 0
                && System.currentTimeMillis() - lastHealth < 10000
                ? Health.up()
                : Health.down();
        return result
            .withDetail("message", metrics.getOrDefault("persistence_healthy", 1d) == 0
                ? "Falha na persistência local do coletor" : connected && !persistenceFailure ? "Conectado" : failure)
            .withDetail("queueBatches", queue.size())
            .withDetail("sendWindow", sendWindow)
            .withDetail("committedGroups", committedGroups)
            .withDetail("lastGroupBatches", lastGroupBatches)
            .withDetail("lastDatabaseMs", lastDatabaseNanos / 1000000d)
            .withDetail("lastCommitMs", lastCommitNanos / 1000000d)
            .withDetail("lastQueueWaitMs", lastQueueNanos / 1000000d)
            .withDetail("lastLockMs", lastLockNanos / 1000000d)
            .withDetail("lastInsertMs", lastInsertNanos / 1000000d)
            .withDetail("committedResults", committed)
            .withDetail("writeFailures", writeFailures)
            .withDetail("rejectedResults", rejected)
            .withDetail("engine", metrics)
            .build();
    }

    @PreDestroy
    public void close() {
        closed = true;
        control.shutdownNow();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
        synchronized (this) {
            disconnect(generation, "Encerramento");
        }
        if (channel != null) channel.shutdownNow();
        if (loops != null) loops.shutdownGracefully();
        if (process != null) process.destroy();
        if (!queue.isEmpty()) LoggerFactory.getLogger(getClass()).warn(
            "Encerramento com {} lotes não confirmados",
            queue.size()
        );
    }
}
