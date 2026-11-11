package com.castly.trust;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.concurrent.Semaphore;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Evidence-only bulkhead before JSON deserialization; never buffers or logs request bodies. */
public final class EvidenceUploadLimitFilter extends OncePerRequestFilter {
  public static final long MAX_REQUEST_BYTES = 7_100_000;
  private final Semaphore slots = new Semaphore(2);

  public static final class TooLarge extends IOException {
    public TooLarge() {
      super("Evidence request exceeds size limit");
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod())
        || !request.getRequestURI().matches("/api/v1/disputes/[0-9a-fA-F-]{36}/images");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      chain.doFilter(request, response);
      return;
    }
    if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
      reject(response, 413, "Evidence request exceeds size limit");
      return;
    }
    if (!slots.tryAcquire()) {
      reject(response, 429, "Image upload capacity is busy. Retry shortly.");
      return;
    }
    try {
      chain.doFilter(
          new HttpServletRequestWrapper(request) {
            private ServletInputStream bounded;

            @Override
            public ServletInputStream getInputStream() throws IOException {
              if (bounded == null) {
                var original = super.getInputStream();
                bounded =
                    new ServletInputStream() {
                      long read;

                      private void count(int n) throws TooLarge {
                        if (n > 0 && (read += n) > MAX_REQUEST_BYTES) throw new TooLarge();
                      }

                      @Override
                      public int read() throws IOException {
                        int value = original.read();
                        if (value >= 0) count(1);
                        return value;
                      }

                      @Override
                      public int read(byte[] b, int off, int len) throws IOException {
                        int n =
                            original.read(
                                b, off, (int) Math.min(len, MAX_REQUEST_BYTES - read + 1));
                        count(n);
                        return n;
                      }

                      @Override
                      public boolean isFinished() {
                        return original.isFinished();
                      }

                      @Override
                      public boolean isReady() {
                        return original.isReady();
                      }

                      @Override
                      public void setReadListener(ReadListener listener) {
                        original.setReadListener(listener);
                      }

                      @Override
                      public void close() throws IOException {
                        original.close();
                      }
                    };
              }
              return bounded;
            }

            @Override
            public BufferedReader getReader() throws IOException {
              return new BufferedReader(
                  new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            }
          },
          response);
    } catch (TooLarge tooLarge) {
      if (!response.isCommitted()) reject(response, 413, "Evidence request exceeds size limit");
    } finally {
      slots.release();
    }
  }

  private void reject(HttpServletResponse response, int status, String detail) throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
  }
}
