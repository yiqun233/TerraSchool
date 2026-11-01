package org.teraschool.server;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.teraschool.organization.api.*;
import org.teraschool.organization.api.ResourceManagement.*;

@RestController
@RequestMapping(value = "/api/organization", produces = "application/json")
class ResourcesHttp {
  private final SchoolManagement schools;
  private final ResourceManagement resources;
  private final TeacherQualifications qualifications;
  private final EmployeeAccountLinks links;
  private final PersonnelManagement personnel;
  private final PersonnelExternalProfile external;

  ResourcesHttp(
      SchoolManagement schools,
      ResourceManagement resources,
      TeacherQualifications qualifications,
      EmployeeAccountLinks links,
      PersonnelManagement personnel,
      PersonnelExternalProfile external) {
    this.schools = schools;
    this.resources = resources;
    this.qualifications = qualifications;
    this.links = links;
    this.personnel = personnel;
    this.external = external;
  }

  @GetMapping("/employees/{id}/external-identifiers")
  PersonnelExternalProfile.ExternalProfileView external(@PathVariable UUID id) {
    return external.getExternalProfile(id);
  }

  @PostMapping(value = "/employees/{id}/external-identifiers", consumes = "application/json")
  PersonnelExternalProfile.ExternalProfileView external(
      @PathVariable UUID id, @RequestBody PersonnelExternalProfile.UpdateExternalProfile input) {
    return external.updateExternalProfile(id, input);
  }

  @GetMapping("/employees/{id}/personnel")
  PersonnelManagement.PersonnelView personnel(@PathVariable UUID id) {
    return personnel.getPersonnel(id);
  }

  @PostMapping(value = "/employees/{id}/personnel", consumes = "application/json")
  PersonnelManagement.PersonnelView personnel(
      @PathVariable UUID id, @RequestBody PersonnelManagement.UpdatePersonnel input) {
    return personnel.updatePersonnel(id, input);
  }

  @GetMapping("/employees/{id}/account-link")
  EmployeeAccountLinks.AccountLink link(@PathVariable UUID id) {
    return links.getLink(id);
  }

  @PostMapping(value = "/employees/{id}/account-link", consumes = "application/json")
  EmployeeAccountLinks.AccountLink link(
      @PathVariable UUID id, @RequestBody EmployeeAccountLinks.ChangeAccountLink input) {
    return links.changeLink(id, input);
  }

  @GetMapping("/teachers/{id}/qualification")
  TeacherQualifications.QualificationView qualification(@PathVariable UUID id) {
    return qualifications.getQualification(id);
  }

  @PostMapping(value = "/teachers/{id}/qualification", consumes = "application/json")
  TeacherQualifications.QualificationView qualification(
      @PathVariable UUID id, @RequestBody TeacherQualifications.UpdateQualification input) {
    return qualifications.updateQualification(id, input);
  }

  record SchoolCapabilities(boolean canCreate) {}

  @GetMapping("/schools/capabilities")
  SchoolCapabilities schoolCapabilities() {
    return new SchoolCapabilities(schools.canCreate());
  }

  @PostMapping(value = "/schools", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  OrganizationUnit createSchool(@RequestBody SchoolManagement.CreateSchool input) {
    return schools.createSchool(input);
  }

  @GetMapping("/schools/{id}")
  SchoolManagement.SchoolView school(@PathVariable UUID id) {
    return schools.getSchool(id);
  }

  @PostMapping(value = "/schools/{id}/update", consumes = "application/json")
  SchoolManagement.SchoolView updateSchool(
      @PathVariable UUID id, @RequestBody SchoolManagement.UpdateSchool input) {
    return schools.updateSchool(id, input);
  }

  @GetMapping("/resources/{kind}/capabilities")
  ResourceCapabilities capabilities(@PathVariable Kind kind) {
    return resources.capabilities(kind);
  }

  @GetMapping("/resources/{kind}")
  ResourcePage list(
      @PathVariable Kind kind,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return resources.list(kind, page, size);
  }

  @GetMapping("/resources/{kind}/options")
  java.util.List<ResourceOption> options(
      @PathVariable Kind kind,
      @RequestParam String type,
      @RequestParam(required = false) UUID schoolId,
      @RequestParam(defaultValue = "") String search) {
    return resources.options(kind, type, schoolId, search);
  }

  @PostMapping(value = "/resources/{kind}", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  ResourceView create(@PathVariable Kind kind, @RequestBody CreateResource input) {
    return resources.create(kind, input);
  }

  @PostMapping(value = "/resources/{kind}/{id}/update", consumes = "application/json")
  ResourceView update(
      @PathVariable Kind kind, @PathVariable UUID id, @RequestBody UpdateResource input) {
    return resources.update(kind, id, input);
  }
}
