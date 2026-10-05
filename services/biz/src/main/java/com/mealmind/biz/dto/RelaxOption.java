package com.mealmind.biz.dto;

/** 无候选时的可放宽条件：交给前端一键重算（以 text 作为新的需求语句发起）。 */
public record RelaxOption(String type, String label, String text) {
}