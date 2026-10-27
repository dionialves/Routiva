package br.com.scriptping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ScriptPingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScriptPingApplication.class, args);
    }
}
