package com.castly.booking;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
@RestController @RequestMapping("/api/v1/bookings") public class BookingController {
 private final JdbcClient db;
 public BookingController(JdbcClient db){this.db=db;}
 public record CreateBooking(@NotNull UUID talentId,@NotBlank String projectType,@NotBlank String location,@NotBlank String requirements,@NotNull java.time.LocalDate proposedDate,@NotNull @Positive java.math.BigDecimal budget){}
 @GetMapping public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt){UUID me=UUID.fromString(jwt.getSubject());return db.sql("select * from public.bookings where client_id=:me or talent_id=:me order by created_at desc limit 100").param("me",me).query().listOfRows();}
 @GetMapping("/{id}") public Map<String,Object> get(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){UUID me=UUID.fromString(jwt.getSubject());return db.sql("select * from public.bookings where id=:id and (client_id=:me or talent_id=:me)").param("id",id).param("me",me).query().listOfRows().stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}
}
