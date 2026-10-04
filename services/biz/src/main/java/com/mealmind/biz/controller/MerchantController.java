package com.mealmind.biz.controller;

import com.mealmind.biz.common.ApiResponse;
import com.mealmind.biz.dto.MenuResponse;
import com.mealmind.biz.dto.MerchantListResponse;
import com.mealmind.biz.dto.MerchantView;
import com.mealmind.biz.service.MerchantService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家与菜单接口（技术方案 §4.1 接口 4/5/6/7）。
 */
@RestController
@RequestMapping("/api")
public class MerchantController {

    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    /** 接口 4：首页推荐商家流。 */
    @GetMapping("/home/merchants")
    public ApiResponse<MerchantListResponse> homeMerchants(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(merchantService.homeMerchants(page, pageSize));
    }

    /** 接口 5：美食外卖页商家列表。 */
    @GetMapping("/foods/merchants")
    public ApiResponse<MerchantListResponse> foodMerchants(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(merchantService.foodMerchants(category, sort, page, pageSize));
    }

    /** 接口 6：商家详情。 */
    @GetMapping("/merchants/{merchantId}")
    public ApiResponse<MerchantView> merchant(@PathVariable String merchantId) {
        return ApiResponse.ok(merchantService.getMerchant(merchantId));
    }

    /** 接口 7：商家菜单。 */
    @GetMapping("/merchants/{merchantId}/menu")
    public ApiResponse<MenuResponse> menu(@PathVariable String merchantId) {
        return ApiResponse.ok(merchantService.getMenu(merchantId));
    }
}
