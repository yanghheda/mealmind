package com.mealmind.biz.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** Golden 评测用例 golden_case。 */
@TableName("golden_case")
public class GoldenCase {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String caseType;
    private String inputText;
    private String expectedConstraints;
    private String expectedPoints;
    private String rubricVersion;
    private String annotatedBy;
    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCaseType() {
        return caseType;
    }

    public void setCaseType(String caseType) {
        this.caseType = caseType;
    }

    public String getInputText() {
        return inputText;
    }

    public void setInputText(String inputText) {
        this.inputText = inputText;
    }

    public String getExpectedConstraints() {
        return expectedConstraints;
    }

    public void setExpectedConstraints(String expectedConstraints) {
        this.expectedConstraints = expectedConstraints;
    }

    public String getExpectedPoints() {
        return expectedPoints;
    }

    public void setExpectedPoints(String expectedPoints) {
        this.expectedPoints = expectedPoints;
    }

    public String getRubricVersion() {
        return rubricVersion;
    }

    public void setRubricVersion(String rubricVersion) {
        this.rubricVersion = rubricVersion;
    }

    public String getAnnotatedBy() {
        return annotatedBy;
    }

    public void setAnnotatedBy(String annotatedBy) {
        this.annotatedBy = annotatedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
