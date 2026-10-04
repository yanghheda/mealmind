package com.mealmind.biz.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 规格分组。 */
public record SpecGroupView(
        String groupId,
        String groupName,
        @JsonProperty("isRequired") boolean isRequired,
        List<SpecOptionView> options) {
}
