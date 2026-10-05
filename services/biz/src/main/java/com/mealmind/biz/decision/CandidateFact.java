package com.mealmind.biz.decision;

import java.util.List;

import com.mealmind.biz.dto.SpecGroupView;

/**
 * 业务服务侧的候选事实：除回填 Agent 的打分输入外，
 * 还带上加购所需的信息（规格组）与约束标记（略超预算的次选）。
 */
public record CandidateFact(AgentCandidate payload, List<SpecGroupView> specGroups, boolean overBudget) {
}