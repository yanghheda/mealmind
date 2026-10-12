package com.mealmind.biz.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mealmind.* 业务配置。
 */
@ConfigurationProperties(prefix = "mealmind")
public class AppProperties {

    /** Agent 运行时内部地址（M1 占位）。 */
    private String agentBaseUrl = "http://localhost:8000";

    /** 服务间令牌（M1 占位）。 */
    private String internalToken = "";

    /** Redis 键前缀。 */
    private String redisPrefix = "mealmind:";

    /** 图片资源基地址；留空输出相对路径 /images/xxx。 */
    private String imageBaseUrl = "";

    /** 游客昵称前缀。 */
    private String nicknamePrefix = "干饭人_";

    /** 分页每页默认条数。 */
    private int pageSizeDefault = 10;

    /** 分页每页上限。 */
    private int pageSizeMax = 50;

    /** 商家列表 / 菜单缓存 TTL（秒）。 */
    private long cacheTtlSeconds = 300;

    /** 品类 -> 图片文件名映射。以 name/file 列表表达：Spring Boot 的属性名元素仅支持 ASCII，
     *  非 ASCII 的 map key 无法绑定，故用列表承载（值可为中文）。 */
    private List<CategoryImage> imageCategories = new ArrayList<>();

    /** 决策链路配置（技术方案 §8.5）。 */
    private Decision decision = new Decision();

    /** 成本计价配置（M4 W5，PRD §5.8 标价口径）。 */
    private Cost cost = new Cost();

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision;
    }

    public Cost getCost() {
        return cost;
    }

    public void setCost(Cost cost) {
        this.cost = cost;
    }

    /** 计价参数：qwen 按每百万 token 单价，决策模型按每次调用单价；gate 为单次决策成本门限。 */
    public static class Cost {

        /** qwen 输入单价（元 / 百万 token）。 */
        private BigDecimal qwenInputPerMillion = new BigDecimal("12");

        /** qwen 输出单价（元 / 百万 token）。 */
        private BigDecimal qwenOutputPerMillion = new BigDecimal("36");

        /** 决策模型单次调用单价（元）；decision-model-preview 限时免费，恢复计费后按官方单价更新。 */
        private BigDecimal decisionPerCall = BigDecimal.ZERO;

        /** 单次决策成本门限（元）：看板 overGate 判定。 */
        private BigDecimal gateYuan = new BigDecimal("0.01");

        public BigDecimal getQwenInputPerMillion() {
            return qwenInputPerMillion;
        }

        public void setQwenInputPerMillion(BigDecimal qwenInputPerMillion) {
            this.qwenInputPerMillion = qwenInputPerMillion;
        }

        public BigDecimal getQwenOutputPerMillion() {
            return qwenOutputPerMillion;
        }

        public void setQwenOutputPerMillion(BigDecimal qwenOutputPerMillion) {
            this.qwenOutputPerMillion = qwenOutputPerMillion;
        }

        public BigDecimal getDecisionPerCall() {
            return decisionPerCall;
        }

        public void setDecisionPerCall(BigDecimal decisionPerCall) {
            this.decisionPerCall = decisionPerCall;
        }

        public BigDecimal getGateYuan() {
            return gateYuan;
        }

        public void setGateYuan(BigDecimal gateYuan) {
            this.gateYuan = gateYuan;
        }
    }

    /** 决策链路：权重、候选上限、整轮超时、幂等与并发限制、档位版本。 */
    public static class Decision {

        /** 综合分权重（PRD 5.4 默认权重：口味 30 / 预算 25 / 时效 15 / 口碑 20 / 场景 10）。 */
        private Map<String, BigDecimal> weights = new LinkedHashMap<>(Map.of(
                "taste", new BigDecimal("0.30"),
                "budget", new BigDecimal("0.25"),
                "timeliness", new BigDecimal("0.15"),
                "reputation", new BigDecimal("0.20"),
                "scene", new BigDecimal("0.10")));

        /** 参与打分的候选上限（超过按评分与月售初排截断）。 */
        private int maxCandidates = 20;

        /** 整轮决策超时（毫秒），超时返回已得结果 + error。 */
        private int runTimeoutMs = 12000;

        /** 澄清等待窗口（毫秒）：收到 clarify 后看门狗换档为 runTimeoutMs + clarifyTimeoutMs（M4 W2）。 */
        private int clarifyTimeoutMs = 60000;

        /** 幂等窗口（秒）：同一会话相同 text + mode 视为重复。 */
        private int idempotencySeconds = 5;

        /** 同一会话并发 run 上限。 */
        private int maxConcurrentRuns = 3;

        /** 档位版本：改档位定义必须升版本号（缓存键与决策记录都引用它）。 */
        private String rubricVersion = "v1.2";

        /** SSE 心跳间隔（秒）。 */
        private int heartbeatSeconds = 15;

        /** 打分缓存 TTL（秒）：键含 rubric 版本与需求指纹，按菜品粒度复用（技术方案 §6.3）。 */
        private long scoreCacheTtlSeconds = 600;

        public long getScoreCacheTtlSeconds() {
            return scoreCacheTtlSeconds;
        }

        public void setScoreCacheTtlSeconds(long scoreCacheTtlSeconds) {
            this.scoreCacheTtlSeconds = scoreCacheTtlSeconds;
        }

        public Map<String, BigDecimal> getWeights() {
            return weights;
        }

        public void setWeights(Map<String, BigDecimal> weights) {
            this.weights = weights;
        }

        public int getMaxCandidates() {
            return maxCandidates;
        }

        public void setMaxCandidates(int maxCandidates) {
            this.maxCandidates = maxCandidates;
        }

        public int getRunTimeoutMs() {
            return runTimeoutMs;
        }

        public void setRunTimeoutMs(int runTimeoutMs) {
            this.runTimeoutMs = runTimeoutMs;
        }

        public int getClarifyTimeoutMs() {
            return clarifyTimeoutMs;
        }

        public void setClarifyTimeoutMs(int clarifyTimeoutMs) {
            this.clarifyTimeoutMs = clarifyTimeoutMs;
        }

        public int getIdempotencySeconds() {
            return idempotencySeconds;
        }

        public void setIdempotencySeconds(int idempotencySeconds) {
            this.idempotencySeconds = idempotencySeconds;
        }

        public int getMaxConcurrentRuns() {
            return maxConcurrentRuns;
        }

        public void setMaxConcurrentRuns(int maxConcurrentRuns) {
            this.maxConcurrentRuns = maxConcurrentRuns;
        }

        public String getRubricVersion() {
            return rubricVersion;
        }

        public void setRubricVersion(String rubricVersion) {
            this.rubricVersion = rubricVersion;
        }

        public int getHeartbeatSeconds() {
            return heartbeatSeconds;
        }

        public void setHeartbeatSeconds(int heartbeatSeconds) {
            this.heartbeatSeconds = heartbeatSeconds;
        }
    }

    public String getAgentBaseUrl() {
        return agentBaseUrl;
    }

    public void setAgentBaseUrl(String agentBaseUrl) {
        this.agentBaseUrl = agentBaseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public String getRedisPrefix() {
        return redisPrefix;
    }

    public void setRedisPrefix(String redisPrefix) {
        this.redisPrefix = redisPrefix;
    }

    public String getImageBaseUrl() {
        return imageBaseUrl;
    }

    public void setImageBaseUrl(String imageBaseUrl) {
        this.imageBaseUrl = imageBaseUrl;
    }

    public String getNicknamePrefix() {
        return nicknamePrefix;
    }

    public void setNicknamePrefix(String nicknamePrefix) {
        this.nicknamePrefix = nicknamePrefix;
    }

    public int getPageSizeDefault() {
        return pageSizeDefault;
    }

    public void setPageSizeDefault(int pageSizeDefault) {
        this.pageSizeDefault = pageSizeDefault;
    }

    public int getPageSizeMax() {
        return pageSizeMax;
    }

    public void setPageSizeMax(int pageSizeMax) {
        this.pageSizeMax = pageSizeMax;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public List<CategoryImage> getImageCategories() {
        return imageCategories;
    }

    public void setImageCategories(List<CategoryImage> imageCategories) {
        this.imageCategories = imageCategories;
    }

    /** 单个「品类 -> 图片文件」映射项。 */
    public static class CategoryImage {

        private String name;
        private String file;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }
    }
}
