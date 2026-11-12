package br.com.scriptping.incidents;

import br.com.scriptping.zabbix.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IncidentsControllerTests {
    private final ZabbixService inventory = mock(ZabbixService.class);
    private final ZabbixClient client = mock(ZabbixClient.class);
    private final IncidentsController controller = new IncidentsController(inventory, client);
    private final Host a = new Host("1", "Router A", "10.0.0.1", List.of(new Host.Group("g", "POP")));
    private final Host b = new Host("2", "Router B", "10.0.0.2", a.groups());

    private final org.springframework.security.core.Authentication admin =
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("alice", "", List.of(
            new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));

    private void incident(boolean writable, boolean manualClose, boolean closed) {
        when(inventory.hosts()).thenReturn(List.of(a));
        when(client.incidentDetails("99", List.of("1"))).thenReturn(new ZabbixClient.IncidentDetails(
            "99", "Link", 3, false, closed, false, writable, manualClose, List.of()));
    }

    @Test void combinesActionsAndRecordsLocalActor() {
        incident(true, true, false);
        controller.update("99", new IncidentsController.UpdateRequest("Investigando", true, true, 4), admin);
        verify(client).updateIncident("99", 15, "alice: Investigando", 4);
    }

    @Test void recordsReadablePrincipalInsteadOfItsJavaRepresentation() {
        incident(true, true, false);
        var principal = new br.com.scriptping.security.UserPrincipal(1L, "admin", "Maria Silva", 0);
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            principal, "", admin.getAuthorities());
        controller.update("99", new IncidentsController.UpdateRequest("Observação", false, false, 3), auth);
        verify(client).updateIncident("99", 4, "admin: Observação", null);
    }

    @Test void acceptsMessageWithoutOtherActions() {
        incident(false, false, false);
        controller.update("99", new IncidentsController.UpdateRequest("Observação", false, false, null), admin);
        verify(client).updateIncident("99", 4, "alice: Observação", null);
    }

    @Test void rejectsUnavailableCloseAndClosedEvents() {
        incident(true, false, false);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.update("99", new IncidentsController.UpdateRequest("", false, true, null), admin));
        incident(true, true, true);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.update("99", new IncidentsController.UpdateRequest("Mensagem", false, false, null), admin));
        verify(client, never()).updateIncident(anyString(), anyInt(), anyString(), any());
    }

    @Test void rejectsEmptyUpdateAndUnauthorizedUsers() {
        incident(true, true, false);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.update("99", new IncidentsController.UpdateRequest(" ", false, false, 3), admin));
        var user = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bob", "", List.of());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
            controller.update("99", new IncidentsController.UpdateRequest("Mensagem", false, false, null), user));
        verify(client, never()).updateIncident(anyString(), anyInt(), anyString(), any());
    }

    @Test void rejectsEventsOutsideInventory() {
        when(inventory.hosts()).thenReturn(List.of(a));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.details("99"));
        verify(client).incidentDetails("99", List.of("1"));
    }

    @Test void acceptsQueryFiltersInRequestBody() {
        when(inventory.hosts()).thenReturn(List.of(a, b));
        when(client.activeIncidents(List.of("1"))).thenReturn(Map.of("1", List.of(
            new ZabbixClient.Incident("Link", 4, "99", 1000))));
        var response = controller.groupedData(new IncidentsController.QueryRequest("g", "1", "4"));
        assertEquals(1, response.getBody().total());
        assertEquals("99", response.getBody().rows().getFirst().eventId());
        verify(client).activeIncidents(List.of("1"));
    }

    @Test void deduplicatesSharedEventsAndRendersTemplate() {
        when(inventory.hosts()).thenReturn(List.of(a, b));
        var event = new ZabbixClient.Incident("Link <down>", 4, "99", 1000, true, true, 3);
        when(client.activeIncidents(List.of("1", "2"))).thenReturn(Map.of("1", List.of(event), "2", List.of(event)));
        var model = new ExtendedModelMap();
        controller.page("g", "", "", "4", model, new MockHttpServletResponse());
        var rows = controller.incidents("g", "", "", "4", "date", "desc", 1, 10).getBody().rows();
        assertEquals(1, rows.size());
        assertTrue(rows.getFirst().acknowledged());
        assertEquals(3, rows.getFirst().messageCount());
        assertEquals(2, ((IncidentsController.Row) rows.getFirst()).hosts().size());
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode("HTML");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var servletContext = new MockServletContext();
        var request = new MockHttpServletRequest(servletContext);
        var exchange = JakartaServletWebApplication.buildApplication(servletContext)
            .buildExchange(request, new MockHttpServletResponse());
        var context = new WebContext(exchange, Locale.forLanguageTag("pt-BR"), model);
        String html = engine.process("incidents", context);
        assertTrue(html.contains("incident-rows"));
        assertTrue(html.contains("Rec.</th>"));
        assertTrue(html.contains("Router A"));
        assertTrue(html.contains("incident-group-search"));
    }

    @Test void filtersBeforeQueryAndDoesNotBroadenUnknownSelections() {
        when(inventory.hosts()).thenReturn(List.of(a, b));
        when(client.activeIncidents(List.of("1"))).thenReturn(Map.of("1", List.of(new ZabbixClient.Incident("Link", 2, "9", 1000))));
        var model = new ExtendedModelMap();
        controller.page("g", "1", "missing", "", model, new MockHttpServletResponse());
        assertTrue(controller.incidents("g", "1", "missing", "", "date", "desc", 1, 10).getBody().rows().isEmpty());
        verify(client).activeIncidents(List.of("1"));
        when(client.activeIncidents(List.of())).thenReturn(Map.of());
        controller.incidents("unknown", "", "", "", "date", "desc", 1, 10);
        verify(client).activeIncidents(List.of());
    }

    @Test void paginatesUniqueEventsSortsDurationsAndSearchesIp() {
        when(inventory.hosts()).thenReturn(List.of(a, b));
        var events = new ArrayList<ZabbixClient.Incident>();
        for (int i = 1; i <= 32; i++) events.add(new ZabbixClient.Incident("Same description", i % 6, "e" + i, i * 1000));
        when(client.activeIncidents(List.of("1", "2"))).thenReturn(Map.of("1", events, "2", events));
        var page = controller.incidents("g", "", "10.0.0.2", "", "date", "desc", 2, 10).getBody();
        assertEquals(32, page.total());
        assertEquals(4, page.pages());
        assertEquals(10, page.rows().size());
        assertEquals(22000, page.rows().getFirst().timestamp());
        var duration = controller.incidents("g", "", "", "", "duration", "desc", 1, 10).getBody();
        assertEquals(1000, duration.rows().getFirst().timestamp());
        var last = controller.incidents("g", "", "", "", "date", "asc", 999, 10).getBody();
        assertEquals(4, last.page()); assertEquals(2, last.rows().size());
        var filtered = controller.incidents("g", "", "", "5", "date", "desc", 1, 25).getBody();
        assertEquals(5, filtered.total());
        assertTrue(filtered.rows().stream().allMatch(r -> r.severity() == 5));
        var multiple = controller.incidents("g", "", "", "2,5", "date", "desc", 1, 50).getBody();
        assertEquals(11, multiple.total());
        assertTrue(multiple.rows().stream().allMatch(r -> Set.of(2, 5).contains(r.severity())));
        assertEquals(0, controller.incidents("g", "", "", "none", "date", "desc", 1, 50).getBody().total());
    }

    @Test void combinesMultipleGroupsAndHostsWithoutBroadeningSelection() {
        var c = new Host("3", "Other", "10.0.1.1", List.of(new Host.Group("other", "Other")));
        when(inventory.hosts()).thenReturn(List.of(a, b, c));
        when(client.activeIncidents(List.of("1", "3"))).thenReturn(Map.of("1", List.of(), "3", List.of()));
        assertEquals(0, controller.incidents("g,other", "1,3", "", "", "date", "desc", 1, 50).getBody().total());
        verify(client).activeIncidents(List.of("1", "3"));
        when(client.activeIncidents(List.of("1"))).thenReturn(Map.of("1", List.of()));
        controller.incidents("g", "1,3", "", "", "date", "desc", 1, 50);
        verify(client).activeIncidents(List.of("1"));
    }

    @Test void partialHostsAreExplicit() {
        when(inventory.hosts()).thenReturn(List.of(a, b));
        when(client.activeIncidents(List.of("1", "2"))).thenReturn(Map.of("1", List.of()));
        assertFalse(controller.incidents("", "", "", "", "date", "desc", 1, 10).getBody().warning().isEmpty());
    }

    @Test void remoteFailureIsVisibleInsteadOfReportingNoIncidents() {
        when(inventory.hosts()).thenReturn(List.of(a));
        when(client.activeIncidents(List.of("1"))).thenThrow(new IllegalStateException("secret"));
        var model = new ExtendedModelMap();
        var response = controller.incidents("", "", "", "", "date", "desc", 1, 10);
        assertEquals(502, response.getStatusCode().value());
        assertTrue(response.getBody().warning().contains("Não foi possível"));
        assertFalse(response.getBody().warning().contains("secret"));
    }
    @Test void groupedSnapshotIncludesEveryFilteredEventBeyondGlobalPageLimit() {
        when(inventory.hosts()).thenReturn(List.of(a));
        var events = new ArrayList<ZabbixClient.Incident>();
        for (int i = 0; i < 151; i++) {
            events.add(new ZabbixClient.Incident("Link", 2, "e" + i, 1000L + i));
        }
        when(client.activeIncidents(List.of("1"))).thenReturn(Map.of("1", events));
        var response = controller.groupedData("g", "1", "2").getBody();
        assertEquals(151, response.total());
        assertEquals(151, response.rows().size());
        assertEquals(1150, response.rows().getFirst().timestamp());
        assertEquals(1, response.pages());
        verify(client).activeIncidents(List.of("1"));
    }
}
