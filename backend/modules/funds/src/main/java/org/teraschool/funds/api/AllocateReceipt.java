package org.teraschool.funds.api;

import java.util.UUID;

/** 把某笔收款的一部分分配到一条报名；金额不得超出可用收款与报名未收金额。 */
public record AllocateReceipt(UUID receiptId, UUID enrollmentId, String amount) {}
