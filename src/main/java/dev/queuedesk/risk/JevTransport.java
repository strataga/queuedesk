package dev.queuedesk.risk;

/**
 * The Jev HTTP seam: one call per assessment, deliberately no retries.
 *
 * <p>An overload (429/529) surfaces as unavailable, the gate holds the
 * payment, and the operator sees it, instead of the adapter quietly
 * spending more calls. The production {@link JevHttpTransport} speaks to
 * TypeSafe; tests script this interface or run the real transport against
 * a loopback stub server. All failures are the typed provider exceptions,
 * never raw I/O errors.
 */
public interface JevTransport {
    /**
     * POSTs one evaluation request and returns the raw response body.
     *
     * @throws RiskSignalProvider.ProviderException on timeout, unavailability,
     *         or a contract-breaking response
     */
    String evaluate(String requestJson) throws RiskSignalProvider.ProviderException;
}
