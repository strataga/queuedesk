import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Standalone Java source launcher: no Spring or third-party dependencies.
 *  The script is the primary producer (PR01): seeded fixtures, bounded pacing,
 *  separate send and observe phases (GN07), machine-readable reports (GEN-01). */
class Generate {
    static final String FIXTURES = "qd-fixtures-1";
    record Planned(String key, String body, int expected, long amountMinor, boolean repeat) {}
    record Sent(String key, String body, int expected, int http, long latencyMs, long sentAtMs, String instructionId) {}
    public static void main(String[] args) throws Exception {
        try { run(args); }
        catch (IllegalArgumentException e) { System.err.println(e.getMessage()); System.exit(1); }
    }
    // SAFE-01 guard check: returns the halt reason, or null to proceed. Fails
    // CLOSED: an unreadable guard state (non-200, unreachable, malformed)
    // halts new traffic — a safeguard that cannot be read cannot be trusted.
    static String guardHaltReason(String base) {
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            var get = HttpRequest.newBuilder(URI.create(base + "/api/v1/ops/guards"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var response = client.send(get, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                return "guard check HTTP " + response.statusCode() + " (fail closed)";
            String json = response.body();
            if (!json.contains("\"haltTraffic\":true")) return null;
            return "lab safeguards halted new traffic " + reasonsDetail(json);
        } catch (Exception unreachable) {
            String why = unreachable.getMessage() == null ? unreachable.toString() : unreachable.getMessage();
            return "guard check unreachable (" + why + ", fail closed)";
        }
    }
    static String reasonsDetail(String json) {
        int reasons = json.indexOf("\"reasons\":[");
        if (reasons < 0) return "halted";
        int end = json.indexOf(']', reasons);
        if (end < 0) return "halted";
        return json.substring(reasons + 11, Math.min(end, reasons + 300));
    }
    static void run(String[] args) throws Exception {
        var options = new HashMap<String, String>();
        boolean send = false, observe = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--send")) { send = true; continue; }
            if (args[i].equals("--observe")) { observe = true; continue; }
            if (!Set.of("--seed", "--count", "--scenario", "--base-url", "--rate", "--run-id",
                    "--concurrency", "--send-seconds", "--observe-seconds", "--report").contains(args[i]) || i+1 == args.length)
                throw new IllegalArgumentException("Usage: scripts/generate [--seed 42] [--count 20] [--scenario normal|burst|duplicate|conflict|invalid|reject|uncertain|unknown|risk-1|risk-2|risk-3|risk-4|risk-5|risk-6] [--run-id demo-42] [--send] [--observe] [--base-url http://127.0.0.1:8080] [--rate 2] [--concurrency 1] [--send-seconds 60] [--observe-seconds 90] [--report reports/<run>.json]");
            String key = args[i++];
            if (options.put(key, args[i]) != null) throw new IllegalArgumentException("Duplicate option: " + key);
        }
        long seed = Long.parseLong(options.getOrDefault("--seed", "42"));
        int count = Integer.parseInt(options.getOrDefault("--count", "20"));
        String scenario = options.getOrDefault("--scenario", "normal");
        int rate = Integer.parseInt(options.getOrDefault("--rate", scenario.equals("burst") ? "10" : "2"));
        int concurrency = Integer.parseInt(options.getOrDefault("--concurrency", "1"));
        int sendSeconds = Integer.parseInt(options.getOrDefault("--send-seconds", "60"));
        int observeSeconds = Integer.parseInt(options.getOrDefault("--observe-seconds", "90"));
        String runId = options.getOrDefault("--run-id", "demo-" + seed);
        if (!runId.matches("[A-Za-z0-9_-]{1,60}")) throw new IllegalArgumentException("run-id must be a synthetic token of 1..60 characters");
        if (count < 1 || count > 500 || rate < 1 || rate > 10 || concurrency < 1 || concurrency > 4
                || sendSeconds < 1 || sendSeconds > 300 || observeSeconds < 1 || observeSeconds > 300)
            throw new IllegalArgumentException("Bounds: count 1..500, rate 1..10, concurrency 1..4, send-seconds 1..300, observe-seconds 1..300");
        // No upfront plan-vs-deadline fit check: a plan that overruns its sending
        // deadline must run and stop honestly (SC12), not be rejected.
        if (!Set.of("normal", "burst", "duplicate", "conflict", "invalid", "reject", "uncertain", "unknown",
                "risk-1", "risk-2", "risk-3", "risk-4", "risk-5", "risk-6").contains(scenario))
            throw new IllegalArgumentException("Unknown scenario");
        if (observe && !send) throw new IllegalArgumentException("--observe requires --send (one run sends, then observes)");
        URI base = URI.create(options.getOrDefault("--base-url", "http://127.0.0.1:8080"));
        // Exact IPv4 loopback literal avoids DNS rebinding, proxying, and redirects.
        if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost()) || base.getPort() < 1
                || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null
                || !(base.getPath().isEmpty() || base.getPath().equals("/")))
            throw new IllegalArgumentException("Only http://127.0.0.1:<port> is allowed");
        var plan = buildPlan(scenario, seed, count, runId);
        long unique = plan.stream().map(Planned::key).distinct().count();
        // Both dry-run payloads and configuration are deterministic. No wall-clock identifiers.
        System.out.printf("mode=%s scenario=%s seed=%d runId=%s count=%d unique=%d rate=%d concurrency=%d base=%s fixtures=%s attemptAmountMinorTotal=%d%n",
                send?"send":"dry-run", scenario, seed, runId, plan.size(), unique, rate, concurrency, base, FIXTURES,
                plan.stream().mapToLong(Planned::amountMinor).sum());
        if (!send) { plan.forEach(p -> System.out.println(p.key() + " " + p.body())); return; }
        // SAFE-01: consult the lab safeguards before new traffic. A halt (or an
        // unreadable guard state — fail closed) stops here with everything
        // honestly unsent.
        String halted = guardHaltReason(base.toString());
        if (halted != null) {
            System.out.println("guard.halt: " + halted + " — sending nothing");
            var unsent = new ArrayList<String>();
            for (var p : plan) unsent.add(p.key() + ":guard-halted");
            if (options.containsKey("--report"))
                writeReport(Path.of(options.get("--report")), scenario, seed, runId, plan.size(), (int) unique,
                        rate, concurrency, sendSeconds, observeSeconds, base, Instant.now(), 0, 0,
                        0, 0, 0, 0, plan.size(), Map.of(), List.of(), List.of(), unsent, List.of());
            System.exit(2);
            return;
        }
        Instant startedAt = Instant.now();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new java.net.ProxySelector() {
                    public java.util.List<java.net.Proxy> select(URI uri) { return java.util.List.of(java.net.Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException error) {}
                }).build();
        var sent = sendAll(client, base, plan, rate, concurrency, sendSeconds);
        int attempted = sent.size(), unsent = plan.size() - attempted;
        int accepted=0, repeated=0, invalid=0, unknown=0, failed=0;
        var httpLatencies = new ArrayList<Long>();
        var firstIdByKey = new HashMap<String, String>();
        var repeatKeys = new HashSet<String>();
        for (var p : plan) if (p.repeat()) repeatKeys.add(p.key());
        var assertionFailures = new ArrayList<String>();
        for (var s : sent) {
            httpLatencies.add(s.latencyMs());
            if (s.http() == -1) { unknown++; continue; }
            boolean expected = s.expected()==400 ? s.http()==400
                    : s.expected()==409 ? s.http()==409
                    : s.http()==202 || s.http()==200;
            if (s.http()==503) unknown++;
            else if (!expected) { failed++; assertionFailures.add(s.key() + ": expected http " + s.expected() + " got " + s.http()); }
            else if (s.http()==202) accepted++;
            else if (s.http()==200) repeated++;
            else invalid++;
            if (s.http()==202 || s.http()==200) {
                if (repeatKeys.contains(s.key()) && firstIdByKey.containsKey(s.key())) {
                    String original = firstIdByKey.get(s.key());
                    if (original == null || !original.equals(s.instructionId()))
                        assertionFailures.add(s.key() + ": repeat did not map to the original instruction ID");
                } else firstIdByKey.putIfAbsent(s.key(), s.instructionId());
            }
        }
        System.out.printf("attempted=%d accepted=%d repeated=%d invalid=%d unknown=%d failed=%d unsent=%d%n",attempted,accepted,repeated,invalid,unknown,failed,unsent);
        var e2eLatencies = new ArrayList<Long>();
        var terminalByKey = new HashMap<String, String>();
        var unresolved = new ArrayList<String>();
        if (observe) {
            var observed = observeRun(client, base, runId, sent, observeSeconds);
            e2eLatencies.addAll(observed.e2eLatencies());
            terminalByKey.putAll(observed.terminalByKey());
            unresolved.addAll(observed.unresolved());
            System.out.printf("observed=%d completed=%d rejected=%d held=%d pending=%d unresolved=%d%n",
                    observed.observed(), observed.completed(), observed.rejected(), observed.held(),
                    observed.pending(), unresolved.size());
            scenarioAssertions(scenario, sent, terminalByKey, unresolved, client, base, assertionFailures);
        } else {
            System.out.println("Processing outcomes: NOT_OBSERVED by this run (pass --observe, or GET /api/v1/instructions?runId=" + runId + ").");
            for (var s : sent) if (s.http() == -1) unresolved.add(s.key() + ":admission-unknown");
        }
        for (int i = attempted; i < plan.size(); i++) unresolved.add(plan.get(i).key() + ":unsent");
        if (options.containsKey("--report"))
            writeReport(Path.of(options.get("--report")), scenario, seed, runId, plan.size(), (int) unique,
                    rate, concurrency, sendSeconds, observeSeconds, base, startedAt, attempted, accepted,
                    repeated, invalid, unknown, failed, unsent, terminalByKey, httpLatencies, e2eLatencies,
                    unresolved, assertionFailures);
        for (var f : assertionFailures) System.out.println("assert=" + f);
        // No automatic retry: ambiguous admissions require reusing the exact original run/seed/options.
        if (!unresolved.isEmpty()) System.exit(2);
        if (!assertionFailures.isEmpty() || failed>0) System.exit(1);
    }
    // SC02 shape: count unique admissions, then count/4 exact key/body repeats of
    // the first keys. Conflict: admit/change-amount pairs. Reject: reject- fixtures.
    // Risk fixtures (RS01-RS06) reuse the same bounds/allowlist: risk-1 is benign
    // small amounts; risk-2 mixes a huge amount every 4th instruction (labeled
    // suspicious in the evaluator-only oracle); risk-3 repeats the duplicate
    // shape; risk-4/5/6 are normal-shaped (injection, faults, and restart are
    // driven by the risk harness, not the payload).
    static List<Planned> buildPlan(String scenario, long seed, int count, String runId) {
        var random = new Random(seed);
        var plan = new ArrayList<Planned>();
        for (int i=0; i<count; i++) {
            String key = runId + "-" + String.format(Locale.ROOT, "%04d", i);
            long amount = 1 + random.nextInt(100_000);
            String currency = "USD", sender = "SENDER_A", ref = key;
            if (scenario.equals("invalid")) {
                switch (i%4) { case 0 -> amount=0; case 1 -> amount=-1; case 2 -> currency="EUR"; case 3 -> sender=""; }
            }
            if (scenario.equals("reject")) ref = "reject-" + String.format(Locale.ROOT, "%04d", i);
            if (scenario.equals("uncertain")) ref = (i%4==3 ? "uncertain-reject-" : "uncertain-") + String.format(Locale.ROOT, "%04d", i);
            if (scenario.equals("unknown")) ref = "unknown-" + String.format(Locale.ROOT, "%04d", i);
            if (scenario.equals("risk-2") && i%4==3) amount = 8_000_000L + random.nextInt(1_000_000);
            long finalAmount = amount;
            if (scenario.equals("conflict") && i%2==1) {
                var first = plan.get(i-1);
                String changed = first.body().replace("\"amountMinor\":" + first.amountMinor(),
                        "\"amountMinor\":" + (first.amountMinor()+1));
                plan.add(new Planned(first.key(), changed, 409, first.amountMinor()+1, false));
                continue;
            }
            String body = "{\"clientReference\":\"%s\",\"runId\":\"%s\",\"senderParticipant\":\"%s\",\"receiverParticipant\":\"RECEIVER_B\",\"amountMinor\":%d,\"currency\":\"%s\"}"
                    .formatted(ref, runId, sender, finalAmount, currency);
            int expected = scenario.equals("invalid") ? 400 : 202;
            plan.add(new Planned(key, body, expected, finalAmount, false));
        }
        if (scenario.equals("duplicate") || scenario.equals("risk-3")) {
            int repeats = count/4;
            for (int r=0; r<repeats; r++) {
                var first = plan.get(r);
                plan.add(new Planned(first.key(), first.body(), 200, first.amountMinor(), true));
            }
        }
        return plan;
    }
    static List<Sent> sendAll(HttpClient client, URI base, List<Planned> plan, int rate, int concurrency, int sendSeconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(sendSeconds).toNanos();
        long intervalNanos = 1_000_000_000L / rate;
        var pool = Executors.newFixedThreadPool(concurrency);
        var sent = Collections.synchronizedList(new ArrayList<Sent>());
        var futures = new ArrayList<Future<?>>();
        try {
            long nextAt = System.nanoTime();
            for (var item : plan) {
                long now = System.nanoTime();
                if (now >= deadline) break; // SC12: stop submitting; the rest stay unsent.
                long sleepNanos = nextAt - now;
                if (sleepNanos > 0) Thread.sleep(Duration.ofNanos(Math.min(sleepNanos, Math.max(0, deadline - now))));
                if (System.nanoTime() >= deadline) break;
                nextAt = Math.max(System.nanoTime(), nextAt) + intervalNanos;
                long remaining = deadline - System.nanoTime();
                futures.add(pool.submit(() -> {
                    long sentAt = System.currentTimeMillis(), start = System.nanoTime();
                    var request = HttpRequest.newBuilder(base.resolve("/api/v1/instructions"))
                            .timeout(Duration.ofNanos(Math.min(Duration.ofSeconds(5).toNanos(), Math.max(1, remaining))))
                            .header("Content-Type", "application/json").header("Idempotency-Key", item.key())
                            .header("X-Correlation-Id", item.key()).POST(HttpRequest.BodyPublishers.ofString(item.body())).build();
                    try {
                        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        long latency = (System.nanoTime()-start)/1_000_000;
                        String id = response.statusCode()==202 || response.statusCode()==200
                                ? jsonString(response.body(), "instructionId") : null;
                        sent.add(new Sent(item.key(), item.body(), item.expected(), response.statusCode(), latency, sentAt, id));
                        System.out.printf("key=%s http=%d latencyMs=%d%n", item.key(), response.statusCode(), latency);
                    } catch (java.io.IOException | InterruptedException e) {
                        sent.add(new Sent(item.key(), item.body(), item.expected(), -1, (System.nanoTime()-start)/1_000_000, sentAt, null));
                        System.out.println("key="+item.key()+" admission=UNKNOWN (retain original key/body)");
                    }
                }));
            }
            for (var f : futures) {
                try { f.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (TimeoutException timeout) { f.cancel(true); break; }
            }
        } finally {
            pool.shutdownNow();
        }
        return new ArrayList<>(sent);
    }
    record Observed(int observed, int completed, int rejected, int held, int pending, List<Long> e2eLatencies,
            Map<String, String> terminalByKey, List<String> unresolved) {}
    // GN07: sending is over; poll the run list every 2s until every admitted key
    // is terminal or the separate completion deadline expires. Unknown admissions
    // are rediscovered by their original clientReference (identical to the key).
    static Observed observeRun(HttpClient client, URI base, String runId, List<Sent> sent, int observeSeconds) throws Exception {
        var sentAtByKey = new HashMap<String, Long>();
        var refToKey = new HashMap<String, String>();
        var unknownKeys = new HashSet<String>();
        for (var s : sent) {
            String ref = jsonString(s.body(), "clientReference");
            if (ref != null) refToKey.put(ref, s.key());
            if (s.http() == -1 || s.http() == 503) unknownKeys.add(s.key());
            else if (s.http() == 202 || s.http() == 200) sentAtByKey.putIfAbsent(s.key(), s.sentAtMs());
        }
        var terminalByKey = new HashMap<String, String>();
        var terminalAtByKey = new HashMap<String, Long>();
        long deadline = System.currentTimeMillis() + observeSeconds * 1000L;
        int completed = 0, rejected = 0, held = 0;
        while (System.currentTimeMillis() < deadline) {
            var states = listRunStates(client, base, runId);
            for (var entry : states.entrySet()) {
                String key = refToKey.getOrDefault(entry.getValue().ref(), entry.getValue().ref());
                String state = entry.getValue().state();
                if (unknownKeys.contains(key) && !sentAtByKey.containsKey(key)) {
                    sentAtByKey.put(key, System.currentTimeMillis()); // rediscovered; e2e measured from here.
                    unknownKeys.remove(key);
                    System.out.println("key=" + key + " rediscovered=" + state);
                }
                // RISK_HELD settles observation: a hold is an expected stable
                // outcome under the risk gate (PRD §13: an L5 scenario expecting
                // a hold passes only when that exact hold and reason are
                // observed). Non-risk scenarios never produce it (gate OFF),
                // and their assertions below still fail a hold as unexpected.
                if ((state.equals("COMPLETED_SIMULATED") || state.equals("REJECTED") || state.equals("RISK_HELD"))
                        && !terminalByKey.containsKey(key)) {
                    terminalByKey.put(key, state);
                    terminalAtByKey.put(key, System.currentTimeMillis());
                    if (state.equals("COMPLETED_SIMULATED")) completed++;
                    else if (state.equals("REJECTED")) rejected++;
                    else held++;
                }
            }
            if (unknownKeys.isEmpty() && terminalByKey.size() >= sentAtByKey.size()) break;
            Thread.sleep(2000);
        }
        var e2e = new ArrayList<Long>();
        for (var key : terminalByKey.keySet()) {
            Long sentAt = sentAtByKey.get(key), terminalAt = terminalAtByKey.get(key);
            if (sentAt != null && terminalAt != null) e2e.add(Math.max(0, terminalAt - sentAt));
        }
        var unresolved = new ArrayList<String>();
        for (var key : sentAtByKey.keySet()) if (!terminalByKey.containsKey(key)) unresolved.add(key + ":pending");
        for (var key : unknownKeys) unresolved.add(key + ":admission-unknown");
        return new Observed(sentAtByKey.size(), completed, rejected, held,
                sentAtByKey.size() - terminalByKey.size(), e2e, terminalByKey, unresolved);
    }
    record ItemState(String state, String ref) {}
    static Map<String, ItemState> listRunStates(HttpClient client, URI base, String runId) throws Exception {
        var states = new LinkedHashMap<String, ItemState>();
        String cursor = null;
        do {
            String path = "/api/v1/instructions?runId=" + runId + "&limit=100" + (cursor == null ? "" : "&cursor=" + cursor);
            var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("run list failed: http " + response.statusCode());
            String json = response.body();
            int from = 0;
            while (true) {
                int idAt = json.indexOf("\"instructionId\":\"", from);
                if (idAt < 0) break;
                int idStart = idAt + "\"instructionId\":\"".length();
                String id = json.substring(idStart, json.indexOf('"', idStart));
                int stateAt = json.indexOf("\"status\":\"", idStart);
                int stateStart = stateAt + "\"status\":\"".length();
                String state = json.substring(stateStart, json.indexOf('"', stateStart));
                int refAt = json.indexOf("\"clientReference\":\"", stateStart);
                int refStart = refAt + "\"clientReference\":\"".length();
                String ref = json.substring(refStart, json.indexOf('"', refStart));
                states.put(id, new ItemState(state, ref));
                from = refStart;
            }
            cursor = response.headers().firstValue("X-Next-Cursor").orElse(null);
        } while (cursor != null);
        return states;
    }
    // Per-scenario assertions beyond HTTP codes; failures exit 1, never fabricated.
    static void scenarioAssertions(String scenario, List<Sent> sent, Map<String, String> terminalByKey,
            List<String> unresolved, HttpClient client, URI base, List<String> failures) throws Exception {
        if (scenario.equals("conflict")) {
            for (var s : sent) {
                if (s.http() != 202 || s.instructionId() == null) continue;
                var request = HttpRequest.newBuilder(base.resolve("/api/v1/instructions/" + s.instructionId()))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                String json = response.body();
                int at = json.indexOf("\"amountMinor\":");
                long stored = Long.parseLong(json.substring(at + "\"amountMinor\":".length()).split("[,}]")[0]);
                long original = Long.parseLong(s.body().split("\"amountMinor\":")[1].split("[,}]")[0]);
                if (stored != original) failures.add(s.key() + ": original payload changed after 409");
            }
        }
        if (scenario.equals("invalid")) {
            String runId = sent.isEmpty() ? null : jsonString(sent.get(0).body(), "runId");
            if (runId != null && !listRunStates(client, base, runId).isEmpty())
                failures.add("invalid scenario created instruction rows");
        }
        if (scenario.equals("normal") || scenario.equals("burst") || scenario.equals("duplicate")
                || scenario.equals("conflict")) {
            for (var entry : terminalByKey.entrySet())
                if (!entry.getValue().equals("COMPLETED_SIMULATED"))
                    failures.add(entry.getKey() + ": expected COMPLETED_SIMULATED, got " + entry.getValue());
        }
        if (scenario.equals("reject")) {
            for (var entry : terminalByKey.entrySet())
                if (!entry.getValue().equals("REJECTED"))
                    failures.add(entry.getKey() + ": expected REJECTED, got " + entry.getValue());
        }
        if (scenario.equals("uncertain")) {
            // Mixed by construction (every 4th is a rejection fixture): each key
            // must reach A definitive outcome, never stay pending, never regress.
            for (var entry : terminalByKey.entrySet())
                if (!entry.getValue().equals("COMPLETED_SIMULATED") && !entry.getValue().equals("REJECTED"))
                    failures.add(entry.getKey() + ": expected definitive outcome, got " + entry.getValue());
        }
        // unknown: nothing terminal is expected; the runner asserts the pending
        // shape (open tasks, no rejection, no replacement) through the API.
    }
    static String jsonString(String json, String field) {
        int at = json.indexOf("\"" + field + "\":\"");
        if (at < 0) return null;
        int start = at + field.length() + 4;
        return json.substring(start, json.indexOf('"', start));
    }
    static String distJson(List<Long> values) {
        if (values.isEmpty()) return "{\"count\":0,\"p50\":0,\"p95\":0,\"max\":0}";
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return "{\"count\":" + sorted.size() + ",\"p50\":" + sorted.get(sorted.size()/2)
                + ",\"p95\":" + sorted.get(Math.min(sorted.size()-1, sorted.size()*95/100))
                + ",\"max\":" + sorted.get(sorted.size()-1) + "}";
    }
    static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
    static void writeReport(Path path, String scenario, long seed, String runId, int planned, int unique,
            int rate, int concurrency, int sendSeconds, int observeSeconds, URI base, Instant startedAt,
            int attempted, int accepted, int repeated, int invalid, int unknown, int failed, int unsent,
            Map<String, String> terminalByKey, List<Long> httpLatencies, List<Long> e2eLatencies,
            List<String> unresolved, List<String> assertionFailures) throws Exception {
        long completed = terminalByKey.values().stream().filter("COMPLETED_SIMULATED"::equals).count();
        long rejected = terminalByKey.values().stream().filter("REJECTED"::equals).count();
        long held = terminalByKey.values().stream().filter("RISK_HELD"::equals).count();
        var json = new StringBuilder("{\n");
        json.append("  \"tool\": \"queuedesk-generate\", \"fixtureVersion\": \"").append(FIXTURES).append("\",\n");
        json.append("  \"scenario\": \"").append(scenario).append("\", \"seed\": ").append(seed)
                .append(", \"runId\": \"").append(jsonEscape(runId)).append("\",\n");
        json.append("  \"count\": ").append(planned).append(", \"unique\": ").append(unique)
                .append(", \"rate\": ").append(rate).append(", \"concurrency\": ").append(concurrency)
                .append(", \"baseUrl\": \"").append(base).append("\", \"sendSeconds\": ").append(sendSeconds)
                .append(", \"observeSeconds\": ").append(observeSeconds).append(",\n");
        json.append("  \"startedAt\": \"").append(startedAt).append("\", \"endedAt\": \"").append(Instant.now()).append("\",\n");
        json.append("  \"attempted\": ").append(attempted).append(", \"accepted\": ").append(accepted)
                .append(", \"repeated\": ").append(repeated).append(", \"invalid\": ").append(invalid)
                .append(", \"unknown\": ").append(unknown).append(", \"failed\": ").append(failed)
                .append(", \"unsent\": ").append(unsent).append(",\n");
        // RISK_HELD appears only when nonzero: SC reports keep their exact
        // two-key shape, risk reports carry the hold count.
        json.append("  \"outcomes\": {\"COMPLETED_SIMULATED\": ").append(completed)
                .append(", \"REJECTED\": ").append(rejected);
        if (held > 0) json.append(", \"RISK_HELD\": ").append(held);
        json.append("},\n");
        json.append("  \"httpLatencyMs\": ").append(distJson(httpLatencies)).append(",\n");
        json.append("  \"endToEndLatencyMs\": ").append(distJson(e2eLatencies)).append(",\n");
        json.append("  \"unresolved\": [");
        for (int i = 0; i < unresolved.size(); i++)
            json.append(i == 0 ? "" : ", ").append("\"").append(jsonEscape(unresolved.get(i))).append("\"");
        json.append("],\n  \"assertionFailures\": [");
        for (int i = 0; i < assertionFailures.size(); i++)
            json.append(i == 0 ? "" : ", ").append("\"").append(jsonEscape(assertionFailures.get(i))).append("\"");
        json.append("]\n}\n");
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, json.toString());
        System.out.println("report=" + path);
    }
}
