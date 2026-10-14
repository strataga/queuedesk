package dev.queuedesk.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

// Rejects POST bodies over 16 KiB with 413 before the controller runs. A declared
// Content-Length short-circuits; streamed bodies are counted while being read.
@Component
public class BodyLimitFilter extends OncePerRequestFilter {
    public static final int MAX_BODY_BYTES = 16 * 1024;
    public static class BodyTooLarge extends RuntimeException {}
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().startsWith("/api/v1/instructions"));
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Filters run outside Spring MVC, so @RestControllerAdvice cannot see our
        // exception: translate to the 413 response here, in the same error shape.
        try {
            if (request.getContentLengthLong() > MAX_BODY_BYTES) throw new BodyTooLarge();
            chain.doFilter(new CountingRequest(request), response);
        } catch (BodyTooLarge oversized) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"code":"REQUEST_TOO_LARGE","message":"Request body exceeds the 16 KiB lab limit"}""");
        }
    }
    static class CountingRequest extends HttpServletRequestWrapper {
        private CountingStream shared;
        CountingRequest(HttpServletRequest request) { super(request); }
        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (shared == null) shared = new CountingStream(super.getInputStream());
            return shared;
        }
        @Override
        public java.io.BufferedReader getReader() throws IOException {
            // Route the reader through the same counting stream: without this
            // override the character path bypasses the 16 KiB limit entirely.
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(getInputStream(), getCharacterEncoding() != null
                            ? java.nio.charset.Charset.forName(getCharacterEncoding())
                            : java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    // Package-visible for direct unit tests of both boundaries.
    static class CountingStream extends ServletInputStream {
        private final ServletInputStream inner;
        private long count;
        CountingStream(ServletInputStream inner) { this.inner = inner; }
        private void charge(int read) {
            if (read < 0) return;
            count += read;
            if (count > MAX_BODY_BYTES) throw new BodyTooLarge();
        }
        @Override
        public int read() throws IOException {
            int b = inner.read();
            charge(b < 0 ? -1 : 1);
            return b;
        }
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = inner.read(b, off, len);
            charge(n);
            return n;
        }
        @Override public boolean isFinished() { return inner.isFinished(); }
        @Override public boolean isReady() { return inner.isReady(); }
        @Override public void setReadListener(ReadListener listener) { inner.setReadListener(listener); }
    }
}
