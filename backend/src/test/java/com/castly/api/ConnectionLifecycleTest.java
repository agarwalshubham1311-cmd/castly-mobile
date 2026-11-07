package com.castly.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;

class ConnectionLifecycleTest {
  @Test
  void dnsFailureAffectsNewConnectionsNotHealthySocketsAndRecoversWithoutReplayingBusinessWork()
      throws Exception {
    AtomicBoolean dnsUnavailable = new AtomicBoolean(false);
    AtomicInteger opened = new AtomicInteger(), writes = new AtomicInteger();
    DataSource transport = mock(DataSource.class);
    when(transport.getLoginTimeout()).thenReturn(5);
    when(transport.getConnection())
        .thenAnswer(
            invocation -> {
              if (dnsUnavailable.get())
                throw new SQLException(
                    "Synthetic connection failure",
                    "08001",
                    new UnknownHostException("synthetic.invalid"));
              opened.incrementAndGet();
              Connection physical = mock(Connection.class);
              AtomicBoolean closed = new AtomicBoolean();
              when(physical.isValid(anyInt())).thenAnswer(v -> !closed.get());
              when(physical.isClosed()).thenAnswer(v -> closed.get());
              when(physical.getAutoCommit()).thenReturn(true);
              when(physical.getTransactionIsolation())
                  .thenReturn(Connection.TRANSACTION_READ_COMMITTED);
              doAnswer(
                      v -> {
                        closed.set(true);
                        return null;
                      })
                  .when(physical)
                  .close();
              return physical;
            });
    HikariConfig config = new HikariConfig();
    config.setDataSource(transport);
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(1000);
    config.setInitializationFailTimeout(-1); // Synthetic test only, not production configuration.
    config.setPoolName("synthetic-dns-lifecycle");
    try (HikariDataSource pool = new HikariDataSource(config)) {
      try (Connection first = pool.getConnection()) {
        assertFalse(first.isClosed());
      }
      dnsUnavailable.set(true);
      try (Connection reused = pool.getConnection()) {
        assertFalse(reused.isClosed());
      }
      assertEquals(1, opened.get(), "A healthy pooled socket does not perform a fresh DNS lookup");
      pool.getHikariPoolMXBean()
          .softEvictConnections(); // Simulated replacement; never touches host.
      var data = new ActorDatabase(pool, new DataSourceTransactionManager(pool));
      var failure =
          assertThrows(
              CannotCreateTransactionException.class,
              () -> data.as(null, db -> writes.incrementAndGet()));
      assertEquals("dns_resolution_failed", SqlFailureKind.of(failure));
      assertEquals("08001", SqlFailureKind.sqlState(failure));
      assertEquals(
          0, writes.get(), "Connection acquisition must not execute or replay business work");
      dnsUnavailable.set(false);
      pool.setConnectionTimeout(5000); // Allow the creator's existing bounded backoff to finish.
      try (Connection recovered = pool.getConnection()) {
        assertFalse(recovered.isClosed());
      }
      assertEquals(2, opened.get());
      assertEquals(0, writes.get());
    }
  }
}
