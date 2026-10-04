package com.mealmind.biz.image;

import java.util.HashMap;
import java.util.Map;

import com.mealmind.biz.config.AppProperties;
import com.mealmind.biz.config.AppProperties.CategoryImage;

import org.springframework.stereotype.Component;

/**
 * 图片 URL 解析：商家 logo 与菜品图都按「品类 -> 图片文件」组合，
 * 与 apps/h5/public/images/manifest.json 保持一致；DB 中的 logo_url / image_url 恒为空、不读取。
 */
@Component
public class ImageUrlResolver {

    private static final String DEFAULT_KEY = "default";
    private static final String DEFAULT_FILE = "cat-default.svg";

    private final AppProperties properties;
    private final Map<String, String> categoryFiles = new HashMap<>();

    public ImageUrlResolver(AppProperties properties) {
        this.properties = properties;
        for (CategoryImage item : properties.getImageCategories()) {
            if (item.getName() != null && item.getFile() != null) {
                categoryFiles.put(item.getName(), item.getFile());
            }
        }
    }

    public String merchantLogo(String category) {
        return resolve(category);
    }

    public String dishImage(String category) {
        return resolve(category);
    }

    private String resolve(String category) {
        String file = DEFAULT_FILE;
        if (category != null) {
            String mapped = categoryFiles.get(category);
            if (mapped == null) {
                mapped = categoryFiles.get(DEFAULT_KEY);
            }
            if (mapped != null && !mapped.isBlank()) {
                file = mapped;
            }
        }
        String base = properties.getImageBaseUrl();
        if (base == null || base.isBlank()) {
            return "/images/" + file;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/images/" + file;
    }
}
