package br.com.scriptping;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardBehaviorTests {
    private AvailabilityDashboardController controller(List<AvailabilityDashboard.Row> rows) {
        var dashboard = mock(AvailabilityDashboard.class);
        when(dashboard.query(5, "")).thenReturn(new AvailabilityDashboard.Result(List.of(), new AvailabilityDashboard.View(null, rows)));
        return new AvailabilityDashboardController(dashboard);
    }

    private AvailabilityDashboard.Row row(String id, String name, Double value, String status, boolean fresh, boolean healthy) {
        var metrics = new HostAvailabilityService.Metrics(0, 0, 0, value, value, value, value, value, 0);
        return new AvailabilityDashboard.Row(id, name, metrics, "10.0.0.1", List.of(), status, fresh, healthy);
    }

    @ParameterizedTest
    @ValueSource(strings = {"operational", "technical", "loss", "latency"})
    void numericSortingKeepsMissingValuesLastInBothDirectionsAndBreaksTiesById(String sort) {
        var controller = controller(List.of(
            row("missing", "Missing", null, "SEM_DADOS", false, false),
            row("b", "B", 10d, "OK", true, true),
            row("a", "A", 10d, "OK", true, true),
            row("zero", "Zero", 0d, "OK", true, true)));
        assertThat(controller.dashboard(5, "", "", sort, "asc", 1).getBody().view().hosts())
            .extracting(AvailabilityDashboard.Row::id).containsExactly("zero", "a", "b", "missing");
        assertThat(controller.dashboard(5, "", "", sort, "desc", 1).getBody().view().hosts())
            .extracting(AvailabilityDashboard.Row::id).containsExactly("a", "b", "zero", "missing");
    }

    @ParameterizedTest
    @CsvSource({"SEM_DADOS,false,false,SEM_DADOS", "SEM_IP,false,false,SEM_DADOS", "OK,true,true,RECEBENDO", "OK,false,true,PARCIAL", "OK,true,false,PARCIAL"})
    void collectionStatusDistinguishesNoDataFromStaleOrUnhealthyCollection(String status, boolean fresh, boolean healthy, String expected) {
        var response = controller(List.of(row("a", "A", null, status, fresh, healthy)))
            .dashboard(5, "", "", "name", "asc", 1).getBody();
        assertThat(response.collectionStatus()).isEqualTo(expected);
    }

    @Test
    void searchNormalizesAccentsAndClampsNegativePages() {
        var response = controller(List.of(row("a", "Produção", 10d, "OK", true, true)))
            .dashboard(5, "", "  PRODUCAO  ", "name", "asc", -10).getBody();
        assertThat(response.view().hosts()).extracting(AvailabilityDashboard.Row::id).containsExactly("a");
        assertThat(response.page().number()).isEqualTo(1);
    }

    @Test
    void emptySearchResultDoesNotChangeCollectionStatusOrSelectionTotal() {
        var response = controller(List.of(row("a", "A", 10d, "OK", true, true)))
            .dashboard(5, "", "missing", "name", "asc", Integer.MAX_VALUE).getBody();
        assertThat(response.page()).isEqualTo(new AvailabilityDashboardController.Page(1, 1, 0, 1, 20));
        assertThat(response.view().hosts()).isEmpty();
        assertThat(response.collectionStatus()).isEqualTo("RECEBENDO");
    }

    @Test
    void emptyInventoryIsDifferentFromLoading() {
        assertThat(controller(List.of()).dashboard(5, "", "", "name", "asc", 1).getBody().collectionStatus()).isEqualTo("SEM_HOSTS");
        var dashboard = mock(AvailabilityDashboard.class);
        when(dashboard.query(5, "")).thenReturn(new AvailabilityDashboard.Result(List.of(), null));
        var response = new AvailabilityDashboardController(dashboard).dashboard(5, "", "", "name", "asc", 1);
        assertThat(response.getBody().collectionStatus()).isEqualTo("CARREGANDO");
        assertThat(response.getBody().view()).isNull();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
