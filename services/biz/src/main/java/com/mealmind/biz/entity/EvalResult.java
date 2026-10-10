package com.mealmind.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 单条用例评测结果 eval_result。 */
@TableName("eval_result")
public class EvalResult {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String batchId;
    private String caseId;
    private Integer passed;
    private String failDetailJson;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public Integer getPassed() {
        return passed;
    }

    public void setPassed(Integer passed) {
        this.passed = passed;
    }

    public String getFailDetailJson() {
        return failDetailJson;
    }

    public void setFailDetailJson(String failDetailJson) {
        this.failDetailJson = failDetailJson;
    }
}
