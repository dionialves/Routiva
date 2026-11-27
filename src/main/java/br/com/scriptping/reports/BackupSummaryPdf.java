package br.com.scriptping.reports;

import java.util.List;
import static br.com.scriptping.reports.ReportPdf.Alignment.*;

final class BackupSummaryPdf {
    private BackupSummaryPdf() {}

    static ReportPdf.Document render(ReportsController.Summary summary, ReportPdf.Issuance issuance) {
        var report = ReportCatalog.BACKUP_SUMMARY;
        var definition = new ReportPdf.Definition(report.id(), report.name(),
            "Relatório de armazenamento e retenção de backups", ReportPdf.Orientation.PORTRAIT,
            List.of(
                new ReportPdf.Indicator("Hosts habilitados", ReportPdf.number(summary.rows().size())),
                new ReportPdf.Indicator("Backups salvos", ReportPdf.number(summary.saved())),
                new ReportPdf.Indicator("Hosts sem backup", ReportPdf.number(summary.withoutBackup()))),
            "Backup de hosts",
            List.of(
                new ReportPdf.Column("Host", 40, LEFT),
                new ReportPdf.Column("Retenção", 15, CENTER),
                new ReportPdf.Column("Backups salvos", 20, CENTER),
                new ReportPdf.Column("Último backup", 25, RIGHT)),
            summary.rows().stream().map(row -> List.of(row.name(), ReportPdf.number(row.retention()),
                ReportPdf.number(row.saved()), row.lastBackup() == null ? "–" : row.lastBackupLabel())).toList(),
            "Nenhum host habilitado para backup.", "Total de hosts: " + ReportPdf.number(summary.rows().size()));
        return ReportPdf.render(definition, issuance);
    }
}
