package com.mealmind.biz.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mealmind.biz.common.BizException;
import com.mealmind.biz.common.ErrorCode;
import com.mealmind.biz.dto.AddCartItemRequest;
import com.mealmind.biz.dto.AddCartItemResponse;
import com.mealmind.biz.dto.CartGroupView;
import com.mealmind.biz.dto.CartItemView;
import com.mealmind.biz.dto.CartResponse;
import com.mealmind.biz.dto.DeleteCartItemResponse;
import com.mealmind.biz.dto.UpdateCartItemRequest;
import com.mealmind.biz.dto.UpdateCartItemResponse;
import com.mealmind.biz.entity.CartItem;
import com.mealmind.biz.entity.Dish;
import com.mealmind.biz.entity.DishSpecGroup;
import com.mealmind.biz.entity.DishSpecOption;
import com.mealmind.biz.entity.Merchant;
import com.mealmind.biz.image.ImageUrlResolver;
import com.mealmind.biz.mapper.CartItemMapper;
import com.mealmind.biz.mapper.DishMapper;
import com.mealmind.biz.mapper.DishSpecGroupMapper;
import com.mealmind.biz.mapper.DishSpecOptionMapper;
import com.mealmind.biz.mapper.MerchantMapper;
import com.mealmind.biz.session.SessionContext;

import org.springframework.stereotype.Service;

/**
 * 购物车（接口 8/9/10/11）。条目唯一性由「菜品 + 规格哈希」决定。
 */
@Service
public class CartService {

    private static final int MAX_QUANTITY = 99;

    private final CartItemMapper cartItemMapper;
    private final DishMapper dishMapper;
    private final DishSpecGroupMapper dishSpecGroupMapper;
    private final DishSpecOptionMapper dishSpecOptionMapper;
    private final MerchantMapper merchantMapper;
    private final ImageUrlResolver imageUrlResolver;

    public CartService(CartItemMapper cartItemMapper,
                       DishMapper dishMapper,
                       DishSpecGroupMapper dishSpecGroupMapper,
                       DishSpecOptionMapper dishSpecOptionMapper,
                       MerchantMapper merchantMapper,
                       ImageUrlResolver imageUrlResolver) {
        this.cartItemMapper = cartItemMapper;
        this.dishMapper = dishMapper;
        this.dishSpecGroupMapper = dishSpecGroupMapper;
        this.dishSpecOptionMapper = dishSpecOptionMapper;
        this.merchantMapper = merchantMapper;
        this.imageUrlResolver = imageUrlResolver;
    }

    /** 接口 8：查询购物车，按商家分组。 */
    public CartResponse getCart() {
        String sessionId = SessionContext.sessionId();
        List<CartItem> items = cartItemMapper.selectList(
                new QueryWrapper<CartItem>().eq("session_id", sessionId).orderByAsc("created_at"));
        if (items.isEmpty()) {
            return new CartResponse(List.of(), 0, BigDecimal.ZERO);
        }
        Set<String> dishIds = new HashSet<>();
        for (CartItem item : items) {
            dishIds.add(item.getDishId());
        }
        Map<String, Dish> dishes = new LinkedHashMap<>();
        for (Dish dish : dishMapper.selectBatchIds(dishIds)) {
            dishes.put(dish.getId(), dish);
        }
        Set<String> merchantIds = new HashSet<>();
        for (Dish dish : dishes.values()) {
            merchantIds.add(dish.getMerchantId());
        }
        Map<String, Merchant> merchants = new LinkedHashMap<>();
        if (!merchantIds.isEmpty()) {
            for (Merchant merchant : merchantMapper.selectBatchIds(merchantIds)) {
                merchants.put(merchant.getId(), merchant);
            }
        }

        Map<String, List<CartItemView>> grouped = new LinkedHashMap<>();
        Map<String, BigDecimal> subtotals = new LinkedHashMap<>();
        int totalQuantity = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CartItem item : items) {
            Dish dish = dishes.get(item.getDishId());
            if (dish == null) {
                continue;
            }
            Merchant merchant = merchants.get(dish.getMerchantId());
            String merchantId = dish.getMerchantId();
            String imageUrl = imageUrlResolver.dishImage(merchant == null ? null : merchant.getCategory());
            CartItemView view = new CartItemView(
                    item.getId(),
                    item.getDishId(),
                    dish.getName(),
                    imageUrl,
                    item.getSpecText(),
                    item.getUnitPrice(),
                    item.getQuantity(),
                    dish.getIsAvailable() != null && dish.getIsAvailable() != 0,
                    dish.getIsCombo() != null && dish.getIsCombo() != 0);
            grouped.computeIfAbsent(merchantId, k -> new ArrayList<>()).add(view);
            BigDecimal lineAmount = item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            subtotals.merge(merchantId, lineAmount, BigDecimal::add);
            totalQuantity += item.getQuantity();
            totalAmount = totalAmount.add(lineAmount);
        }

        List<CartGroupView> groups = new ArrayList<>();
        for (Map.Entry<String, List<CartItemView>> entry : grouped.entrySet()) {
            String merchantId = entry.getKey();
            Merchant merchant = merchants.get(merchantId);
            groups.add(new CartGroupView(
                    merchantId,
                    merchant == null ? null : merchant.getName(),
                    merchant == null ? null : imageUrlResolver.merchantLogo(merchant.getCategory()),
                    entry.getValue(),
                    subtotals.getOrDefault(merchantId, BigDecimal.ZERO)));
        }
        return new CartResponse(groups, totalQuantity, totalAmount);
    }

    /** 接口 9：加入购物车（同菜品 + 同规格合并数量）。 */
    public AddCartItemResponse addItem(AddCartItemRequest request) {
        if (request == null || request.dishId() == null || request.dishId().isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM);
        }
        int quantity = validateQuantity(request.quantity());
        Dish dish = dishMapper.selectById(request.dishId());
        if (dish == null) {
            throw new BizException(ErrorCode.DISH_NOT_FOUND);
        }
        if (dish.getIsAvailable() == null || dish.getIsAvailable() == 0) {
            throw new BizException(ErrorCode.DISH_UNAVAILABLE);
        }
        SpecResolution spec = resolveSpec(dish, request.specOptionIds());
        String sessionId = SessionContext.sessionId();

        CartItem existing = cartItemMapper.selectOne(new QueryWrapper<CartItem>()
                .eq("session_id", sessionId)
                .eq("dish_id", dish.getId())
                .eq("spec_hash", spec.specHash()));
        if (existing != null) {
            int merged = existing.getQuantity() + quantity;
            if (merged > MAX_QUANTITY) {
                throw new BizException(ErrorCode.QUANTITY_LIMIT);
            }
            existing.setQuantity(merged);
            existing.setUnitPrice(spec.unitPrice());
            cartItemMapper.updateById(existing);
            return new AddCartItemResponse(existing.getId(), merged);
        }

        CartItem item = new CartItem();
        item.setId(UUID.randomUUID().toString().replace("-", ""));
        item.setSessionId(sessionId);
        item.setDishId(dish.getId());
        item.setSpecText(spec.specText());
        item.setSpecHash(spec.specHash());
        item.setQuantity(quantity);
        item.setUnitPrice(spec.unitPrice());
        cartItemMapper.insert(item);
        return new AddCartItemResponse(item.getId(), quantity);
    }

    /** 接口 10：修改数量（0 删除）。 */
    public UpdateCartItemResponse updateItem(UpdateCartItemRequest request) {
        if (request == null || request.itemId() == null || request.itemId().isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM);
        }
        if (request.quantity() == null) {
            throw new BizException(ErrorCode.INVALID_PARAM);
        }
        int quantity = request.quantity();
        if (quantity < 0 || quantity > MAX_QUANTITY) {
            throw new BizException(ErrorCode.QUANTITY_LIMIT);
        }
        String sessionId = SessionContext.sessionId();
        CartItem item = cartItemMapper.selectOne(new QueryWrapper<CartItem>()
                .eq("id", request.itemId()).eq("session_id", sessionId));
        if (item == null) {
            throw new BizException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
        if (quantity == 0) {
            cartItemMapper.deleteById(item.getId());
            return new UpdateCartItemResponse(item.getId(), 0, true);
        }
        item.setQuantity(quantity);
        cartItemMapper.updateById(item);
        return new UpdateCartItemResponse(item.getId(), quantity, false);
    }

    /** 接口 11：删除单条（itemId）或清空一组（merchantId）。 */
    public DeleteCartItemResponse deleteItem(String itemId, String merchantId) {
        boolean hasItem = itemId != null && !itemId.isBlank();
        boolean hasMerchant = merchantId != null && !merchantId.isBlank();
        if (hasItem == hasMerchant) {
            throw new BizException(ErrorCode.INVALID_PARAM);
        }
        String sessionId = SessionContext.sessionId();
        if (hasItem) {
            int deleted = cartItemMapper.delete(new QueryWrapper<CartItem>()
                    .eq("id", itemId).eq("session_id", sessionId));
            return new DeleteCartItemResponse(deleted);
        }
        int deleted = cartItemMapper.deleteByMerchant(sessionId, merchantId);
        return new DeleteCartItemResponse(deleted);
    }

    private int validateQuantity(Integer quantity) {
        if (quantity == null || quantity < 1 || quantity > MAX_QUANTITY) {
            throw new BizException(ErrorCode.QUANTITY_LIMIT);
        }
        return quantity;
    }

    /**
     * 解析规格选择：校验归属与必选，产出 specText / specHash / unitPrice。
     */
    private SpecResolution resolveSpec(Dish dish, List<String> specOptionIds) {
        List<DishSpecGroup> groups = dishSpecGroupMapper.selectList(
                new QueryWrapper<DishSpecGroup>().eq("dish_id", dish.getId()).orderByAsc("sort_no"));
        List<String> selectedIds = specOptionIds == null ? List.of() : specOptionIds.stream()
                .filter(id -> id != null && !id.isBlank()).toList();

        if (groups.isEmpty()) {
            if (!selectedIds.isEmpty()) {
                throw new BizException(ErrorCode.INVALID_SPEC);
            }
            return new SpecResolution(null, md5Hex(dish.getId() + "|"), dish.getPrice());
        }

        List<String> groupIds = groups.stream().map(DishSpecGroup::getId).toList();
        List<DishSpecOption> options = dishSpecOptionMapper.selectList(
                new QueryWrapper<DishSpecOption>().in("group_id", groupIds).orderByAsc("sort_no"));
        Map<String, DishSpecOption> optionById = new LinkedHashMap<>();
        Map<String, List<DishSpecOption>> selectedByGroup = new LinkedHashMap<>();
        for (DishSpecOption option : options) {
            optionById.put(option.getId(), option);
        }
        if (new HashSet<>(selectedIds).size() != selectedIds.size()) {
            throw new BizException(ErrorCode.INVALID_SPEC);
        }
        for (String selectedId : selectedIds) {
            DishSpecOption option = optionById.get(selectedId);
            if (option == null) {
                // 该选项不属于本菜品
                throw new BizException(ErrorCode.INVALID_SPEC);
            }
            selectedByGroup.computeIfAbsent(option.getGroupId(), k -> new ArrayList<>()).add(option);
        }

        List<String> names = new ArrayList<>();
        BigDecimal unitPrice = dish.getPrice();
        for (DishSpecGroup group : groups) {
            List<DishSpecOption> selected = selectedByGroup.getOrDefault(group.getId(), List.of());
            if (selected.size() > 1) {
                throw new BizException(ErrorCode.INVALID_SPEC);
            }
            boolean required = group.getIsRequired() != null && group.getIsRequired() != 0;
            if (required && selected.isEmpty()) {
                throw new BizException(ErrorCode.INVALID_SPEC);
            }
            if (!selected.isEmpty()) {
                names.add(selected.get(0).getOptionName());
                unitPrice = unitPrice.add(selected.get(0).getExtraPrice());
            }
        }
        String specText = names.isEmpty() ? null : String.join(" / ", names);
        List<String> sortedSelected = selectedIds.stream().sorted().toList();
        String specHash = md5Hex(dish.getId() + "|" + String.join(",", sortedSelected));
        return new SpecResolution(specText, specHash, unitPrice);
    }

    private static String md5Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 not available", ex);
        }
    }

    private record SpecResolution(String specText, String specHash, BigDecimal unitPrice) {
    }
}
