package mspagent.service.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import mspagent.core.engine.Run;
import mspagent.core.engine.RunListener;
import mspagent.service.live.RunSocket;
import org.springframework.stereotype.Component;

/** Every change of a run: counted for Prometheus and pushed to the tenant's open consoles. */
@Component
public class RunEvents implements RunListener {
    private final MeterRegistry meters;
    private final RunSocket socket;

    public RunEvents(MeterRegistry meters, RunSocket socket) {
        this.meters = meters;
        this.socket = socket;
    }

    @Override
    public void changed(Run run) {
        meters.counter("msp.runs", "agent", run.agentId(), "status", run.status().name()).increment();
        socket.publish(run);
    }
}
