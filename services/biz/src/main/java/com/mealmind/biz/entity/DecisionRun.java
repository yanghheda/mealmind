package com.mealmind.biz.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 决策记录 decision_run（技术方案 §5.2）。 */
@TableName("decision_run")
public class DecisionRun {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String sessionId;
    private String inputText;
    private String profileJson;
    private String profileFingerprint;
    private String mode;
    private String merchantId;
    private String status;
    private String degradeLevel;
    private String rubricVersion;
    private Integer candidateCount;
    private String filteredDetail;
    private Integer latencyMs;
    private BigDecimal costYuan;
    /** 本次带入的长期偏好 id（JSON 数组字符串；M4 偏好记忆）。 */
    private String appliedPreferenceIds;
    /** 待答澄清问题（clarify 事件载荷 JSON；M4 W2 追问）。 */
    private String clarifyJson;
    /** 澄清状态：PENDING / ANSWERED / SKIPPED（M4 W2 追问；null 表示本轮未追问）。 */
    private String clarifyState;
    /** REFINE 继承来源 run（接口 18 修正；M4 W3）。 */
    private String parentRunId;
    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getInputText() {
        return inputText;
    }

    public void setInputText(String inputText) {
        this.inputText = inputText;
    }

    public String getProfileJson() {
        return profileJson;
    }

    public void setProfileJson(String profileJson) {
        this.profileJson = profileJson;
    }

    public String getProfileFingerprint() {
        return profileFingerprint;
    }

    public void setProfileFingerprint(String profileFingerprint) {
        this.profileFingerprint = profileFingerprint;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDegradeLevel() {
        return degradeLevel;
    }

    public void setDegradeLevel(String degradeLevel) {
        this.degradeLevel = degradeLevel;
    }

    public String getRubricVersion() {
        return rubricVersion;
    }

    public void setRubricVersion(String rubricVersion) {
        this.rubricVersion = rubricVersion;
    }

    public Integer getCandidateCount() {
        return candidateCount;
    }

    public void setCandidateCount(Integer candidateCount) {
        this.candidateCount = candidateCount;
    }

    public String getFilteredDetail() {
        return filteredDetail;
    }

    public void setFilteredDetail(String filteredDetail) {
        this.filteredDetail = filteredDetail;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public BigDecimal getCostYuan() {
        return costYuan;
    }

    public void setCostYuan(BigDecimal costYuan) {
        this.costYuan = costYuan;
    }

    public String getAppliedPreferenceIds() {
        return appliedPreferenceIds;
    }

    public void setAppliedPreferenceIds(String appliedPreferenceIds) {
        this.appliedPreferenceIds = appliedPreferenceIds;
    }

    public String getClarifyJson() {
        return clarifyJson;
    }

    public void setClarifyJson(String clarifyJson) {
        this.clarifyJson = clarifyJson;
    }

    public String getClarifyState() {
        return clarifyState;
    }

    public void setClarifyState(String clarifyState) {
        this.clarifyState = clarifyState;
    }

    public String getParentRunId() {
        return parentRunId;
    }

    public void setParentRunId(String parentRunId) {
        this.parentRunId = parentRunId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}