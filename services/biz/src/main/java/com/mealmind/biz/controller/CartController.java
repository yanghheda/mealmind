package com.mealmind.biz.controller;

import com.mealmind.biz.common.ApiResponse;
import com.mealmind.biz.dto.AddCartItemRequest;
import com.mealmind.biz.dto.AddCartItemResponse;
import com.mealmind.biz.dto.CartResponse;
import com.mealmind.biz.dto.DeleteCartItemResponse;
import com.mealmind.biz.dto.UpdateCartItemRequest;
import com.mealmind.biz.dto.UpdateCartItemResponse;
import com.mealmind.biz.service.CartService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购物车接口（技术方案 §4.1 接口 8/9/10/11）。
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    /** 接口 8：查询购物车。 */
    @GetMapping
    public ApiResponse<CartResponse> cart() {
        return ApiResponse.ok(cartService.getCart());
    }

    /** 接口 9：加入购物车。 */
    @PostMapping("/items")
    public ApiResponse<AddCartItemResponse> addItem(@RequestBody AddCartItemRequest request) {
        return ApiResponse.ok(cartService.addItem(request));
    }

    /** 接口 10：修改数量。 */
    @PatchMapping("/items")
    public ApiResponse<UpdateCartItemResponse> updateItem(@RequestBody UpdateCartItemRequest request) {
        return ApiResponse.ok(cartService.updateItem(request));
    }

    /** 接口 11：删除单条或清空一组。 */
    @DeleteMapping("/items")
    public ApiResponse<DeleteCartItemResponse> deleteItem(
            @RequestParam(required = false) String itemId,
            @RequestParam(required = false) String merchantId) {
        return ApiResponse.ok(cartService.deleteItem(itemId, merchantId));
    }
}
