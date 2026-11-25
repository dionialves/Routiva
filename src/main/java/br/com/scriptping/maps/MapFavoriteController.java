package br.com.scriptping.maps;

import br.com.scriptping.security.UserPrincipal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/maps/favorites")
public class MapFavoriteController {
    private final MapFavoriteService service;

    public MapFavoriteController(MapFavoriteService service) {
        this.service = service;
    }

    public record FavoriteRequest(Boolean favorite) {}

    @GetMapping
    public List<String> favorites(@AuthenticationPrincipal UserPrincipal user) {
        return service.favorites(userId(user));
    }

    @PostMapping("/{mapId}")
    public List<String> setFavorite(@AuthenticationPrincipal UserPrincipal user, @PathVariable String mapId,
        @RequestBody FavoriteRequest request) {
        if (request.favorite() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return service.setFavorite(userId(user), mapId, request.favorite());
    }

    private long userId(UserPrincipal user) {
        if (user == null || user.id() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return user.id();
    }
}
