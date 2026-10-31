package org.teraschool.verification;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 验证 RFC 9457 表示；不回传输入原文、数据库异常或堆栈。 */
@RestControllerAdvice
class ProbeErrors {
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied() { return problem(403, "ACCESS_DENIED", "没有执行该操作的权限"); }

    @ExceptionHandler({ConstraintViolationException.class, IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid() { return problem(400, "INVALID_INPUT", "输入不符合验证契约"); }

    private ResponseEntity<ProblemDetail> problem(int status, String code, String detail) {
        var result = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        result.setType(URI.create("urn:teraschool:verification:" + code.toLowerCase()));
        result.setProperty("code", code);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(result);
    }
}
