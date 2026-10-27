package br.com.scriptping;

import io.github.cdimascio.dotenv.Dotenv;
import java.util.LinkedHashMap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Loads .env from the working directory; real environment variables take precedence. */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(
        ConfigurableEnvironment environment,
        SpringApplication application
    ) {
        environment.getPropertySources().addLast(load("."));
    }

    static MapPropertySource load(String directory) {
        var values = new LinkedHashMap<String, Object>();
        Dotenv.configure()
            .directory(directory)
            .ignoreIfMissing()
            .load()
            .entries()
            .forEach(entry -> values.put(entry.getKey(), unquote(entry.getValue())));
        return new MapPropertySource("dotenv", values);
    }

    // dotenv-java preserves single quotes; Compose removes them. Keep both readers consistent.
    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }
}
