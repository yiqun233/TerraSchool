package org.teraschool.server;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.teraschool.identity.api.RoleTemplateManagement;
import org.teraschool.identity.api.RoleTemplateManagement.*;

@RestController
@RequestMapping(value = "/api/authorization/role-templates", produces = "application/json")
class RoleTemplateHttp {
  private final RoleTemplateManagement roles;

  RoleTemplateHttp(RoleTemplateManagement roles) {
    this.roles = roles;
  }

  @GetMapping
  List<RoleSummary> list(
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "false") boolean includeInactive) {
    return roles.listRoles(search, includeInactive);
  }

  @GetMapping("/starting")
  List<StartingTemplate> starting() {
    return roles.startingTemplates();
  }

  @GetMapping("/{id}")
  RoleTemplate get(@PathVariable UUID id) {
    return roles.getRole(id);
  }

  @PostMapping(consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  RoleTemplate create(@RequestBody CreateRole input) {
    return roles.createRole(input);
  }

  @PostMapping(value = "/from-template", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  RoleTemplate fromTemplate(@RequestBody GenerateFromTemplate input) {
    return roles.generateFromTemplate(input);
  }

  @PostMapping(value = "/{id}/replace", consumes = "application/json")
  RoleTemplate replace(@PathVariable UUID id, @RequestBody RoleChange input) {
    return roles.replaceRole(id, input);
  }
}
