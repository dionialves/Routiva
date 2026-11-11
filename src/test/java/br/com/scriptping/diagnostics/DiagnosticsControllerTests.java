package br.com.scriptping.diagnostics;

import br.com.scriptping.zabbix.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DiagnosticsControllerTests {
    private EmbeddedDatabase db;
    private NamedParameterJdbcTemplate jdbc;
    private MockMvc mvc;
    private final ZabbixService inventory = mock(ZabbixService.class);
    private final ZabbixClient client = mock(ZabbixClient.class);

    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new NamedParameterJdbcTemplate(db);
        jdbc.getJdbcTemplate().execute("CREATE TABLE ping_sample(id BIGINT,host_id VARCHAR,target VARCHAR,measured_at BIGINT,latency DOUBLE,error VARCHAR)");
        when(inventory.hosts()).thenReturn(List.of(new Host("h", "Router", "10.0.0.1", List.of(new Host.Group("g", "POP")))));
        mvc = MockMvcBuilders.standaloneSetup(new DiagnosticsController(inventory, client, jdbc)).build();
    }
    @AfterEach void close() { db.shutdown(); }

    private void sample(long id, String target, long age, Double latency, String error) {
        jdbc.getJdbcTemplate().update("INSERT INTO ping_sample VALUES(?,?,?,?,?,?)", id,"h",target,System.currentTimeMillis()-age,latency,error);
    }
    @Test void acknowledgedHostAndIncidentRemainVisibleWithoutLiveMeasurements() throws Exception {
        when(inventory.hosts()).thenReturn(List.of(new Host("h", "Router", "10.0.0.1",
            List.of(new Host.Group("g", "POP")), true)));
        when(client.activeIncidents(List.of("h"))).thenReturn(Map.of("h", List.of(
            new ZabbixClient.Incident("Incidente reconhecido", 4))));
        sample(1, "10.0.0.1", 0, 5d, null);
        mvc.perform(get("/api/diagnostics/groups/g/details")).andExpect(status().isOk())
            .andExpect(jsonPath("$.hosts[0].id").value("h"))
            .andExpect(jsonPath("$.incidents.h").value(1));
        mvc.perform(get("/api/diagnostics/groups/g/live")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].state").value("Não monitorado — incidente reconhecido"))
            .andExpect(jsonPath("$[0].ping").isEmpty())
            .andExpect(jsonPath("$[0].loss").isEmpty());
    }

    @Test void liveUsesCurrentIpAndExcludesInternalErrorsFromLoss() throws Exception {
        sample(1,"10.0.0.1",3000,null,"Tempo esgotado");
        sample(2,"10.0.0.1",2000,null,"Erro interno");
        sample(3,"10.0.0.1",1000,4d,null);
        sample(4,"10.0.0.2",0,100d,null);
        mvc.perform(get("/api/diagnostics/groups/g/live")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$[0].ping").value(4d))
            .andExpect(jsonPath("$[0].loss").value(50d))
            .andExpect(jsonPath("$[0].state").value("Respondendo"));
    }
    @Test void liveAveragesSincePageOpenedAndResetExcludesEarlierSamples() throws Exception {
        long opened = System.currentTimeMillis() - 120000;
        sample(1,"10.0.0.1",130000,100d,null);
        sample(2,"10.0.0.1",90000,10d,null);
        sample(3,"10.0.0.1",80000,null,"Tempo esgotado");
        sample(4,"10.0.0.1",70000,null,"Erro interno");
        sample(5,"10.0.0.1",1000,30d,null);
        sample(6,"10.0.0.2",0,200d,null);
        for (int poll = 0; poll < 2; poll++) {
            mvc.perform(get("/api/diagnostics/groups/g/live").param("since", String.valueOf(opened)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ping").value(20d))
                .andExpect(jsonPath("$[0].loss").value(100d / 3));
        }
        mvc.perform(get("/api/diagnostics/groups/g/live")
                .param("since", String.valueOf(System.currentTimeMillis() - 10000)))
            .andExpect(jsonPath("$[0].ping").value(30d))
            .andExpect(jsonPath("$[0].loss").value(0d));
        mvc.perform(get("/api/diagnostics/groups/g/live")
                .param("since", String.valueOf(System.currentTimeMillis())))
            .andExpect(jsonPath("$[0].ping").isEmpty())
            .andExpect(jsonPath("$[0].loss").isEmpty());
    }
    @Test void staleSamplesDoNotAppearAsLive() throws Exception {
        sample(1,"10.0.0.1",20000,5d,null);
        mvc.perform(get("/api/diagnostics/groups/g/live")).andExpect(jsonPath("$[0].ping").isEmpty())
            .andExpect(jsonPath("$[0].state").value("Coleta atrasada"));
    }
    @Test void unknownGroupNeverQueriesZabbix() throws Exception {
        mvc.perform(get("/api/diagnostics/groups/other/details")).andExpect(status().isNotFound());
        verifyNoInteractions(client);
    }
    @Test void detailsCountsIncidentsBySeverityPerHost() throws Exception {
        when(client.activeIncidents(List.of("h"))).thenReturn(Map.of("h", List.of(
            new ZabbixClient.Incident("A", 4), new ZabbixClient.Incident("B", 2),
            new ZabbixClient.Incident("C", 4))));
        mvc.perform(get("/api/diagnostics/groups/g/details")).andExpect(status().isOk())
            .andExpect(jsonPath("$.incidents.h").value(3))
            .andExpect(jsonPath("$.incidentSeverities.h.4").value(2))
            .andExpect(jsonPath("$.incidentSeverities.h.2").value(1))
            .andExpect(jsonPath("$.incidentSeverities.h.5").doesNotExist());
    }
    @Test void metadataFailureDoesNotHideHostsOrInventIncidentCounts() throws Exception {
        when(client.diagnosticInventory(List.of("h"))).thenThrow(new IllegalStateException());
        when(client.activeIncidents(List.of("h"))).thenThrow(new IllegalStateException());
        mvc.perform(get("/api/diagnostics/groups/g/details")).andExpect(status().isOk())
            .andExpect(jsonPath("$.hosts[0].id").value("h"))
            .andExpect(jsonPath("$.incidents.h").doesNotExist())
            .andExpect(jsonPath("$.warning").isNotEmpty())
            .andExpect(jsonPath("$.limits.latency").value(20d))
            .andExpect(jsonPath("$.limits.lossCritical").value(1d));
    }
}
