package dev.queuedesk.risk;

// Jev HTTP seam (live gate, stubbed phase): one call per assessment, no
// retries (PRD §25 — a 429/529 surfaces as unavailable, the gate holds, and
// the operator sees it). The production JevHttpTransport speaks to TypeSafe;
// tests script this interface or run the real transport against a loopback
// stub server. Failures are the typed ProviderExceptions, never raw IO.
public interface JevTransport {
    String evaluate(String requestJson) throws RiskSignalProvider.ProviderException;
}
