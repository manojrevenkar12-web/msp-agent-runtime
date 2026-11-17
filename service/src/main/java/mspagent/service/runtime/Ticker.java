package mspagent.service.runtime;

import mspagent.core.engine.Engine;
import mspagent.service.Settings;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Resumes runs due for a retry, runs whose worker died, and runs a person approved. */
@Component
public class Ticker {
    private final Engine engine;
    private final Settings settings;

    public Ticker(Engine engine, Settings settings) {
        this.engine = engine;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${msp.tick-millis:1000}")
    public void tick() {
        engine.tick(settings.workerId(), 200);
    }
}
