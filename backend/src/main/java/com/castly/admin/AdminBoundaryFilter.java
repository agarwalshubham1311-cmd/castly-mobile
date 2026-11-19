package com.castly.admin;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds admin payloads including chunked transfer; never records raw inputs or credentials. */
public class AdminBoundaryFilter extends OncePerRequestFilter {
  private final AdminSettings settings;

  public AdminBoundaryFilter(AdminSettings settings) {
    this.settings = settings;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getServletPath();
    if (path == null || path.isEmpty()) path = request.getRequestURI();
    final String resource = path;
    boolean legacy =
        resource.equals("/api/v1/engagement/admin/analytics")
            || java.util.List.of(
                    "subscriptions", "verification", "moderation", "disputes", "trust", "reviews")
                .stream()
                .anyMatch(
                    group ->
                        resource.equals("/api/v1/admin/" + group)
                            || resource.startsWith("/api/v1/admin/" + group + "/"));
    if (!resource.startsWith("/api/v1/admin") && !legacy) {
      chain.doFilter(request, response);
      return;
    }
    response.setHeader("Cache-Control", "no-store");
    UUID id = UUID.randomUUID();
    request.setAttribute("castly.admin.request", id);
    response.setHeader("X-Request-ID", id.toString());
    // Legacy review retains its original controller/limits and adds an opt-in delegated MFA gate.
    if (legacy && !settings.enabled()) {
      chain.doFilter(request, response);
      return;
    }
    if (!settings.enabled()) {
      response.sendError(404);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(16385);
    if (body.length > 16384) {
      response.sendError(413);
      return;
    }
    HttpServletRequestWrapper bounded =
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
              public int read() {
                return input.read();
              }

              public boolean isFinished() {
                return input.available() == 0;
              }

              public boolean isReady() {
                return true;
              }

              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
              }
            };
          }

          @Override
          public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
          }
        };
    chain.doFilter(bounded, response);
  }
}
