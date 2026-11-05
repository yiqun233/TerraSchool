package org.teraschool.enrollment.api;

import java.time.LocalDate;
import java.util.UUID;

/** Execute an approved change to the enrollment's frozen study end date. */
public record DeferEnrollment(UUID approvalId, LocalDate validUntil) {}
