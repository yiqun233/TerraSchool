package org.teraschool.server;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.teraschool.organization.api.PersonnelAttachments;

@RestController
@RequestMapping("/api/organization/employees/{employee}/attachments")
class PersonnelAttachmentHttp {
  private final PersonnelAttachments attachments;

  PersonnelAttachmentHttp(PersonnelAttachments attachments) {
    this.attachments = attachments;
  }

  @GetMapping(produces = "application/json")
  java.util.List<PersonnelAttachments.AttachmentView> list(@PathVariable UUID employee) {
    return attachments.listAttachments(employee);
  }

  @PostMapping(consumes = "multipart/form-data", produces = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  PersonnelAttachments.AttachmentView upload(
      @PathVariable UUID employee,
      @RequestParam UUID requestId,
      @RequestPart("file") MultipartFile file)
      throws java.io.IOException {
    if (file.getSize() > 5242880) throw new IllegalArgumentException("附件超过5MB");
    return attachments.createAttachment(
        employee,
        new PersonnelAttachments.UploadAttachment(
            requestId, file.getOriginalFilename(), file.getBytes()));
  }

  @GetMapping(value = "/{id}", produces = "application/octet-stream")
  ResponseEntity<byte[]> download(@PathVariable UUID employee, @PathVariable UUID id) {
    var file = attachments.readAttachment(employee, id);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.name(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .cacheControl(CacheControl.noStore())
        .body(file.content());
  }

  @PostMapping(value = "/{id}/remove", consumes = "application/json")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void remove(
      @PathVariable UUID employee,
      @PathVariable UUID id,
      @RequestBody PersonnelAttachments.RemoveAttachment input) {
    attachments.removeAttachment(employee, id, input);
  }
}
