package br.com.scriptping.reports;

import br.com.scriptping.hosts.HostRepository;
import br.com.scriptping.security.SecurityConfiguration;
import br.com.scriptping.users.UserRepository;
import br.com.scriptping.users.UserService;
import java.nio.charset.Charset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReportsController.class)
@Import(SecurityConfiguration.class)
@WithMockUser(roles = "ADMIN")
class ReportsPageTests {
    @Autowired MockMvc mvc;
    @MockitoBean HostRepository hosts;
    @MockitoBean UserRepository users;
    @MockitoBean UserService userService;

    @Test
    @WithMockUser(roles = "USER")
    void protectsReportAndDownloadWithBackupPermissions() throws Exception {
        mvc.perform(get("/relatorios")).andExpect(status().isForbidden());
        mvc.perform(get("/relatorios/resumo-backup/pdf")).andExpect(status().isForbidden());
    }

    @Test
    void rendersSummaryIncludingHostsWithoutCopies() throws Exception {
        when(hosts.backupSummary()).thenReturn(List.of(
            new HostRepository.BackupSummary("Roteador 01", 5, 0, null),
            new HostRepository.BackupSummary("Roteador 02", 7, 2, 10800000L)));
        String html = mvc.perform(get("/relatorios")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("id=\"backup-summary-dialog\"", "Resumo de backup", "Roteador 01",
            "Sem backup", "data-tone=\"missing\"", "Roteador 02", "01/01/1970 00:00:00", "Baixar relatório");
    }

    @Test
    void rendersTheCatalogWithCategoryDescriptionAndOneDialog() throws Exception {
        String html = mvc.perform(get("/relatorios")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(html).contains("Consulte e exporte informações operacionais e gerenciais da sua rede.",
                "Acompanhe o armazenamento e a retenção dos backups dos equipamentos.",
                "data-report-id=\"resumo-backup\"", "aria-controls=\"backup-summary-dialog\"",
                "Retenção, quantidade de cópias armazenadas e último backup dos hosts.")
            .doesNotContain("Relatórios disponíveis", "report-rows", "reports-pagination",
                "report-search", "data-report-count");
        assertThat(html.split("id=\"backup-summary-dialog\"", -1)).hasSize(2);
    }

    @Test
    void rendersNeutralIndicatorWhenAllEnabledHostsHaveBackup() throws Exception {
        when(hosts.backupSummary()).thenReturn(List.of(
            new HostRepository.BackupSummary("Roteador 01", 5, 1, 10800000L)));
        String html = mvc.perform(get("/relatorios")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("app-modal-adaptive", "aria-describedby=\"backup-summary-description\"",
                "data-tone=\"neutral\"", "id=\"backup-summary-download\"")
            .doesNotContain("data-tone=\"missing\"");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 3, 250, 1250})
    void downloadsAllRowsWithRepeatedHeadersAndOneTotal(int count) throws Exception {
        String longName = "Estação (São João) \\ " + "Equipamento em operação ".repeat(10) + "Fim-da-linha";
        when(hosts.backupSummary()).thenReturn(IntStream.rangeClosed(1, count)
            .mapToObj(index -> new HostRepository.BackupSummary(index == 1 ? longName : "Estação " + index,
                5, index == 1 ? 0 : 1, index == 1 ? null : 10800000L)).toList());
        var response = mvc.perform(get("/relatorios/resumo-backup/pdf")).andExpect(status().isOk())
            .andExpect(content().contentType("application/pdf")).andReturn().getResponse();

        assertThat(response.getHeader("Content-Disposition"))
            .matches("attachment; filename=\"resumo-backup_[0-9-]{10}_[0-9-]{8}\\.pdf\"");
        String pdf = new String(response.getContentAsByteArray(), Charset.forName("windows-1252"));
        var pageCount = java.util.regex.Pattern.compile("/Count (\\d+)").matcher(pdf);
        assertThat(pageCount.find()).isTrue();
        int pages = Integer.parseInt(pageCount.group(1));
        assertThat(pages > 1).isEqualTo(count >= 250);
        assertThat(pdf).startsWith("%PDF-1.4").contains("/BaseFont /Helvetica", "/Subtype /Image", "(Usuário: user)")
            .doesNotContain("/Courier");
        assertThat(pdf.split("\\(Último backup\\)", -1)).hasSize(pages + 1);
        assertThat(pdf.split("\\(Emissão: ", -1)).hasSize(pages + 1);
        assertThat(pdf.split("\\(Hosts habilitados\\)", -1)).hasSize(2);
        assertThat(pdf.split("\\(Total de hosts: ", -1)).hasSize(2);
        assertThat(pdf).contains("(Página " + pages + " de " + pages + ")", "(Total de hosts: " + ReportPdf.number(count) + ")");
        if (count == 0) assertThat(pdf).contains("(Nenhum host habilitado para backup.)");
        if (count > 0) assertThat(pdf).contains("São João", "Fim-da-linha", "(–)");
        if (count > 1) assertThat(pdf).contains("(01/01/1970 00:00:00)");
        for (int index = 2; index <= count; index++) assertThat(pdf).contains("(Estação " + index + ")");
    }
}
