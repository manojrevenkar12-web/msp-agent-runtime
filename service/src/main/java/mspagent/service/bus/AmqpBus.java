package mspagent.service.bus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mspagent.core.engine.Json;
import mspagent.core.event.Event;
import mspagent.service.Settings;
import mspagent.service.runtime.Dispatcher;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ: one topic exchange, {@code msp.partitions} durable queues with a single active consumer each (events of a
 * ticket stay in order across instances), and a dead-letter queue for messages a consumer could not take at all.
 * Messages are acknowledged after the engine returned; the engine itself absorbs redeliveries.
 */
@Configuration
@ConditionalOnProperty(name = "msp.bus", havingValue = "amqp")
public class AmqpBus {
    public static final String EXCHANGE = "msp.events";
    public static final String DLX = "msp.events.dlx";
    public static final String DEAD = "msp.events.dead";

    static String queue(int i) {
        return "msp.events.p" + i;
    }

    public static int partition(String key, int partitions) {
        return Math.floorMod(key.hashCode(), partitions);
    }

    @Bean
    Declarables topology(Settings s) {
        List<Declarable> d = new ArrayList<>();
        TopicExchange ex = new TopicExchange(EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(DLX, true, false);
        Queue dead = QueueBuilder.durable(DEAD).build();
        d.add(ex);
        d.add(dlx);
        d.add(dead);
        d.add(BindingBuilder.bind(dead).to(dlx).with("dead"));
        for (int i = 0; i < s.partitions(); i++) {
            Queue q = QueueBuilder.durable(queue(i))
                    .withArguments(Map.of("x-single-active-consumer", true, "x-dead-letter-exchange", DLX,
                            "x-dead-letter-routing-key", "dead"))
                    .build();
            Binding b = BindingBuilder.bind(q).to(ex).with("p" + i);
            d.add(q);
            d.add(b);
        }
        return new Declarables(d);
    }

    @Bean
    Publisher amqpPublisher(RabbitTemplate rabbit, Settings s) {
        return (key, e) -> {
            MessageProperties p = new MessageProperties();
            p.setContentType("application/json");
            p.setMessageId(e.key());
            p.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
            rabbit.send(EXCHANGE, "p" + partition(key, s.partitions()),
                    new Message(Json.write(e).getBytes(StandardCharsets.UTF_8), p));
        };
    }

    @Bean
    SimpleMessageListenerContainer consumers(ConnectionFactory cf, Settings s, Dispatcher dispatcher) {
        SimpleMessageListenerContainer c = new SimpleMessageListenerContainer(cf);
        String[] names = new String[s.partitions()];
        for (int i = 0; i < names.length; i++) {
            names[i] = queue(i);
        }
        c.setQueueNames(names);
        c.setPrefetchCount(10);
        c.setDefaultRequeueRejected(false);
        c.setMessageListener(m -> dispatcher.dispatch(
                Json.read(new String(m.getBody(), StandardCharsets.UTF_8), Event.class)));
        return c;
    }
}
