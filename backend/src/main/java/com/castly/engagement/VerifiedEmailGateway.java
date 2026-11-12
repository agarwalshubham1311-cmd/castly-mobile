package com.castly.engagement;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class VerifiedEmailGateway {
  private final com.castly.trust.TrustedContactService contacts;

  public VerifiedEmailGateway(com.castly.trust.TrustedContactService contacts) {
    this.contacts = contacts;
  }

  public Optional<String> verified(String token, UUID actor) {
    return contacts.verifiedEmail(token, actor);
  }
}
