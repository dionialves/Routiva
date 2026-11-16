package br.com.scriptping.client.radio;

import br.com.scriptping.accesspoint.*;
import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.ixc.IxcClient;
import br.com.scriptping.scheduling.ScheduledTask;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Sequential AP assessment. The only automatic producer of persisted radio-client observations. */
@Component
public class RadioClientAssessmentTask implements ScheduledTask {
    private final AccessPointService aps;
    private final LatencyAssessmentService latency;
    private final RadioClientService clients;
    private final int minimum;
    private final long coverage;
    private final Clock clock;
    private final Sleeper sleeper;
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
    @org.springframework.beans.factory.annotation.Autowired
    public RadioClientAssessmentTask(AccessPointService aps, LatencyAssessmentService latency, RadioClientService clients,
        @Value("${radio.history.minimum-readings:2}") int minimum,
        @Value("${radio.history.minimum-duration-ms:10000}") long coverage) {
        this(aps, latency, clients, minimum, coverage, Clock.systemUTC(), Thread::sleep);
    }
    RadioClientAssessmentTask(AccessPointService aps, LatencyAssessmentService latency, RadioClientService clients,
        int minimum, long coverage, Clock clock, Sleeper sleeper) {
        this.aps = aps; this.latency = latency; this.clients = clients; this.minimum = Math.max(2, minimum);
        this.coverage = Math.max(0, coverage); this.clock = clock; this.sleeper = sleeper;
    }
    @Override public String key() { return "radio-client-assessment"; }
    @Override public String label() { return "Avaliação de Cliente Rádio"; }
    @Override public Outcome execute(Map<String, String> parameters) throws Exception {
        return execute(parameters, new Context(UUID.randomUUID().toString(), detail -> {}));
    }
    @Override public Outcome execute(Map<String, String> parameters, Context context) throws Exception {
        validate(parameters);
        if (!latency.ready()) throw new IllegalStateException("Coletor indisponível; nenhuma avaliação iniciada.");
        var inventory = List.copyOf(aps.list());
        if (inventory.isEmpty()) return Outcome.completed("Nenhum AP cadastrado para avaliação.");
        int done = 0, partial = 0, failed = 0;
        for (var ap : inventory) context.report().accept(new Detail(ap.hostId(), ap.name(), "PENDING", "Aguardando processamento."));
        for (var ap : inventory) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            context.report().accept(new Detail(ap.hostId(), ap.name(), "RUNNING", "Coletando clientes e avaliando 30 pacotes por destino."));
            try {
                if (!ap.available()) throw new IllegalStateException("AP indisponível no inventário importado.");
                var result = assess(ap, context.executionId());
                if (result.partial()) partial++; else done++;
                context.report().accept(new Detail(ap.hostId(), ap.name(), result.partial() ? "PARTIAL" : "COMPLETED", result.message()));
            } catch (InterruptedException interrupted) {
                context.report().accept(new Detail(ap.hostId(), ap.name(), "INTERRUPTED", "Processamento interrompido."));
                throw interrupted;
            } catch (Exception error) {
                failed++;
                LoggerFactory.getLogger(getClass()).warn("Radio assessment execution={} ap={} failed", context.executionId(), ap.hostId(), error);
                String message = error instanceof ResponseStatusException status
                    && status.getStatusCode().value() == 409 ? "AP ocupado por outra avaliação; nenhuma medição interrompida."
                    : !ap.available() ? "AP indisponível no inventário importado." : "Falha na consulta, medição ou gravação. Consulte os logs.";
                context.report().accept(new Detail(ap.hostId(), ap.name(), "FAILED", message));
            }
        }
        if (failed == inventory.size()) throw new IllegalStateException("Não foi possível avaliar nenhum AP. Consulte os detalhes por AP.");
        return new Outcome(partial + failed > 0, inventory.size() + " APs: " + done + " concluídos, "
            + partial + " com ressalvas e " + failed + " com falha.");
    }
    private Outcome assess(AccessPointService.Row ap, String executionId) throws InterruptedException {
        String owner = "task:" + executionId;
        latency.reserve(ap.hostId(), owner);
        LatencyAssessmentService.View view = null;
        try {
            var prepared = latency.prepare(ap.hostId());
            if (prepared.targets().size() == 1) return Outcome.completed("Nenhum cliente wireless conectado. Registros anteriores preservados.");
            var samples = new LinkedHashMap<String, RadioCollection.Sample>();
            observe(samples, prepared.snapshot(), prepared.logins());
            long firstAt = prepared.snapshot().measuredAt(), nextRead = clock.millis() + 10000;
            long windowEnd = clock.millis() + Math.max(30000, Math.max(coverage, (minimum - 1L) * 10000));
            view = latency.startPrepared(prepared, 30, owner);
            long deadline = Math.max(view.endsAt(), windowEnd) + 15000;
            boolean readFailure = false;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                long now = clock.millis();
                if (now >= nextRead) {
                    try {
                        var snapshot = aps.read(ap.hostId(), "wireless", null);
                        if (snapshot.warning() != null && !snapshot.warning().isBlank()) readFailure = true;
                        else observe(samples, snapshot, prepared.logins());
                    } catch (ResponseStatusException error) { readFailure = true; }
                    nextRead = clock.millis() + 10000;
                }
                view = latency.read(ap.hostId(), view.id(), owner);
                if ((view.completed() || view.failed()) && clock.millis() >= windowEnd) break;
                if (clock.millis() >= deadline) break;
                sleeper.sleep(1000);
            }
            long touched = clock.millis();
            var captured = List.copyOf(samples.values());
            var measured = latency.measurements(view);
            var batch = new RadioCollection.Batch(UUID.nameUUIDFromBytes((executionId + "/" + ap.hostId()).getBytes(StandardCharsets.UTF_8)).toString(),
                ap.hostId(), ap.name(), owner, firstAt, touched, captured, measured);
            clients.persistCollection(batch, prepared.logins());
            var byMac = new LinkedHashMap<String, List<RadioCollection.Sample>>();
            captured.forEach(sample -> byMac.computeIfAbsent(sample.mac(), key -> new ArrayList<>()).add(sample));
            long eligible = byMac.values().stream().filter(list -> list.size() >= minimum
                && list.getLast().at() - list.getFirst().at() >= coverage).count();
            long noIp = prepared.targets().stream().filter(t -> !t.mac().isBlank() && t.warning() != null).count();
            long noMeasurement = Math.max(0, byMac.size() - measured.size());
            long ambiguous = byMac.values().stream().filter(list -> list.stream().anyMatch(sample -> sample.observedLogins().size() > 1)).count();
            boolean partial = !prepared.ixcAvailable() || readFailure || view.failed() || !view.completed()
                || eligible < byMac.size() || noMeasurement > 0 || ambiguous > 0
                || view.results().stream().anyMatch(result -> result.target().warning() == null
                    && result.samples().stream().filter(point -> point.loss() != null).count() < 30)
                || prepared.targets().stream().anyMatch(t -> t.warning() != null);
            return new Outcome(partial, byMac.size() + " clientes observados; " + eligible + " com coleta histórica válida; "
                + measured.size() + " com medição ICMP; " + noIp + " sem IP válido; " + noMeasurement + " sem medição; " + ambiguous + " com identidade ambígua."
                + (!prepared.ixcAvailable() ? " Consulta ao IXC indisponível; vínculos existentes preservados." : "")
                + (readFailure ? " Houve falha em leituras wireless adicionais." : "")
                + (view.failed() || !view.completed() ? " Avaliação de latência incompleta." : ""));
        } finally {
            try { if (view != null) latency.stop(ap.hostId(), view.id(), owner); }
            finally { latency.release(ap.hostId(), owner); }
        }
    }
    private void observe(Map<String, RadioCollection.Sample> samples, EquipmentProvider.Snapshot snapshot, List<IxcClient.Login> logins) {
        for (var row : snapshot.rows()) {
            if ("disconnected".equals(row.get("connection-status"))) continue;
            String mac;
            try { mac = IxcClient.normalizeMac(row.get("mac-address")); }
            catch (ResponseStatusException invalid) { continue; }
            var matches = logins.stream().filter(login -> mac.equals(login.mac)).toList();
            var names = matches.stream().map(login -> login.pppoe.strip()).filter(name -> !name.isBlank()).distinct().toList();
            var ips = matches.stream().map(login -> login.ip.strip()).filter(br.com.scriptping.IpAddress::isValid).distinct().toList();
            var raw = new LinkedHashMap<>(row);
            if (ips.size() == 1) raw.put("ip-address", ips.getFirst());
            samples.putIfAbsent(mac + "/" + snapshot.measuredAt(), new RadioCollection.Sample(snapshot.measuredAt(), mac,
                Collections.unmodifiableMap(raw), List.copyOf(names)));
        }
    }
}
