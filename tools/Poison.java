import com.ibm.mq.jakarta.jms.MQQueueConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.*;

/**
 * Poison injector for the quarantine drill: puts one raw text message,
 * byte-for-byte as given, on the worker input queue.
 *
 * <p>Deliberately dumb: no envelope building and no validation, because the
 * drill needs genuinely malformed input to prove backout routing. Lab-only:
 * loopback MQ with the lab's documented credentials (the password comes
 * from {@code MQ_APP_PASSWORD}, defaulting to the lab value).
 *
 * <p>Usage: {@code scripts/poison <runTag> <rawBody>}. The run tag travels
 * as a JMS property so drills can correlate what they injected.
 */
public class Poison {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: scripts/poison <runTag> <rawBody>");
        var f = new MQQueueConnectionFactory();
        f.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        f.setHostName("127.0.0.1");
        f.setPort(1414);
        f.setChannel("DEV.APP.SVRCONN");
        f.setQueueManager("QDESK");
        f.setStringProperty(WMQConstants.USERID, "app");
        f.setStringProperty(WMQConstants.PASSWORD, System.getenv().getOrDefault("MQ_APP_PASSWORD", "queuedesk-lab"));
        try (var c = f.createConnection(); var s = c.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            var message = s.createTextMessage(args[1]);
            message.setStringProperty("runId", args[0]);
            s.createProducer(s.createQueue("QUEUEDESK.INSTRUCTION.IN")).send(message);
            System.out.println("poisoned runId=" + args[0]);
        }
    }
}
