package com.castly.subscription;
import static com.castly.api.MarketplaceController.actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/subscriptions/google-play")
public class GooglePlayController {
 public record Purchase(@NotBlank @Size(min=16,max=4096) @Pattern(regexp="\\S+") String purchaseToken){}
 public record Message(@com.fasterxml.jackson.annotation.JsonProperty("messageId") @NotBlank @Size(max=200) String messageId,@NotBlank @Size(max=22000) String data, @com.fasterxml.jackson.annotation.JsonProperty("publishTime") String publishTime, Map<String,String> attributes){}
 public record Delivery(@Valid @NotNull Message message,@Size(max=500) String subscription){}
 private final GooglePlayService service;
 public GooglePlayController(GooglePlayService service){this.service=service;}
 @GetMapping("/configuration") public Map<String,Object> config(@AuthenticationPrincipal Jwt jwt){return service.configuration(actor(jwt));}
 @PostMapping({"/verify","/restore"}) public Map<String,Object> verify(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Purchase body){return service.verify(actor(jwt),body.purchaseToken());}
 @PostMapping("/rtdn") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void rtdn(@Valid @RequestBody Delivery body){service.rtdn(body.message().messageId(),body.message().data());}
}
