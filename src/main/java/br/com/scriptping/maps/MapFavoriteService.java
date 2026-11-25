package br.com.scriptping.maps;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MapFavoriteService {
    private final JdbcTemplate jdbc;

    public MapFavoriteService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> favorites(long userId) {
        return jdbc.queryForList("""
            SELECT map_id FROM network_map_favorite WHERE user_id = ?
            ORDER BY favorited_at_ms DESC, map_id
            """, String.class, userId);
    }

    @Transactional
    public List<String> setFavorite(long userId, String mapId, boolean favorite) {
        // Serialize changes for the same account, including repeated requests from different tabs.
        if (jdbc.queryForList("SELECT id FROM app_user WHERE id = ? FOR UPDATE", Long.class, userId).isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (jdbc.queryForList("SELECT id FROM network_map WHERE id = ?", String.class, mapId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mapa não encontrado.");
        if (favorite) {
            long latest = jdbc.queryForObject("SELECT COALESCE(MAX(favorited_at_ms), 0) FROM network_map_favorite WHERE user_id = ?",
                Long.class, userId);
            long favoritedAt = Math.max(System.currentTimeMillis(), latest + 1);
            jdbc.update("""
                INSERT INTO network_map_favorite (user_id, map_id, favorited_at_ms)
                SELECT ?, ?, ? WHERE NOT EXISTS (
                    SELECT 1 FROM network_map_favorite WHERE user_id = ? AND map_id = ?
                )
                """, userId, mapId, favoritedAt, userId, mapId);
        } else {
            jdbc.update("DELETE FROM network_map_favorite WHERE user_id = ? AND map_id = ?", userId, mapId);
        }
        return favorites(userId);
    }
}
