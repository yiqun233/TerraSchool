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
  private final CustomerFollowupQueries followupQueries;
  private final CustomerFollowupCommands followupCommands;
  private final CustomerConversionService conversions;

  CustomerHttp(
      CustomerQueries queries,
      CustomerCommands commands,
      CustomerFollowupQueries followupQueries,
      CustomerFollowupCommands followupCommands,
      CustomerConversionService conversions) {
    this.queries = queries;
    this.commands = commands;
    this.followupQueries = followupQueries;
    this.followupCommands = followupCommands;
    this.conversions = conversions;
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

  @GetMapping("/pool")
  ResponseEntity<CustomerQueries.CustomerPage> pool(
      @RequestParam UUID schoolId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return noStore(queries.pool(schoolId, page, size));
  }

  @PostMapping(value = "/{id}/release-to-pool", consumes = "application/json")
  ResponseEntity<Customer> releaseToPool(
      @PathVariable UUID id, @RequestBody CustomerPoolCommand command) {
    return noStore(commands.releaseToPool(id, command.expectedVersion()));
  }

  @PostMapping(value = "/{id}/claim", consumes = "application/json")
  ResponseEntity<Customer> claim(@PathVariable UUID id, @RequestBody CustomerPoolCommand command) {
    return noStore(commands.claim(id, command.expectedVersion()));
  }

  @GetMapping("/{id}/merges")
  ResponseEntity<java.util.List<CustomerQueries.MergedCustomer>> mergedInto(@PathVariable UUID id) {
    return noStore(queries.mergedInto(id));
  }

  @PostMapping(value = "/{id}/merge", consumes = "application/json")
  ResponseEntity<CustomerMergeResult> merge(
      @PathVariable UUID id, @RequestBody CustomerMergeCommand command) {
    return noStore(
        commands.merge(
            id,
            command.primaryId(),
            command.expectedSourceVersion(),
            command.expectedPrimaryVersion()));
  }

  @PostMapping(value = "/{id}/merge/reverse", consumes = "application/json")
  ResponseEntity<CustomerMergeResult> reverseMerge(
      @PathVariable UUID id, @RequestBody CustomerPoolCommand command) {
    return noStore(commands.reverseMerge(id, command.expectedVersion()));
  }

  @GetMapping("/{id}/student-link")
  ResponseEntity<CustomerStudentLinkView> studentLink(@PathVariable UUID id) {
    return noStore(conversions.link(id));
  }

  @PostMapping(value = "/{id}/convert-to-student", consumes = "application/json")
  ResponseEntity<CustomerConversionResult> convertToStudent(
      @PathVariable UUID id, @RequestBody ConvertCustomerToStudent command) {
    return noStore(conversions.convert(id, command));
  }

  record CustomerPoolCommand(long expectedVersion) {}

  record CustomerMergeCommand(
      UUID primaryId, long expectedSourceVersion, long expectedPrimaryVersion) {}

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

  @GetMapping("/{id}/followups")
  ResponseEntity<CustomerFollowupQueries.FollowupPage> followups(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return noStore(followupQueries.list(id, page, size));
  }

  @GetMapping("/reminders")
  ResponseEntity<CustomerFollowupQueries.ReminderPage> reminders(
      @RequestParam UUID schoolId,
      @RequestParam String throughOn,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return noStore(followupQueries.reminders(schoolId, throughOn, page, size));
  }

  @PostMapping(value = "/{id}/followups", consumes = "application/json")
  ResponseEntity<CustomerFollowupQueries.Followup> addFollowup(
      @PathVariable UUID id, @RequestBody CustomerFollowupCommands.FollowupCreate command) {
    return noStore(followupCommands.create(id, command));
  }

  private static <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
