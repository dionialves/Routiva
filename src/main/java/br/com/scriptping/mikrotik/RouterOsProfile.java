package br.com.scriptping.mikrotik;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Version-specific menus; package availability is still checked on the device. */
record RouterOsProfile(int major, int minor) {
    private static final Pattern VERSION = Pattern.compile("^(6|7)\\.(\\d+)(?:\\.\\d+)?(?:[a-z]+\\d*)?(?:\\s+\\([^)]*\\))?$");

    static RouterOsProfile from(String version) {
        var match = VERSION.matcher(version == null ? "" : version.trim());
        if (!match.matches()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "Versão não suportada ou não identificada. Utilize MikroTik com RouterOS 6.x ou 7.x.");
        try {
            return new RouterOsProfile(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Versão RouterOS inválida.");
        }
    }

    List<String> radioMenus() {
        if (major == 6) return List.of("/interface/wireless");
        return List.of("/interface/wireless", minor < 13 ? "/interface/wifiwave2" : "/interface/wifi");
    }
}
