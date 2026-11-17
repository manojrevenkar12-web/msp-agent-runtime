package mspagent.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The agent runtime as a service: webhooks in, agents run on the engine, the console and API out. */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(Settings.class)
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
