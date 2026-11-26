package br.com.scriptping.backup;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BackupController.class)
@WithMockUser(roles = "ADMIN")
class BackupPageTests {
    @Autowired MockMvc mvc;
    @MockitoBean BackupStore store;
    @MockitoBean BackupJobs jobs;

    @Test
    void rendersSharedDialogsAndTablesOnce() throws Exception {
        String html = mvc.perform(get("/backup")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String id : List.of("backup-settings-dialog", "backup-settings-form", "backup-text-dialog", "backup-run-dialog", "backup-run-form", "backup-run-host", "backup-run-events", "backup-rows", "backup-host-rows")) {
            assertThat(html.split("id=\"" + id + "\"", -1)).hasSize(2);
        }
    }

    @Test
    void startsManualBackupForSelectedHost() throws Exception {
        when(jobs.start("42")).thenReturn(new BackupJobs.Status(true, 1, 0, 0, List.of(), "manual", List.of(new BackupJobs.Event(1000L, "Iniciando backup"))));
        mvc.perform(post("/api/backups/run").with(csrf()).contentType("application/json").content("{\"hostId\":\"42\"}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.events[0].timestamp").value(1000))
            .andExpect(jsonPath("$.events[0].message").value("Iniciando backup"));
        verify(jobs).start("42");
        mvc.perform(post("/api/backups/run").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(jobs);
    }

    @Test
    void attachesBinaryFileAndRejectsItsTextPreview() throws Exception {
        var entry = new BackupStore.Entry("a", "1", "OLT", "Digistar", "olt.tar", false, 1);
        byte[] bytes = {0, 12, (byte) 0xff};
        when(store.file("a")).thenReturn(new BackupStore.File(entry, bytes));
        mvc.perform(get("/api/backups/a/download")).andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"olt.tar\""))
            .andExpect(content().bytes(bytes));
        mvc.perform(get("/api/backups/a/text")).andExpect(status().isUnsupportedMediaType());
    }
}
