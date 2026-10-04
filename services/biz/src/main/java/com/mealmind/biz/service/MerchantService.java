package com.mealmind.biz.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mealmind.biz.common.BizException;
import com.mealmind.biz.common.ErrorCode;
import com.mealmind.biz.config.AppProperties;
import com.mealmind.biz.dto.DishTagView;
import com.mealmind.biz.dto.DishView;
import com.mealmind.biz.dto.MenuCategoryView;
import com.mealmind.biz.dto.MenuResponse;
import com.mealmind.biz.dto.MerchantListResponse;
import com.mealmind.biz.dto.MerchantView;
import com.mealmind.biz.dto.PreviewDish;
import com.mealmind.biz.dto.SpecGroupView;
import com.mealmind.biz.dto.SpecOptionView;
import com.mealmind.biz.entity.Dish;
import com.mealmind.biz.entity.DishCategory;
import com.mealmind.biz.entity.DishSpecGroup;
import com.mealmind.biz.entity.DishSpecOption;
import com.mealmind.biz.entity.DishTag;
import com.mealmind.biz.entity.Merchant;
import com.mealmind.biz.entity.MerchantTag;
import com.mealmind.biz.image.ImageUrlResolver;
import com.mealmind.biz.mapper.DishCategoryMapper;
import com.mealmind.biz.mapper.DishMapper;
import com.mealmind.biz.mapper.DishSpecGroupMapper;
import com.mealmind.biz.mapper.DishSpecOptionMapper;
import com.mealmind.biz.mapper.DishTagMapper;
import com.mealmind.biz.mapper.MerchantMapper;
import com.mealmind.biz.mapper.MerchantTagMapper;

import org.springframework.stereotype.Service;

/**
 * 商家与菜单查询（接口 4/5/6/7）。
 */
@Service
public class MerchantService {

    /**
     * 综合排序公式：评分权重 20（rating 0–5 -> 0–100），月售权重每 1000 单加 1 分、封顶 10000 单。
     * 即 rating * 20 + LEAST(monthly_sales, 10000) / 1000.0，降序；同分按 id 稳定排序。
     * 打烊商家（is_open = 0）仍保留在列表中，由前端标注「休息中」。
     */
    private static final String COMPOSITE_ORDER =
            "ORDER BY rating * 20 + LEAST(monthly_sales, 10000) / 1000.0 DESC, id ASC";

    private static final String CATEGORY_ALL = "ALL";

    private final MerchantMapper merchantMapper;
    private final MerchantTagMapper merchantTagMapper;
    private final DishMapper dishMapper;
    private final DishCategoryMapper dishCategoryMapper;
    private final DishTagMapper dishTagMapper;
    private final DishSpecGroupMapper dishSpecGroupMapper;
    private final DishSpecOptionMapper dishSpecOptionMapper;
    private final ImageUrlResolver imageUrlResolver;
    private final JsonCacheService cacheService;
    private final AppProperties properties;

    public MerchantService(MerchantMapper merchantMapper,
                           MerchantTagMapper merchantTagMapper,
                           DishMapper dishMapper,
                           DishCategoryMapper dishCategoryMapper,
                           DishTagMapper dishTagMapper,
                           DishSpecGroupMapper dishSpecGroupMapper,
                           DishSpecOptionMapper dishSpecOptionMapper,
                           ImageUrlResolver imageUrlResolver,
                           JsonCacheService cacheService,
                           AppProperties properties) {
        this.merchantMapper = merchantMapper;
        this.merchantTagMapper = merchantTagMapper;
        this.dishMapper = dishMapper;
        this.dishCategoryMapper = dishCategoryMapper;
        this.dishTagMapper = dishTagMapper;
        this.dishSpecGroupMapper = dishSpecGroupMapper;
        this.dishSpecOptionMapper = dishSpecOptionMapper;
        this.imageUrlResolver = imageUrlResolver;
        this.cacheService = cacheService;
        this.properties = properties;
    }

    /** 接口 4：首页推荐商家流（综合排序，含打烊商家）。 */
    public MerchantListResponse homeMerchants(Integer pageParam, Integer pageSizeParam) {
        int page = normalizePage(pageParam);
        int pageSize = normalizePageSize(pageSizeParam);
        String cacheKey = cacheService.key("cache:merchants:HOME:ALL:" + page + ":" + pageSize);
        MerchantListResponse cached = cacheService.get(cacheKey, MerchantListResponse.class);
        if (cached != null) {
            return cached;
        }
        QueryWrapper<Merchant> wrapper = new QueryWrapper<>();
        wrapper.last(COMPOSITE_ORDER);
        MerchantListResponse response = pageMerchants(wrapper, page, pageSize);
        cacheService.put(cacheKey, response);
        return response;
    }

    /** 接口 5：美食外卖页商家列表（品类筛选 + 排序）。 */
    public MerchantListResponse foodMerchants(String categoryParam, String sortParam, Integer pageParam, Integer pageSizeParam) {
        String category = (categoryParam == null || categoryParam.isBlank()) ? CATEGORY_ALL : categoryParam;
        validateCategory(category);
        String sort = normalizeSort(sortParam);
        int page = normalizePage(pageParam);
        int pageSize = normalizePageSize(pageSizeParam);
        String cacheKey = cacheService.key("cache:merchants:" + sort + ":" + category + ":" + page + ":" + pageSize);
        MerchantListResponse cached = cacheService.get(cacheKey, MerchantListResponse.class);
        if (cached != null) {
            return cached;
        }
        QueryWrapper<Merchant> wrapper = new QueryWrapper<>();
        if (!CATEGORY_ALL.equals(category)) {
            wrapper.eq("category", category);
        }
        applySort(wrapper, sort);
        MerchantListResponse response = pageMerchants(wrapper, page, pageSize);
        cacheService.put(cacheKey, response);
        return response;
    }

    /** 接口 6：商家详情。 */
    public MerchantView getMerchant(String merchantId) {
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BizException(ErrorCode.MERCHANT_NOT_FOUND);
        }
        return buildMerchantViews(List.of(merchant)).get(0);
    }

    /** 接口 7：商家菜单（分类 + 菜品 + 规格 + 标签）。 */
    public MenuResponse getMenu(String merchantId) {
        if (merchantMapper.selectById(merchantId) == null) {
            throw new BizException(ErrorCode.MERCHANT_NOT_FOUND);
        }
        String cacheKey = cacheService.key("cache:menu:" + merchantId);
        MenuResponse cached = cacheService.get(cacheKey, MenuResponse.class);
        if (cached != null) {
            return cached;
        }
        Merchant merchant = merchantMapper.selectById(merchantId);
        List<DishCategory> categories = dishCategoryMapper.selectList(
                new QueryWrapper<DishCategory>().eq("merchant_id", merchantId).orderByAsc("sort_no"));
        List<Dish> dishes = dishMapper.selectList(
                new QueryWrapper<Dish>().eq("merchant_id", merchantId).orderByAsc("sort_no"));

        List<String> dishIds = dishes.stream().map(Dish::getId).toList();
        Map<String, List<DishTagView>> tagsByDish = loadDishTags(dishIds);
        Map<String, List<SpecGroupView>> specsByDish = loadSpecGroups(dishIds);

        List<MenuCategoryView> categoryViews = new ArrayList<>();
        for (DishCategory category : categories) {
            List<DishView> dishViews = dishes.stream()
                    .filter(d -> category.getId().equals(d.getCategoryId()))
                    .map(d -> toDishView(d, merchant, tagsByDish, specsByDish))
                    .toList();
            categoryViews.add(new MenuCategoryView(category.getId(), category.getName(), dishViews));
        }
        MenuResponse response = new MenuResponse(merchantId, categoryViews);
        cacheService.put(cacheKey, response);
        return response;
    }

    private MerchantListResponse pageMerchants(QueryWrapper<Merchant> wrapper, int page, int pageSize) {
        Page<Merchant> result = merchantMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<MerchantView> views = buildMerchantViews(result.getRecords());
        boolean hasMore = result.getTotal() > (long) page * pageSize;
        return new MerchantListResponse(views, result.getTotal(), hasMore);
    }

    private List<MerchantView> buildMerchantViews(List<Merchant> merchants) {
        if (merchants == null || merchants.isEmpty()) {
            return List.of();
        }
        List<String> merchantIds = merchants.stream().map(Merchant::getId).toList();

        Map<String, List<MerchantTag>> tagsByMerchant = merchantTagMapper.selectList(
                        new QueryWrapper<MerchantTag>().in("merchant_id", merchantIds).orderByAsc("sort_no"))
                .stream().collect(Collectors.groupingBy(MerchantTag::getMerchantId, LinkedHashMap::new, Collectors.toList()));

        // 预览菜品：可售优先、月售降序，每商家取前 3（一次 IN 查询避免 N+1）
        Map<String, List<Dish>> dishesByMerchant = dishMapper.selectList(
                        new QueryWrapper<Dish>().in("merchant_id", merchantIds)
                                .orderByDesc("is_available").orderByDesc("monthly_sales"))
                .stream().collect(Collectors.groupingBy(Dish::getMerchantId, LinkedHashMap::new, Collectors.toList()));

        List<MerchantView> views = new ArrayList<>(merchants.size());
        for (Merchant merchant : merchants) {
            List<MerchantTag> tags = tagsByMerchant.getOrDefault(merchant.getId(), List.of());
            List<String> promoTags = tags.stream()
                    .filter(t -> "PROMO".equals(t.getTagType()))
                    .map(MerchantTag::getTag).toList();
            List<String> featureTags = tags.stream()
                    .filter(t -> "FEATURE".equals(t.getTagType()))
                    .map(MerchantTag::getTag).toList();
            String logo = imageUrlResolver.merchantLogo(merchant.getCategory());
            List<PreviewDish> previews = dishesByMerchant.getOrDefault(merchant.getId(), List.of()).stream()
                    .limit(3)
                    .map(d -> new PreviewDish(d.getId(), d.getName(), logo, d.getPrice()))
                    .toList();
            views.add(new MerchantView(
                    merchant.getId(),
                    merchant.getName(),
                    logo,
                    merchant.getRating(),
                    merchant.getMonthlySales(),
                    merchant.getDeliveryMinutes(),
                    merchant.getDistanceMeters(),
                    merchant.getMinOrderAmount(),
                    merchant.getDeliveryFee(),
                    merchant.getIsOpen() == null || merchant.getIsOpen() != 0,
                    merchant.getRankLabel(),
                    promoTags,
                    featureTags,
                    previews));
        }
        return views;
    }

    private Map<String, List<DishTagView>> loadDishTags(List<String> dishIds) {
        if (dishIds.isEmpty()) {
            return Map.of();
        }
        List<DishTag> tags = dishTagMapper.selectList(
                new QueryWrapper<DishTag>().in("dish_id", dishIds).orderByAsc("tag_type"));
        Map<String, List<DishTagView>> result = new LinkedHashMap<>();
        for (DishTag tag : tags) {
            result.computeIfAbsent(tag.getDishId(), k -> new ArrayList<>())
                    .add(new DishTagView(tag.getTag(), tag.getTagType()));
        }
        return result;
    }

    private Map<String, List<SpecGroupView>> loadSpecGroups(List<String> dishIds) {
        if (dishIds.isEmpty()) {
            return Map.of();
        }
        List<DishSpecGroup> groups = dishSpecGroupMapper.selectList(
                new QueryWrapper<DishSpecGroup>().in("dish_id", dishIds).orderByAsc("sort_no"));
        if (groups.isEmpty()) {
            return Map.of();
        }
        List<String> groupIds = groups.stream().map(DishSpecGroup::getId).toList();
        Map<String, List<DishSpecOption>> optionsByGroup = dishSpecOptionMapper.selectList(
                        new QueryWrapper<DishSpecOption>().in("group_id", groupIds).orderByAsc("sort_no"))
                .stream().collect(Collectors.groupingBy(DishSpecOption::getGroupId, LinkedHashMap::new, Collectors.toList()));

        Map<String, List<SpecGroupView>> result = new LinkedHashMap<>();
        for (DishSpecGroup group : groups) {
            List<SpecOptionView> options = optionsByGroup.getOrDefault(group.getId(), List.of()).stream()
                    .map(o -> new SpecOptionView(o.getId(), o.getOptionName(), o.getExtraPrice(),
                            o.getIsDefault() != null && o.getIsDefault() != 0))
                    .toList();
            result.computeIfAbsent(group.getDishId(), k -> new ArrayList<>())
                    .add(new SpecGroupView(group.getId(), group.getGroupName(),
                            group.getIsRequired() != null && group.getIsRequired() != 0, options));
        }
        return result;
    }

    private DishView toDishView(Dish dish, Merchant merchant,
                              Map<String, List<DishTagView>> tagsByDish,
                              Map<String, List<SpecGroupView>> specsByDish) {
        List<SpecGroupView> specGroups = specsByDish.getOrDefault(dish.getId(), List.of());
        boolean priceFrom = !specGroups.isEmpty();
        BigDecimal price = dish.getPrice();
        if (priceFrom) {
            // 带规格菜品展示最低规格价：基础价 + 每个规格组的最小加价
            BigDecimal minExtra = BigDecimal.ZERO;
            for (SpecGroupView group : specGroups) {
                BigDecimal groupMin = group.options().stream()
                        .map(SpecOptionView::extraPrice)
                        .min(BigDecimal::compareTo)
                        .orElse(BigDecimal.ZERO);
                minExtra = minExtra.add(groupMin);
            }
            price = dish.getPrice().add(minExtra);
        }
        return new DishView(
                dish.getId(),
                dish.getName(),
                dish.getDescription(),
                imageUrlResolver.dishImage(merchant.getCategory()),
                price,
                dish.getOriginPrice(),
                dish.getMonthlySales(),
                dish.getIsAvailable() != null && dish.getIsAvailable() != 0,
                dish.getIsCombo() != null && dish.getIsCombo() != 0,
                priceFrom,
                tagsByDish.getOrDefault(dish.getId(), List.of()),
                specGroups);
    }

    private void validateCategory(String category) {
        if (CATEGORY_ALL.equals(category)) {
            return;
        }
        Set<String> validCategories = new HashSet<>(merchantMapper.selectDistinctCategories());
        if (!validCategories.contains(category)) {
            throw new BizException(ErrorCode.INVALID_CATEGORY);
        }
    }

    private void applySort(QueryWrapper<Merchant> wrapper, String sort) {
        switch (sort) {
            case "DISTANCE" -> wrapper.orderByAsc("distance_meters").orderByAsc("id");
            case "RATING" -> wrapper.orderByDesc("rating").orderByDesc("monthly_sales").orderByAsc("id");
            // 综合排序：与首页同一加权公式
            default -> wrapper.last(COMPOSITE_ORDER);
        }
    }

    private String normalizeSort(String sortParam) {
        if (sortParam == null || sortParam.isBlank()) {
            return "DEFAULT";
        }
        String sort = sortParam.trim().toUpperCase();
        return switch (sort) {
            case "DISTANCE", "RATING" -> sort;
            default -> "DEFAULT";
        };
    }

    private int normalizePage(Integer page) {
        return (page == null || page < 1) ? 1 : page;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return properties.getPageSizeDefault();
        }
        return Math.min(pageSize, properties.getPageSizeMax());
    }
}
