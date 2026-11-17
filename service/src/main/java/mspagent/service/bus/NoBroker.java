package mspagent.service.bus;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** With bus {@code inline} the relay hands events to the engine directly; nothing is published. */
@Configuration
@ConditionalOnProperty(name = "msp.bus", havingValue = "inline", matchIfMissing = true)
public class NoBroker {
    @Bean
    Publisher noPublisher() {
        return (key, e) -> {
            throw new IllegalStateException("bus is inline; nothing is published");
        };
    }
}
