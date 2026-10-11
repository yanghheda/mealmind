package com.mealmind.biz.dto;

import com.mealmind.biz.entity.Preference;

/** 接口 12/13/14：偏好记忆条目视图。 */
public record PreferenceView(
        String id,
        String type,
        String content,
        String source,
        String status,
        int seenCount) {

    public static PreferenceView of(Preference preference) {
        return new PreferenceView(
                preference.getId(),
                preference.getType(),
                preference.getContent(),
                preference.getSource(),
                preference.getStatus(),
                preference.getSeenCount() == null ? 1 : preference.getSeenCount());
    }
}