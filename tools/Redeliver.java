import com.ibm.mq.jakarta.jms.MQQueueConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/** Controlled redelivery injector for SC07: replays one stored instruction as a
 *  second JMS delivery of the SAME eventId. Lab-only; loopback MQ with the
 *  lab's documented credentials. Payload bytes are rebuilt from the live read
 *  plus a recomputed REQUEST_HASH_V1 (same canonical form as the app). */
public class Redeliver {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 4) throw new IllegalArgumentException(
                "Usage: scripts/redeliver <baseUrl> <instructionId> <eventId> [queue]");
        String base = args[0], instructionId = args[1], eventId = args[2];
        // Optional queue for isolated lanes (RS03 uses the risk queue); the
        // default keeps SC07 on the shared input queue.
        String queue = args.length == 4 ? args[3] : "QUEUEDESK.INSTRUCTION.IN";
        if (!queue.matches("[A-Z0-9._]{1,48}")) throw new IllegalArgumentException("queue must be 1..48 MQ word characters");
        // Exact IPv4 loopback literal like Generate: prefix matching would
        // accept http://127.0.0.1.evil.com (DNS rebinding target).
        URI parsed = URI.create(base);
        if (!"http".equals(parsed.getScheme()) || !"127.0.0.1".equals(parsed.getHost()) || parsed.getPort() < 1
                || parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null
                || !(parsed.getPath().isEmpty() || parsed.getPath().equals("/")))
            throw new IllegalArgumentException("Only http://127.0.0.1:<port> is allowed");
        var client = HttpClient.newHttpClient();
        var get = HttpRequest.newBuilder(URI.create(base + "/api/v1/instructions/" + instructionId))
                .timeout(java.time.Duration.ofSeconds(5)).GET().build();
        var body = client.send(get, HttpResponse.BodyHandlers.ofString()).body();
        String correlationId = field(body, "correlationId");
        String runId = field(body, "runId");
        String ref = field(body, "clientReference");
        String sender = field(body, "senderParticipant");
        String receiver = field(body, "receiverParticipant");
        String amount = number(body, "amountMinor");
        String currency = field(body, "currency");
        // Same canonical form as RequestHash (VERSION-led, backslash-escaped);
        // validatedPayload compares digests, so any drift breaks SC07.
        String hash = sha256("REQUEST_HASH_V1|" + esc(ref) + "|" + esc(runId) + "|" + esc(sender) + "|"
                + esc(receiver) + "|" + amount + "|" + esc(currency));
        String envelope = "{\"schemaVersion\":1,\"eventType\":\"InstructionReceived\",\"eventId\":\"" + eventId
                + "\",\"instructionId\":\"" + instructionId + "\",\"correlationId\":\"" + correlationId
                + "\",\"runId\":\"" + runId + "\",\"occurredAt\":\"" + Instant.now()
                + "\",\"payload\":{\"clientReference\":\"" + ref + "\",\"senderParticipant\":\"" + sender
                + "\",\"receiverParticipant\":\"" + receiver + "\",\"amountMinor\":" + amount
                + ",\"currency\":\"" + currency + "\",\"requestHash\":\"" + hash + "\"}}";
        var f = new MQQueueConnectionFactory();
        f.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        f.setHostName("127.0.0.1");
        f.setPort(1414);
        f.setChannel("DEV.APP.SVRCONN");
        f.setQueueManager("QDESK");
        f.setStringProperty(WMQConstants.USERID, "app");
        f.setStringProperty(WMQConstants.PASSWORD, System.getenv().getOrDefault("MQ_APP_PASSWORD", "queuedesk-lab"));
        try (var c = f.createConnection(); var s = c.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            var message = s.createTextMessage(envelope);
            message.setStringProperty("eventId", eventId);
            message.setStringProperty("runId", runId);
            s.createProducer(s.createQueue(queue)).send(message);
            System.out.println("redelivered eventId=" + eventId);
        }
    }
    // Minimal JSON readers: the read view is app-shaped (string/number fields,
    // no nesting above payload, no escaped quotes in synthetic fixtures).
    private static String field(String json, String name) {
        String key = "\"" + name + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) throw new IllegalArgumentException("no field " + name);
        int end = json.indexOf('"', start + key.length());
        return json.substring(start + key.length(), end);
    }
    private static String number(String json, String name) {
        String key = "\"" + name + "\":";
        int start = json.indexOf(key);
        if (start < 0) throw new IllegalArgumentException("no field " + name);
        int end = start + key.length();
        while (end < json.length() && "-+0123456789.eE".indexOf(json.charAt(end)) >= 0) end++;
        return json.substring(start + key.length(), end);
    }
    private static String esc(String field) {
        return field.replace("\\", "\\\\").replace("|", "\\|");
    }
    private static String sha256(String canonical) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        var hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
