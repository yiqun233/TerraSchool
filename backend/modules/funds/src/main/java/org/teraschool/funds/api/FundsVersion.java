package org.teraschool.funds.api;

/** 只带版本的撤销/释放动作。 */
public record FundsVersion(Long expectedVersion) {}
