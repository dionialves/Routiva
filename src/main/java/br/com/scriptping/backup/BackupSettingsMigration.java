package br.com.scriptping.backup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@DependsOnDatabaseInitialization
class BackupSettingsMigration implements InitializingBean {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    BackupSettingsMigration(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Override
    public void afterPropertiesSet() {
        transaction.executeWithoutResult(status -> {
            if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = current_schema AND LOWER(table_name) = 'backup_settings'
                """, Integer.class) == 0) return;

            var legacy = jdbc.query("SELECT retention, host_ids FROM backup_settings WHERE id=1 FOR UPDATE",
                (rs, row) -> new LegacySettings(rs.getInt(1), rs.getString(2)));
            if (!legacy.isEmpty()) {
                var settings = legacy.getFirst();
                var hostIds = decodeIds(settings.hostIds());
                jdbc.update("UPDATE app_host SET backup_retention=?", settings.retention());
                for (String hostId : hostIds) {
                    jdbc.update("UPDATE app_host SET backup_enabled=TRUE WHERE host_id=?", hostId);
                }
            }
            jdbc.execute("DROP TABLE backup_settings");
        });
    }

    private static List<String> decodeIds(String value) {
        try {
            return new ObjectMapper().readValue(value, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Configuração anterior de backup inválida.", ex);
        }
    }

    private record LegacySettings(int retention, String hostIds) {}
}
