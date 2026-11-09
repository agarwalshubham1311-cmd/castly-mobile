package com.castly.subscription;

import com.fasterxml.jackson.databind.JsonNode;

public interface GooglePlayGateway {
  JsonNode get(String packageName, String token);

  void acknowledge(String packageName, String product, String token);

  boolean configured();
}
