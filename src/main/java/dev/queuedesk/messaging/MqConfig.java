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

/**
 * IBM MQ wiring, declared explicitly bean by bean.
 *
 * <p>There is no IBM Spring starter on the classpath, so there is no
 * auto-configuration magic to explain away: connection factory, caching
 * wrapper, send template, and listener factory are all visible here. Sends
 * are transacted and persistent. The worker listener runs with concurrency
 * 1 on its own transacted JMS session (see {@code workerListenerFactory}).
 */
@Configuration
@EnableScheduling
@EnableJms
public class MqConfig {
    /** Raw MQ client connection factory, pointed at the lab queue manager from configuration. */
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
    /** Caching wrapper for sends: reuses sessions and reconnects after broker outages. */
    @Bean
    public ConnectionFactory connectionFactory(MQConnectionFactory mq) {
        var caching = new CachingConnectionFactory(mq);
        caching.setSessionCacheSize(4);
        caching.setReconnectOnException(true);
        return caching;
    }
    /** Send template: transacted sessions and persistent delivery, so a confirmed send survives a broker restart. */
    @Bean
    public JmsTemplate mqJmsTemplate(ConnectionFactory connectionFactory) {
        var template = new JmsTemplate(connectionFactory);
        template.setSessionTransacted(true);
        template.setExplicitQosEnabled(true);
        template.setDeliveryMode(DeliveryMode.PERSISTENT);
        return template;
    }
    /**
     * The worker's single consumer: concurrency 1 on a local transacted JMS
     * session. The database transaction lives inside the processing service
     * (a separate boundary); the JMS session commits only after the listener
     * returns normally, so a crash mid-processing redelivers instead of
     * losing the message. Uses the raw MQ factory because listener
     * containers manage their own consumer caching.
     */
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
