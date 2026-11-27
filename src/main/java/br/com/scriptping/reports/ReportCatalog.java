package br.com.scriptping.reports;

import java.util.Comparator;
import java.util.List;
import org.springframework.security.core.Authentication;

final class ReportCatalog {
    private static final Category BACKUP = new Category("backup", "Backup", "download",
        "Acompanhe o armazenamento e a retenção dos backups dos equipamentos.");

    static final Report BACKUP_SUMMARY = new Report("resumo-backup", "Resumo de backup",
        "Retenção, quantidade de cópias armazenadas e último backup dos hosts.",
        BACKUP, "file", "backup-summary-dialog", "backupReportDialog", "ROLE_ADMIN", 10);

    private static final List<Report> REPORTS = List.of(BACKUP_SUMMARY);

    private ReportCatalog() {}

    record Category(String id, String name, String icon, String description) {}

    record Report(String id, String name, String description, Category category, String icon,
                  String dialogId, String dialogFragment, String authority, int order) {
        boolean permitted(Authentication authentication) {
            return authority == null || (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority())));
        }
    }

    record Group(Category category, List<Report> reports) {}

    static List<Group> available(Authentication authentication) {
        var reports = REPORTS.stream().filter(report -> report.permitted(authentication))
            .sorted(Comparator.comparingInt(Report::order)).toList();
        return reports.stream().map(Report::category).distinct()
            .map(category -> new Group(category, reports.stream()
                .filter(report -> report.category().equals(category)).toList())).toList();
    }
}
