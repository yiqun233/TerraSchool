package org.teraschool.server;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.teraschool.admissions.api.*;

@RestController
@RequestMapping(value = "/api/admissions/channels", produces = "application/json")
class ChannelHttp {
  private final ChannelQueries queries;
  private final ChannelCommands commands;

  ChannelHttp(ChannelQueries queries, ChannelCommands commands) {
    this.queries = queries;
    this.commands = commands;
  }

  @GetMapping("/capabilities")
  ChannelQueries.ChannelCapabilities capabilities() {
    return queries.capabilities();
  }

  @GetMapping
  ChannelQueries.ChannelPage list(
      @RequestParam UUID schoolId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return queries.list(schoolId, page, size);
  }

  @PostMapping(consumes = "application/json")
  Channel create(@RequestBody ChannelCommands.ChannelCreate command) {
    return commands.create(command);
  }

  @GetMapping("/{id}/address")
  org.springframework.http.ResponseEntity<ChannelQueries.ChannelAddress> address(
      @PathVariable UUID id) {
    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(queries.address(id));
  }

  @PostMapping(value = "/{id}/address", consumes = "application/json")
  org.springframework.http.ResponseEntity<ChannelQueries.ChannelAddress> updateAddress(
      @PathVariable UUID id, @RequestBody ChannelCommands.ChannelAddressUpdate command) {
    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(commands.updateAddress(id, command));
  }

  @PostMapping(value = "/{id}/update", consumes = "application/json")
  Channel update(@PathVariable UUID id, @RequestBody ChannelCommands.ChannelUpdate command) {
    return commands.update(id, command);
  }
}
