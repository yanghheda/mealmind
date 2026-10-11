package com.mealmind.biz.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mealmind.biz.common.BizException;
import com.mealmind.biz.common.ErrorCode;
import com.mealmind.biz.dto.MerchantListResponse;
import com.mealmind.biz.dto.SearchDishView;
import com.mealmind.biz.dto.SearchResponse;
import com.mealmind.biz.entity.Dish;
import com.mealmind.biz.entity.DishTag;
import com.mealmind.biz.entity.Merchant;
import com.mealmind.biz.entity.MerchantTag;
import com.mealmind.biz.image.ImageUrlResolver;
import com.mealmind.biz.mapper.DishMapper;
import com.mealmind.biz.mapper.DishTagMapper;
import com.mealmind.biz.mapper.MerchantMapper;
import com.mealmind.biz.mapper.MerchantTagMapper;

import org.springframework.stereotype.Service;

/**
 * 接口 21：关键字搜索（M4 W4）。
 * 匹配 merchant.name / dish.name / dish_tag.tag / merchant_tag.tag；% 与 _ 按字面量转义；
 * 数据量小、关键词多变，不加 Redis 缓存。
 */
@Service
public class SearchService {

    /** 菜品标签展示优先级（与 h5 菜单页一致），未收录类型排最后。 */
    private static final List<String> TAG_PRIORITY =
            List.of("RANK", "SELLING", "SPICY", "FLAVOR", "INGREDIENT", "HEALTH");

    /** 保留在搜索行中的标签上限（行宽有限，与菜单卡展示一致）。 */
    private static final int TAG_LIMIT = 3;

    private final MerchantService merchantService;
    private final MerchantMapper merchantMapper;
    private final MerchantTagMapper merchantTagMapper;
    private final DishMapper dishMapper;
    private final DishTagMapper dishTagMapper;
    private final ImageUrlResolver imageUrlResolver;

    public SearchService(MerchantService merchantService,
                         MerchantMapper merchantMapper,
                         MerchantTagMapper merchantTagMapper,
                         DishMapper dishMapper,
                         DishTagMapper dishTagMapper,
                         ImageUrlResolver imageUrlResolver) {
        this.merchantService = merchantService;
        this.merchantMapper = merchantMapper;
        this.merchantTagMapper = merchantTagMapper;
        this.dishMapper = dishMapper;
        this.dishTagMapper = dishTagMapper;
        this.imageUrlResolver = imageUrlResolver;
    }

    public SearchResponse search(String keywordParam, Integer pageParam, Integer pageSizeParam) {
        if (keywordParam == null || keywordParam.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM);
        }
        String keyword = keywordParam.trim();
        int page = merchantService.normalizePage(pageParam);
        int pageSize = merchantService.normalizePageSize(pageSizeParam);
        String like = "%" + escapeLike(keyword) + "%";

        // 商家：命中商家名或商家标签（先查标签表拿 id，再拼条件，避免把用户输入拼进 SQL）
        QueryWrapper<Merchant> merchantWrapper = new QueryWrapper<>();
        List<String> merchantIdsByTag = tagMerchantIds(like);
        if (merchantIdsByTag.isEmpty()) {
            merchantWrapper.apply("name LIKE {0}", like);
        } else {
            merchantWrapper.and(w -> w.apply("name LIKE {0}", like).or().in("id", merchantIdsByTag));
        }
        merchantWrapper.last(MerchantService.COMPOSITE_ORDER);
        MerchantListResponse merchantPage = merchantService.pageMerchants(merchantWrapper, page, pageSize);

        // 菜品：命中菜名或菜品标签，按月售降序（同月售按 id 稳定排序）
        QueryWrapper<Dish> dishWrapper = new QueryWrapper<>();
        List<String> dishIdsByTag = tagDishIds(like);
        if (dishIdsByTag.isEmpty()) {
            dishWrapper.apply("name LIKE {0}", like);
        } else {
            dishWrapper.and(w -> w.apply("name LIKE {0}", like).or().in("id", dishIdsByTag));
        }
        dishWrapper.orderByDesc("monthly_sales").orderByAsc("id");
        Page<Dish> dishPage = dishMapper.selectPage(new Page<>(page, pageSize), dishWrapper);

        return new SearchResponse(
                keyword,
                merchantPage.list(),
                buildDishViews(dishPage.getRecords()),
                merchantPage.total(),
                merchantPage.hasMore(),
                dishPage.getTotal());
    }

    private List<String> tagMerchantIds(String like) {
        return merchantTagMapper.selectList(new QueryWrapper<MerchantTag>().apply("tag LIKE {0}", like))
                .stream().map(MerchantTag::getMerchantId).distinct().toList();
    }

    private List<String> tagDishIds(String like) {
        return dishTagMapper.selectList(new QueryWrapper<DishTag>().apply("tag LIKE {0}", like))
                .stream().map(DishTag::getDishId).distinct().toList();
    }

    private List<SearchDishView> buildDishViews(List<Dish> dishes) {
        if (dishes.isEmpty()) {
            return List.of();
        }
        List<String> merchantIds = dishes.stream().map(Dish::getMerchantId).distinct().toList();
        Map<String, Merchant> merchantsById = new LinkedHashMap<>();
        for (Merchant merchant : merchantMapper.selectList(new QueryWrapper<Merchant>().in("id", merchantIds))) {
            merchantsById.put(merchant.getId(), merchant);
        }
        Map<String, List<String>> tagsByDish = loadDishTags(
                dishes.stream().map(Dish::getId).toList());

        List<SearchDishView> views = new ArrayList<>(dishes.size());
        for (Dish dish : dishes) {
            Merchant merchant = merchantsById.get(dish.getMerchantId());
            if (merchant == null) {
                // 理论上不会发生（外键约束），跳过该行而不是让整个搜索失败
                continue;
            }
            views.add(new SearchDishView(
                    dish.getId(),
                    dish.getName(),
                    imageUrlResolver.dishImage(merchant.getCategory()),
                    dish.getPrice(),
                    merchant.getId(),
                    merchant.getName(),
                    merchant.getDeliveryMinutes(),
                    tagsByDish.getOrDefault(dish.getId(), List.of())));
        }
        return views;
    }

    /** 按展示优先级排序、截断的标签文本。 */
    private Map<String, List<String>> loadDishTags(List<String> dishIds) {
        List<DishTag> tags = dishTagMapper.selectList(new QueryWrapper<DishTag>().in("dish_id", dishIds));
        Map<String, List<DishTag>> byDish = new LinkedHashMap<>();
        for (DishTag tag : tags) {
            byDish.computeIfAbsent(tag.getDishId(), k -> new ArrayList<>()).add(tag);
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<DishTag>> entry : byDish.entrySet()) {
            List<String> ordered = entry.getValue().stream()
                    .sorted((a, b) -> Integer.compare(tagRank(a.getTagType()), tagRank(b.getTagType())))
                    .map(DishTag::getTag)
                    .distinct()
                    .limit(TAG_LIMIT)
                    .toList();
            result.put(entry.getKey(), ordered);
        }
        return result;
    }

    private static int tagRank(String tagType) {
        int index = TAG_PRIORITY.indexOf(tagType);
        return index == -1 ? TAG_PRIORITY.size() : index;
    }

    /** LIKE 字面量转义：\ 先转义，再转义 % 与 _（MySQL LIKE 默认转义符为 \）。 */
    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}