package com.castly.trust;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class EvidenceUploadLimitFilterTest {
  private MockHttpServletRequest request(boolean unknown) {
    var request =
        unknown
            ? new MockHttpServletRequest() {
              @Override
              public long getContentLengthLong() {
                return -1;
              }
            }
            : new MockHttpServletRequest();
    request.setMethod("POST");
    request.setRequestURI("/api/v1/disputes/00000000-0000-0000-0000-000000000001/images");
    return request;
  }

  private void authenticate() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "fixture-user", null, java.util.List.of()));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void knownAndChunkedOversizeBodiesAreRejectedBeforeUnboundedDeserialization() throws Exception {
    authenticate();
    for (boolean chunked : new boolean[] {false, true}) {
      var request = request(chunked);
      request.setContent(new byte[(int) EvidenceUploadLimitFilter.MAX_REQUEST_BYTES + 1]);
      var response = new MockHttpServletResponse();
      new EvidenceUploadLimitFilter()
          .doFilter(
              request,
              response,
              (req, res) ->
                  req.getInputStream().transferTo(java.io.OutputStream.nullOutputStream()));
      assertEquals(413, response.getStatus());
      assertFalse(response.getContentAsString().contains("fixture-user"));
    }
  }

  @Test
  void bulkheadRejectsThirdUploadBeforeReadingAndReleasesAfterCompletion() throws Exception {
    var filter = new EvidenceUploadLimitFilter();
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Integer> upload =
          () -> {
            authenticate();
            try {
              var response = new MockHttpServletResponse();
              filter.doFilter(
                  request(true),
                  response,
                  (req, res) -> {
                    entered.countDown();
                    try {
                      if (!release.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("Held upload timed out");
                    } catch (InterruptedException e) {
                      throw new RuntimeException(e);
                    }
                  });
              return response.getStatus();
            } finally {
              SecurityContextHolder.clearContext();
            }
          };
      var first = executor.submit(upload);
      var second = executor.submit(upload);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      authenticate();
      var rejected = new MockHttpServletResponse();
      filter.doFilter(request(true), rejected, (req, res) -> fail("Rejected upload body was read"));
      assertEquals(429, rejected.getStatus());
      release.countDown();
      assertEquals(200, first.get());
      assertEquals(200, second.get());
      var next = new MockHttpServletResponse();
      filter.doFilter(request(true), next, (req, res) -> {});
      assertEquals(200, next.getStatus());
    } finally {
      release.countDown();
    }
  }
}
