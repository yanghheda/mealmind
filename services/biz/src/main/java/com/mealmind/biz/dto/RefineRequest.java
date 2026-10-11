package com.mealmind.biz.dto;

import java.util.List;

/**
 * 接口 18 请求体：修正（REFINE，基于原 run 新建继承 run）或换一批
 * （NEXT_BATCH，同 run 从已落库候选里取下一组）。
 */
public record RefineRequest(
        String type,
        String text,
        List<String> excludeDishIds,
        Integer pageSize) {
}