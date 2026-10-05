package com.mealmind.biz.entity;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 决策候选与综合分 decision_candidate（主键为 run_id + dish_id）。 */
@TableName("decision_candidate")
public class DecisionCandidate {

    @TableId(value = "run_id", type = IdType.INPUT)
    private String runId;
    private String dishId;
    private BigDecimal compositeScore;
    private BigDecimal compositeConfidence;
    private Integer rankNo;
    private Integer isShown;
    private Integer isAddedToCart;

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getDishId() {
        return dishId;
    }

    public void setDishId(String dishId) {
        this.dishId = dishId;
    }

    public BigDecimal getCompositeScore() {
        return compositeScore;
    }

    public void setCompositeScore(BigDecimal compositeScore) {
        this.compositeScore = compositeScore;
    }

    public BigDecimal getCompositeConfidence() {
        return compositeConfidence;
    }

    public void setCompositeConfidence(BigDecimal compositeConfidence) {
        this.compositeConfidence = compositeConfidence;
    }

    public Integer getRankNo() {
        return rankNo;
    }

    public void setRankNo(Integer rankNo) {
        this.rankNo = rankNo;
    }

    public Integer getIsShown() {
        return isShown;
    }

    public void setIsShown(Integer isShown) {
        this.isShown = isShown;
    }

    public Integer getIsAddedToCart() {
        return isAddedToCart;
    }

    public void setIsAddedToCart(Integer isAddedToCart) {
        this.isAddedToCart = isAddedToCart;
    }
}