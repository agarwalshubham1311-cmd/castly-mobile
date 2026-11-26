package com.castly.enforcement;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class EnforcementBodyLimitTest {
  @Test
  void declaredAndStreamingBodiesCannotExceedTheBound() throws Exception {
    for (String path :
        java.util.List.of(
            "/api/v1/account/appeals",
            "/api/v1/admin/users/00000000-0000-0000-0000-000000000080/access")) {
      var known = new MockHttpServletRequest("POST", path);
      known.setContent(new byte[16385]);
      var response = new MockHttpServletResponse();
      new EnforcementBodyLimit()
          .doFilter(
              known,
              response,
              (req, res) -> fail("Oversized declared body must not reach deserialization"));
      assertEquals(413, response.getStatus());
      var unknown =
          new MockHttpServletRequest("POST", path) {
            @Override
            public long getContentLengthLong() {
              return -1;
            }
          };
      unknown.setContent(new byte[16385]);
      assertThrows(
          EnforcementBodyLimit.TooLarge.class,
          () ->
              new EnforcementBodyLimit()
                  .doFilter(
                      unknown,
                      new MockHttpServletResponse(),
                      (req, res) ->
                          req.getInputStream()
                              .transferTo(java.io.OutputStream.nullOutputStream())));
    }
  }

  @Test
  void existingBookingAndEvidenceBodiesAreUnaffected() throws Exception {
    for (String path :
        java.util.List.of(
            "/api/v1/bookings", "/api/v1/disputes/00000000-0000-0000-0000-000000000080/images")) {
      var request = new MockHttpServletRequest("POST", path);
      request.setContent(new byte[16385]);
      var size = new java.util.concurrent.atomic.AtomicInteger();
      new EnforcementBodyLimit()
          .doFilter(
              request,
              new MockHttpServletResponse(),
              (req, res) -> size.set(req.getInputStream().readAllBytes().length));
      assertEquals(16385, size.get());
    }
  }
}
