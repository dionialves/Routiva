package br.com.scriptping.scheduling;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@WebMvcTest(ScheduleController.class)
@WithMockUser(roles = "ADMIN")
class SchedulePageTests {
    @Autowired MockMvc mvc;
    @MockitoBean ScheduleStore store;
    @MockitoBean TaskCatalog catalog;
    @MockitoBean ScheduleEngine engine;
    @Test void rendersSharedComponentsOnceAndExplainsEmptyCatalog() throws Exception {
        when(catalog.choices()).thenReturn(List.of());
        String html = mvc.perform(get("/configuracao/agendador")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("Agendador de tarefas", "Nenhuma tarefa disponível", "hosts-table", "analysis-dialog", "analysis-tabs", "analysis-body", "analysis-footer", "America/Sao_Paulo");
        for (String id : List.of("schedule-dialog", "schedule-form", "schedule-rows", "execution-rows", "delete-schedule-dialog", "schedule-data", "schedule-history"))
            assertThat(html.split("id=\"" + id + "\"", -1)).hasSize(2);
        assertThat(html).contains("Executar agora");
        assertThat(html).doesNotContain("name=\"name\"", "Modo avançado", "Expressão cron", "<th scope=\"col\">Ações</th>");
        assertThat(html).contains("role=\"tablist\"", "aria-controls=\"schedule-history\"", "Dados do agendamento");
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/scheduler-page.html"), html);
    }
    @Test void previewReturnsFiveFutureExecutionsAndRejectsInvalidRules() throws Exception {
        mvc.perform(post("/configuracao/agendador/preview").with(csrf()).contentType("application/json")
            .content("{\"frequency\":\"DAILY\",\"time\":\"02:00\",\"zone\":\"America/Sao_Paulo\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.times.length()").value(5));
        mvc.perform(post("/configuracao/agendador/preview").with(csrf()).contentType("application/json")
            .content("{\"frequency\":\"CRON\",\"zone\":\"UTC\"}"))
            .andExpect(status().isBadRequest());
    }
    @Test void historyIsScopedToSelectedScheduleAndClampsPagination() throws Exception {
        when(store.historyCount("selected")).thenReturn(51L);
        when(store.history("selected", 2)).thenReturn(List.of());
        mvc.perform(get("/configuracao/agendador/api/selected/history?page=999"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2))
            .andExpect(jsonPath("$.total").value(51));
        verify(store).history("selected", 2);
        verify(store, never()).history(anyInt());
        mvc.perform(get("/configuracao/agendador/api"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.history").doesNotExist());
    }
    @Test void scheduleCanBeSavedWithoutNameAndDuplicateActivityReturnsConflict() throws Exception {
        var task = mock(ScheduledTask.class); when(task.key()).thenReturn("test"); when(task.label()).thenReturn("Atividade");
        when(catalog.require("test")).thenReturn(task);
        String input = "{\"taskKey\":\"test\",\"rule\":{\"frequency\":\"DAILY\",\"time\":\"02:00\",\"zone\":\"UTC\"}}";
        mvc.perform(post("/configuracao/agendador/api").with(csrf()).contentType("application/json").content(input))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").isNotEmpty());
        verify(store).save(argThat(e -> e.name().equals("Atividade")), eq(true), anyLong());
        doThrow(new org.springframework.dao.DuplicateKeyException("duplicate")).when(store).save(any(), anyBoolean(), anyLong());
        mvc.perform(post("/configuracao/agendador/api").with(csrf()).contentType("application/json").content(input))
            .andExpect(status().isConflict());
    }
    @Test void manualExecutionReturnsAcceptedAndRequiresCsrf() throws Exception {
        when(engine.runNow("one")).thenReturn("execution");
        mvc.perform(post("/configuracao/agendador/api/one/run").with(csrf()))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.executionId").value("execution"));
        mvc.perform(post("/configuracao/agendador/api/one/run")).andExpect(status().isForbidden());
        verify(engine, times(1)).runNow("one");
    }
    @Test void mutationsRequireCsrf() throws Exception {
        mvc.perform(post("/configuracao/agendador/api/id/delete")).andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }
    @Test void cannotSaveUnknownTask() throws Exception {
        when(catalog.require("missing")).thenThrow(new IllegalArgumentException("Tarefa indisponível."));
        mvc.perform(post("/configuracao/agendador/api").with(csrf()).contentType("application/json")
            .content("{\"name\":\"Teste\",\"taskKey\":\"missing\",\"rule\":{\"frequency\":\"DAILY\",\"time\":\"02:00\",\"zone\":\"UTC\"}}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
}
