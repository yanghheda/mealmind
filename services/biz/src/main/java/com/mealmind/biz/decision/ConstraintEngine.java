package com.mealmind.biz.decision;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mealmind.biz.config.AppProperties;
import com.mealmind.biz.dto.DishTagView;
import com.mealmind.biz.dto.SpecGroupView;
import com.mealmind.biz.dto.SpecOptionView;
import com.mealmind.biz.entity.Dish;
import com.mealmind.biz.entity.DishCategory;
import com.mealmind.biz.entity.DishSpecGroup;
import com.mealmind.biz.entity.DishSpecOption;
import com.mealmind.biz.entity.DishTag;
import com.mealmind.biz.entity.Merchant;
import com.mealmind.biz.image.ImageUrlResolver;
import com.mealmind.biz.mapper.DishCategoryMapper;
import com.mealmind.biz.mapper.DishMapper;
import com.mealmind.biz.mapper.DishSpecGroupMapper;
import com.mealmind.biz.mapper.DishSpecOptionMapper;
import com.mealmind.biz.mapper.DishTagMapper;
import com.mealmind.biz.mapper.MerchantMapper;

import org.springframework.stereotype.Component;

/**
 * 约束引擎（PRD 5.3 六条硬约束，由代码执行）。
 * 评估顺序：营业状态 → 售罄/下架 → 预算 → 忌口 → 时效 → 候选上限；
 * 预算全被排除时允许浮动 10% 内的候选作为次选并标记「略超预算」。
 * 产出「已过滤 N 个，原因分布」明细，供前端展示与评测统计复用。
 */
@Component
public class ConstraintEngine {

    private static final String MODE_MERCHANT = "MERCHANT";
    private static final BigDecimal BUDGET_FLOAT = new BigDecimal("1.10");
    /** 辣度档位（与 Agent 侧 RuleScorer 同一口径）。 */
    private static final Map<String, Integer> SPICY_LEVELS =
            Map.of("不辣", 0, "微辣", 1, "中辣", 2, "特辣", 3);

    private final MerchantMapper merchantMapper;
    private final DishMapper dishMapper;
    private final DishCategoryMapper dishCategoryMapper;
    private final DishTagMapper dishTagMapper;
    private final DishSpecGroupMapper dishSpecGroupMapper;
    private final DishSpecOptionMapper dishSpecOptionMapper;
    private final ImageUrlResolver imageUrlResolver;
    private final AppProperties properties;

    public ConstraintEngine(MerchantMapper merchantMapper,
                            DishMapper dishMapper,
                            DishCategoryMapper dishCategoryMapper,
                            DishTagMapper dishTagMapper,
                            DishSpecGroupMapper dishSpecGroupMapper,
                            DishSpecOptionMapper dishSpecOptionMapper,
                            ImageUrlResolver imageUrlResolver,
                            AppProperties properties) {
        this.merchantMapper = merchantMapper;
        this.dishMapper = dishMapper;
        this.dishCategoryMapper = dishCategoryMapper;
        this.dishTagMapper = dishTagMapper;
        this.dishSpecGroupMapper = dishSpecGroupMapper;
        this.dishSpecOptionMapper = dishSpecOptionMapper;
        this.imageUrlResolver = imageUrlResolver;
        this.properties = properties;
    }

    public FilterResult filter(DecisionProfile profile, String mode, String merchantId) {
        List<Merchant> merchants = MODE_MERCHANT.equals(mode)
                ? merchantScope(merchantId)
                : merchantMapper.selectList(null);
        Map<String, Merchant> merchantById = merchants.stream()
                .collect(Collectors.toMap(Merchant::getId, m -> m, (a, b) -> a, LinkedHashMap::new));

        List<Dish> dishes = merchantById.isEmpty() ? List.of() : dishMapper.selectList(
                new QueryWrapper<Dish>().in("merchant_id", merchantById.keySet()));
        List<String> dishIds = dishes.stream().map(Dish::getId).toList();
        Map<String, List<DishTagView>> tagsByDish = loadTags(dishIds);
        Map<String, List<SpecGroupView>> specsByDish = loadSpecGroups(dishIds);
        Map<String, String> categoryNames = dishCategoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(DishCategory::getId, DishCategory::getName, (a, b) -> a));

        int closed = 0;
        int unavailable = 0;
        int budget = 0;
        int avoid = 0;
        int timeliness = 0;
        Set<String> avoidTags = Set.copyOf(profile.avoidOrEmpty());
        List<CandidateFact> kept = new ArrayList<>();
        List<CandidateFact> budgetExcluded = new ArrayList<>();

        for (Dish dish : dishes) {
            Merchant merchant = merchantById.get(dish.getMerchantId());
            if (merchant.getIsOpen() != null && merchant.getIsOpen() == 0) {
                closed++;
                continue;
            }
            if (dish.getIsAvailable() != null && dish.getIsAvailable() == 0) {
                unavailable++;
                continue;
            }
            CandidateFact fact = toFact(dish, merchant, tagsByDish, specsByDish, categoryNames);
            if (!java.util.Collections.disjoint(avoidTags, tagNames(fact))) {
                avoid++;
                continue;
            }
            if (profile.maxDeliveryMin() != null && merchant.getDeliveryMinutes() > profile.maxDeliveryMin()) {
                timeliness++;
                continue;
            }
            if (profile.budgetMax() != null
                    && fact.payload().price().compareTo(profile.budgetMax()) > 0) {
                budget++;
                budgetExcluded.add(fact);
                continue;
            }
            kept.add(fact);
        }

        int overBudget = 0;
        if (kept.isEmpty() && profile.budgetMax() != null && !budgetExcluded.isEmpty()) {
            // 预算内全部被排除：放行浮动 10% 内的候选作为次选（其余约束已在上方通过）
            BigDecimal ceiling = profile.budgetMax().multiply(BUDGET_FLOAT);
            List<CandidateFact> relaxed = budgetExcluded.stream()
                    .filter(f -> f.payload().price().compareTo(ceiling) <= 0)
                    .map(f -> new CandidateFact(f.payload(), f.specGroups(), true))
                    .toList();
            if (!relaxed.isEmpty()) {
                kept = new ArrayList<>(relaxed);
                overBudget = relaxed.size();
            }
        }

        int capped = 0;
        int maxCandidates = properties.getDecision().getMaxCandidates();
        if (kept.size() > maxCandidates) {
            // 候选超限的粗排：先保证与需求相关的菜品不被高分但无关的菜挤掉，再按评分与月售
            kept.sort(Comparator
                    .comparingInt((CandidateFact f) -> relevance(profile, f)).reversed()
                    .thenComparing(f -> f.payload().merchantRating(), Comparator.reverseOrder())
                    .thenComparing(f -> f.payload().dishMonthlySales(), Comparator.reverseOrder())
                    .thenComparing(f -> f.payload().dishId()));
            capped = kept.size() - maxCandidates;
            kept = new ArrayList<>(kept.subList(0, maxCandidates));
        }

        Map<String, Integer> detail = new LinkedHashMap<>();
        detail.put("closed", closed);
        detail.put("unavailable", unavailable);
        detail.put("budget", budget);
        detail.put("avoid", avoid);
        detail.put("timeliness", timeliness);
        detail.put("capped", capped);
        detail.put("overBudget", overBudget);
        return new FilterResult(kept, dishes.size(), detail);
    }

    private List<Merchant> merchantScope(String merchantId) {
        Merchant merchant = merchantMapper.selectById(merchantId);
        return merchant == null ? List.of() : List.of(merchant);
    }

    /** 粗排相关性：口味标签命中计分（辣度按「同档 +2 / 临近 +1 / 泛辣命中 +2」）。 */
    private int relevance(DecisionProfile profile, CandidateFact fact) {
        Set<String> dishTags = tagNames(fact);
        int score = 0;
        for (String taste : profile.tasteTagsOrEmpty()) {
            if (dishTags.contains(taste)) {
                score += 2;
                continue;
            }
            if ("辣".equals(taste)) {
                if (dishSpicyLevel(dishTags) > 0) {
                    score += 2;
                }
            } else if (SPICY_LEVELS.containsKey(taste) && dishTags.contains("辣")) {
                score += 1;
            }
        }
        for (String taste : profile.tasteTagsOrEmpty()) {
            if (!SPICY_LEVELS.containsKey(taste) && !"辣".equals(taste) && dishTags.contains(taste)) {
                score += 2;
            }
        }
        return score;
    }

    private int dishSpicyLevel(Set<String> dishTags) {
        int level = -1;
        for (String tag : dishTags) {
            Integer value = SPICY_LEVELS.get(tag);
            if (value != null) {
                level = Math.max(level, value);
            }
        }
        return level;
    }

    private CandidateFact toFact(Dish dish, Merchant merchant,
                                 Map<String, List<DishTagView>> tagsByDish,
                                 Map<String, List<SpecGroupView>> specsByDish,
                                 Map<String, String> categoryNames) {
        List<SpecGroupView> specGroups = specsByDish.getOrDefault(dish.getId(), List.of());
        AgentCandidate payload = new AgentCandidate(
                dish.getId(),
                dish.getName(),
                merchant.getId(),
                merchant.getName(),
                displayPrice(dish, specGroups),
                imageUrlResolver.dishImage(merchant.getCategory()),
                merchant.getDeliveryMinutes() == null ? 0 : merchant.getDeliveryMinutes(),
                merchant.getRating(),
                dish.getMonthlySales() == null ? 0 : dish.getMonthlySales(),
                categoryNames.get(dish.getCategoryId()),
                tagsByDish.getOrDefault(dish.getId(), List.of()).stream()
                        .map(t -> new AgentCandidate.AgentTag(t.tag(), t.tagType()))
                        .toList());
        return new CandidateFact(payload, specGroups, false);
    }

    /** 带规格菜品与菜单口径一致：展示最低规格价 = 基础价 + 各规格组最小加价。 */
    private BigDecimal displayPrice(Dish dish, List<SpecGroupView> specGroups) {
        BigDecimal price = dish.getPrice();
        for (SpecGroupView group : specGroups) {
            BigDecimal groupMin = group.options().stream()
                    .map(SpecOptionView::extraPrice)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            price = price.add(groupMin);
        }
        return price;
    }

    private Set<String> tagNames(CandidateFact fact) {
        return fact.payload().tags().stream().map(AgentCandidate.AgentTag::tag).collect(Collectors.toSet());
    }

    private Map<String, List<DishTagView>> loadTags(List<String> dishIds) {
        if (dishIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<DishTagView>> result = new LinkedHashMap<>();
        for (DishTag tag : dishTagMapper.selectList(new QueryWrapper<DishTag>().in("dish_id", dishIds))) {
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
}