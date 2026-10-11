package com.mealmind.biz.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 偏好记忆 preference（技术方案 §5.2 / PRD 4.8）。
 * status 语义：ACTIVE 生效 / PENDING 观察中（跨 run 重复出现才升级）/ IGNORED 墓碑（同内容不再自动写入）。
 */
@TableName("preference")
public class Preference {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String sessionId;
    private String type;
    private String content;
    private String source;
    private String status;
    private Integer seenCount;
    private String lastSeenRunId;
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getSeenCount() {
        return seenCount;
    }

    public void setSeenCount(Integer seenCount) {
        this.seenCount = seenCount;
    }

    public String getLastSeenRunId() {
        return lastSeenRunId;
    }

    public void setLastSeenRunId(String lastSeenRunId) {
        this.lastSeenRunId = lastSeenRunId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}