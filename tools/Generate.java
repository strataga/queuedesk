import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Standalone Java source launcher: no Spring or third-party dependencies. */
class Generate {
    record Planned(String key, String body, int expected, long amountMinor) {}
    public static void main(String[] args) throws Exception {
        try { run(args); }
        catch (IllegalArgumentException e) { System.err.println(e.getMessage()); System.exit(1); }
    }
    static void run(String[] args) throws Exception {
        var options = new HashMap<String, String>();
        boolean send = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--send")) { send = true; continue; }
            if (!Set.of("--seed", "--count", "--scenario", "--base-url", "--rate", "--run-id").contains(args[i]) || i+1 == args.length)
                throw new IllegalArgumentException("Usage: scripts/generate [--seed 42] [--count 20] [--scenario normal|burst|duplicate|invalid] [--run-id demo-42] [--send] [--base-url http://127.0.0.1:8080] [--rate 2]");
            String key = args[i++];
            if (options.put(key, args[i]) != null) throw new IllegalArgumentException("Duplicate option: " + key);
        }
        long seed = Long.parseLong(options.getOrDefault("--seed", "42"));
        int count = Integer.parseInt(options.getOrDefault("--count", "20"));
        String scenario = options.getOrDefault("--scenario", "normal");
        int rate = Integer.parseInt(options.getOrDefault("--rate", scenario.equals("burst") ? "10" : "2"));
        String runId = options.getOrDefault("--run-id", "demo-" + seed);
        if (!runId.matches("[A-Za-z0-9_-]{1,60}")) throw new IllegalArgumentException("run-id must be a synthetic token of 1..60 characters");
        if (count < 1 || count > 500 || rate < 1 || rate > 10 || count > rate * 60)
            throw new IllegalArgumentException("Bounds: count 1..500, rate 1..10, planned traffic must fit the 60-second cap");
        if (!Set.of("normal", "burst", "duplicate", "invalid").contains(scenario)) throw new IllegalArgumentException("Unknown scenario");
        URI base = URI.create(options.getOrDefault("--base-url", "http://127.0.0.1:8080"));
        // Exact IPv4 loopback literal avoids DNS rebinding, proxying, and redirects.
        if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost()) || base.getPort() < 1
                || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null
                || !(base.getPath().isEmpty() || base.getPath().equals("/")))
            throw new IllegalArgumentException("Only http://127.0.0.1:<port> is allowed");
        var random = new Random(seed);
        var plan = new ArrayList<Planned>();
        for (int i=0; i<count; i++) {
            if (scenario.equals("duplicate") && i%5==4) { plan.add(plan.get(i-1)); continue; }
            String key = runId + "-" + String.format(Locale.ROOT, "%04d", i);
            long amount = 1 + random.nextInt(100_000);
            String currency = "USD", sender = "SENDER_A";
            if (scenario.equals("invalid")) {
                switch (i%4) { case 0 -> amount=0; case 1 -> amount=-1; case 2 -> currency="EUR"; case 3 -> sender=""; }
            }
            String body = "{\"clientReference\":\"%s\",\"runId\":\"%s\",\"senderParticipant\":\"%s\",\"receiverParticipant\":\"RECEIVER_B\",\"amountMinor\":%d,\"currency\":\"%s\"}"
                    .formatted(key, runId, sender, amount, currency);
            plan.add(new Planned(key,body,scenario.equals("invalid") ? 400 : 202,amount));
        }
        // Both dry-run payloads and configuration are deterministic. No wall-clock identifiers.
        System.out.printf("mode=%s scenario=%s seed=%d runId=%s count=%d rate=%d concurrency=1 base=%s attemptAmountMinorTotal=%d%n", send?"send":"dry-run", scenario, seed, runId, count, rate, base, plan.stream().mapToLong(Planned::amountMinor).sum());
        if (!send) { plan.forEach(p -> System.out.println(p.key() + " " + p.body())); return; }
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new java.net.ProxySelector() {
                    public java.util.List<java.net.Proxy> select(URI uri) { return java.util.List.of(java.net.Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException error) {}
                }).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        int accepted=0, repeated=0, invalid=0, unknown=0, failed=0, attempted=0;
        for (var item : plan) {
            long remaining = deadline-System.nanoTime();
            if (remaining <= 0) break;
            attempted++;
            var request = HttpRequest.newBuilder(base.resolve("/api/v1/instructions"))
                    .timeout(Duration.ofNanos(Math.min(Duration.ofSeconds(5).toNanos(), remaining)))
                    .header("Content-Type", "application/json").header("Idempotency-Key", item.key())
                    .header("X-Correlation-Id", item.key()).POST(HttpRequest.BodyPublishers.ofString(item.body())).build();
            long start = System.nanoTime();
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                boolean expected = item.expected()==400 ? status==400 : status==202 || status==200;
                if (status==503) unknown++;
                else if (!expected) failed++;
                else if (status==202) accepted++;
                else if (status==200) repeated++;
                else invalid++;
                System.out.printf("key=%s http=%d latencyMs=%d%n", item.key(), status, (System.nanoTime()-start)/1_000_000);
            } catch (java.io.IOException e) { unknown++; System.out.println("key="+item.key()+" admission=UNKNOWN (retain original key/body)"); }
            long wait = Math.min(Duration.ofMillis(1000/rate).toNanos(), Math.max(0,deadline-System.nanoTime()));
            Thread.sleep(Duration.ofNanos(wait));
        }
        int unsent = count-attempted;
        System.out.printf("attempted=%d accepted=%d repeated=%d invalid=%d unknown=%d failed=%d unsent=%d%n",attempted,accepted,repeated,invalid,unknown,failed,unsent);
        System.out.println("Processing outcomes: NOT_OBSERVED; admissions remain RECEIVED. No MQ or worker exists.");
        // No automatic retry: ambiguous admissions require reusing the exact original run/seed/options.
        if (unknown>0 || unsent>0) System.exit(2);
        if (failed>0) System.exit(1);
    }
}
