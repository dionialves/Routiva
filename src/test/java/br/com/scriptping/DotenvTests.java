package br.com.scriptping;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class DotenvTests {

    @TempDir
    Path directory;

    @Test
    void loadsQuotedCredentialsAndPreservesEnvironmentPrecedence() throws Exception {
        Files.writeString(
            directory.resolve(".env"),
            "ZABBIX_USERNAME=monitor\nZABBIX_PASSWORD='secret # $ value'\n"
        );
        var environment = new StandardEnvironment();
        environment
            .getPropertySources()
            .addLast(DotenvEnvironmentPostProcessor.load(directory.toString()));
        assertThat(environment.getProperty("ZABBIX_USERNAME")).isEqualTo("monitor");
        assertThat(environment.getProperty("ZABBIX_PASSWORD")).isEqualTo("secret # $ value");
        environment
            .getPropertySources()
            .addFirst(new MapPropertySource("override", Map.of("ZABBIX_USERNAME", "external")));
        assertThat(environment.getProperty("ZABBIX_USERNAME")).isEqualTo("external");
    }

    @Test
    void missingDotenvDoesNotPreventStartup() {
        assertThat(DotenvEnvironmentPostProcessor.load(directory.toString())).isNotNull();
    }
}
