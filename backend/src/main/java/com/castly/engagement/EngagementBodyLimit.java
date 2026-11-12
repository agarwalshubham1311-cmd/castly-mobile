package com.castly.engagement;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Limits new JSON endpoints without changing existing booking/evidence handlers. */
public final class EngagementBodyLimit extends OncePerRequestFilter {
  public static final class TooLarge extends IOException {}

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (!path.startsWith("/api/v1/engagement/")) {
      chain.doFilter(request, response);
      return;
    }
    if (request.getContentLengthLong() > 65536) {
      response.setStatus(413);
      response.setContentType("application/problem+json");
      response
          .getWriter()
          .write("{\"status\":413,\"title\":\"Engagement request exceeds size limit\"}");
      return;
    }
    if (path.equals("/api/v1/engagement/webhooks/email")) {
      chain.doFilter(request, response);
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          private ServletInputStream limited;

          public ServletInputStream getInputStream() throws IOException {
            if (limited != null) return limited;
            var original = super.getInputStream();
            limited =
                new ServletInputStream() {
                  int seen;

                  public int read() throws IOException {
                    int value = original.read();
                    if (value != -1 && ++seen > 65536) throw new TooLarge();
                    return value;
                  }

                  public int read(byte[] b, int offset, int length) throws IOException {
                    int n = original.read(b, offset, Math.min(length, 65537 - seen));
                    if (n > 0 && ((seen += n) > 65536)) throw new TooLarge();
                    return n;
                  }

                  public boolean isFinished() {
                    return original.isFinished();
                  }

                  public boolean isReady() {
                    return original.isReady();
                  }

                  public void setReadListener(ReadListener l) {
                    original.setReadListener(l);
                  }
                };
            return limited;
          }

          public BufferedReader getReader() throws IOException {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
          }
        },
        response);
  }
}
