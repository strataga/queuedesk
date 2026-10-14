package dev.queuedesk.messaging;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

// Every messaging bean is declared here explicitly: no IBM Spring starter, so
// there is no auto-configuration magic to explain away in LEARN-02.
@Configuration
@EnableScheduling
@EnableJms
public class MqConfig {
    @Bean
    public MQConnectionFactory mqConnectionFactory(
            @Value("${queuedesk.mq.host}") String host,
            @Value("${queuedesk.mq.port}") int port,
            @Value("${queuedesk.mq.channel}") String channel,
            @Value("${queuedesk.mq.queue-manager}") String queueManager,
            @Value("${queuedesk.mq.user}") String user,
            @Value("${queuedesk.mq.password}") String password) throws Exception {
        var factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setHostName(host);
        factory.setPort(port);
        factory.setChannel(channel);
        factory.setQueueManager(queueManager);
        factory.setStringProperty(WMQConstants.USERID, user);
        factory.setStringProperty(WMQConstants.PASSWORD, password);
        return factory;
    }
    @Bean
    public ConnectionFactory connectionFactory(MQConnectionFactory mq) {
        var caching = new CachingConnectionFactory(mq);
        caching.setSessionCacheSize(4);
        caching.setReconnectOnException(true);
        return caching;
    }
    @Bean
    public JmsTemplate mqJmsTemplate(ConnectionFactory connectionFactory) {
        var template = new JmsTemplate(connectionFactory);
        template.setSessionTransacted(true);
        template.setExplicitQosEnabled(true);
        template.setDeliveryMode(DeliveryMode.PERSISTENT);
        return template;
    }
    // One worker consumer: concurrency 1, LOCAL transacted JMS session. The DB
    // transaction lives inside ProcessingService (separate boundary); the JMS
    // session commits only after the listener returns. Uses the raw MQ factory:
    // listener containers manage their own consumer caching.
    @Bean
    public DefaultJmsListenerContainerFactory workerListenerFactory(MQConnectionFactory mq,
            @Value("${queuedesk.worker.enabled:true}") boolean workerEnabled) {
        var factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(mq);
        factory.setSessionTransacted(true);
        factory.setConcurrency("1");
        factory.setAutoStartup(workerEnabled);
        // Container-level failures (broker drops, poison loops the listener
        // cannot classify) go to the lab log with identity instead of Spring
        // defaults. Listener exceptions still drive redelivery/backout.
        factory.setErrorHandler(t -> org.slf4j.LoggerFactory.getLogger("queuedesk.worker.container")
                .error("worker.container.error error={}", t.toString()));
        return factory;
    }
}
