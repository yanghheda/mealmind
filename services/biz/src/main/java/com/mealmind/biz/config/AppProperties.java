package com.mealmind.biz.config;

import java.util.ArrayList;
import java.util.List;

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
