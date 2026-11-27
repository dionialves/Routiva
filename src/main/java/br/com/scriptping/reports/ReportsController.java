package br.com.scriptping.reports;

import br.com.scriptping.hosts.HostRepository;
import br.com.scriptping.security.UserPrincipal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class ReportsController {
    private final HostRepository hosts;
    private final ZoneId zone;

    ReportsController(HostRepository hosts, @Value("${spring.jackson.time-zone:America/Sao_Paulo}") String zone) {
        this.hosts = hosts;
        this.zone = ZoneId.of(zone);
    }

    record Row(String name, int retention, long saved, Long lastBackup, String lastBackupLabel) {}

    record Summary(List<Row> rows, long saved, long withoutBackup) {}

    @GetMapping("/relatorios")
    String page(Model model, Authentication authentication) {
        model.addAttribute("reportGroups", ReportCatalog.available(authentication));
        if (ReportCatalog.BACKUP_SUMMARY.permitted(authentication)) {
            model.addAttribute("summary", summary());
        }
        return "reports";
    }

    @GetMapping(value = "/relatorios/resumo-backup/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> download(Authentication authentication) {
        if (!ReportCatalog.BACKUP_SUMMARY.permitted(authentication)) {
            throw new AccessDeniedException("Acesso não permitido.");
        }
        String user = authentication.getPrincipal() instanceof UserPrincipal principal
            ? principal.name() : authentication.getName();
        var issuance = new ReportPdf.Issuance(Instant.now(), zone, user);
        var pdf = BackupSummaryPdf.render(summary(), issuance);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(pdf.filename()).build().toString())
            .body(pdf.content());
    }

    private Summary summary() {
        var rows = hosts.backupSummary().stream().map(host -> new Row(host.name(), host.retention(),
            host.saved(), host.lastBackup(), host.lastBackup() == null ? "Sem backup"
                : ReportPdf.date(Instant.ofEpochMilli(host.lastBackup()), zone))).toList();
        return new Summary(rows, rows.stream().mapToLong(Row::saved).sum(),
            rows.stream().filter(host -> host.saved() == 0).count());
    }
}
