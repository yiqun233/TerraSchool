package org.teraschool.server;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.teraschool.admissions.api.*;

@RestController
@RequestMapping(value = "/api/admissions/customers", produces = "application/json")
class CustomerHttp {
  private final CustomerQueries queries;
  private final CustomerCommands commands;

  CustomerHttp(CustomerQueries queries, CustomerCommands commands) {
    this.queries = queries;
    this.commands = commands;
  }

  @GetMapping("/capabilities")
  ResponseEntity<CustomerQueries.CustomerCapabilities> capabilities() {
    return noStore(queries.capabilities());
  }

  @GetMapping
  ResponseEntity<CustomerQueries.CustomerPage> list(
      @RequestParam UUID schoolId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return noStore(queries.list(schoolId, page, size));
  }

  @PostMapping(consumes = "application/json")
  ResponseEntity<CustomerCommands.CustomerCreateResult> create(
      @RequestBody CustomerCommands.CustomerCreate command) {
    return noStore(commands.create(command));
  }

  @PostMapping(value = "/cancel", consumes = "application/json")
  ResponseEntity<CustomerCommands.CustomerCreateResult> cancel(
      @RequestBody CustomerCommands.CustomerCreate command) {
    return noStore(commands.cancel(command));
  }

  @PostMapping("/{id}/phone")
  ResponseEntity<CustomerCommands.CustomerPhone> phone(@PathVariable UUID id) {
    return noStore(commands.revealPhone(id));
  }

  private static <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
