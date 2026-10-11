package com.mealmind.biz.controller;

import com.mealmind.biz.common.ApiResponse;
import com.mealmind.biz.dto.SearchResponse;
import com.mealmind.biz.service.SearchService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索接口（技术方案 §4.1 接口 21）。
 */
@RestController
@RequestMapping("/api")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /** 接口 21：关键字搜索商家与菜品（keyword 必填，空串/空白返回 400）。 */
    @GetMapping("/search")
    public ApiResponse<SearchResponse> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(searchService.search(keyword, page, pageSize));
    }
}