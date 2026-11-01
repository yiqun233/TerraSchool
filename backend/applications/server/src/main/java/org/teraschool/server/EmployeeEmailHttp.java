package org.teraschool.server;

import org.springframework.web.bind.annotation.*;
import org.teraschool.organization.api.EmployeeEmailConfiguration;

@RestController
@RequestMapping(value = "/api/employee/me/email-config", produces = "application/json")
class EmployeeEmailHttp {
  private final EmployeeEmailConfiguration configuration;

  EmployeeEmailHttp(EmployeeEmailConfiguration configuration) {
    this.configuration = configuration;
  }

  @GetMapping
  EmployeeEmailConfiguration.EmailConfigurationView get() {
    return configuration.getMyEmailConfiguration();
  }

  @PostMapping(consumes = "application/json")
  EmployeeEmailConfiguration.EmailConfigurationView update(
      @RequestBody EmployeeEmailConfiguration.ChangeEmailConfiguration input) {
    return configuration.changeMyEmailConfiguration(input);
  }
}
