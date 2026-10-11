package com.mealmind.biz.dto;

import java.util.List;

/** 澄清回答请求（M4 W2 追问）：answers 为逐题选择，skipped 为一键跳过整轮追问。 */
public record ClarifyAnswersRequest(List<Answer> answers, boolean skipped) {

    /** 单题回答：optionId 为 null 表示跳过该问题（后端按「不填」处理）。 */
    public record Answer(String questionId, String optionId) {
    }

    public List<Answer> answersOrEmpty() {
        return answers == null ? List.of() : answers;
    }
}