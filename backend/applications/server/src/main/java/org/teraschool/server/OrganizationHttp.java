package org.teraschool.server;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.teraschool.identity.api.*;
import org.teraschool.organization.api.*;

@RestController
@RequestMapping(produces = "application/json")
class OrganizationHttp {
  private final OrganizationQueries queries;
  private final CurrentActor actors;
  private final OrganizationCommands commands;
  private final DataDictionaries dictionaries;

  OrganizationHttp(
      OrganizationQueries queries,
      CurrentActor actors,
      OrganizationCommands commands,
      DataDictionaries dictionaries) {
    this.queries = queries;
    this.actors = actors;
    this.commands = commands;
    this.dictionaries = dictionaries;
  }

  public record CsrfView(String token, String headerName, String parameterName) {}

  @GetMapping("/api/session/csrf")
  CsrfView csrf(@io.swagger.v3.oas.annotations.Parameter(hidden = true) CsrfToken token) {
    return new CsrfView(token.getToken(), token.getHeaderName(), token.getParameterName());
  }

  @GetMapping("/api/session/me")
  Actor me() {
    return actors.requireOrganizationReader();
  }

  @GetMapping("/api/session/context")
  SessionContext context() {
    var base = actors.sessionContext();
    var employeeName = queries.employeeDisplayName(base.accountId());
    return new SessionContext(
        base.accountId(),
        base.tenantId(),
        base.tenantName(),
        base.login(),
        employeeName == null ? base.displayName() : employeeName,
        base.tenantAdmin(),
        base.passwordChangeRequired());
  }

  @GetMapping("/api/session/permissions")
  CurrentPermissions permissions() {
    return actors.permissions();
  }

  public record OrganizationCapabilities(java.util.Set<java.util.UUID> editableSchoolIds) {}

  @GetMapping("/api/organization/capabilities")
  OrganizationCapabilities capabilities() {
    return new OrganizationCapabilities(actors.requireOrganizationMaintainer().schools());
  }

  @GetMapping("/api/organization/school-options")
  java.util.List<OrganizationUnit> schoolOptions(@RequestParam(defaultValue = "") String search) {
    return queries.schoolOptions(search);
  }

  @PostMapping(value = "/api/organization/units", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  OrganizationUnit create(@RequestBody CreateOrganizationUnit command) {
    return commands.createChild(command);
  }

  @PostMapping(value = "/api/organization/units/{id}/rename", consumes = "application/json")
  OrganizationUnit rename(
      @PathVariable java.util.UUID id, @RequestBody RenameOrganizationUnit command) {
    return commands.rename(id, command);
  }

  @GetMapping("/api/organization/units")
  OrganizationPage list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return queries.list(page, size);
  }

  @GetMapping("/api/organization/schools/{schoolId}/dictionaries/{dictionaryCode}/items")
  java.util.List<DataDictionaries.Item> dictionaryItems(
      @PathVariable java.util.UUID schoolId,
      @PathVariable String dictionaryCode,
      @RequestParam(defaultValue = "false") boolean includeInactive) {
    return dictionaries.list(schoolId, dictionaryCode, includeInactive);
  }

  @PostMapping(
      value = "/api/organization/schools/{schoolId}/dictionaries/{dictionaryCode}/items",
      consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  DataDictionaries.Item createDictionaryItem(
      @PathVariable java.util.UUID schoolId,
      @PathVariable String dictionaryCode,
      @RequestBody DataDictionaries.CreateItem command) {
    return dictionaries.create(schoolId, dictionaryCode, command);
  }

  @PostMapping(
      value = "/api/organization/dictionary-items/{id}/update",
      consumes = "application/json")
  DataDictionaries.Item updateDictionaryItem(
      @PathVariable java.util.UUID id, @RequestBody DataDictionaries.UpdateItem command) {
    return dictionaries.update(id, command);
  }
}
