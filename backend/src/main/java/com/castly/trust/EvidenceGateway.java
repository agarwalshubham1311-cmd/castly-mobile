package com.castly.trust;

public interface EvidenceGateway {
  boolean configured();

  void upload(String token, String path, byte[] png);

  String sign(String token, String path, java.time.Instant deadline);

  void remove(String token, String path);
}
