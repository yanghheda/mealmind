/* MealMind Prototype · api.js
   接口桩层：函数签名与未来真实接口保持一致，内部返回 mock 数据。
   带 delay 以呈现 loading / 骨架屏状态；接入后端时仅替换实现，保持出参形状不变。 */

(function () {
  var delay = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };

  var API = {
    /**
     * GET /api/home/merchants
     * → { code: 0, data: Merchant[] }
     */
    fetchHomeMerchants: async function () {
      await delay(560); // TODO: replace with fetch('/api/home/merchants')
      return { code: 0, data: window.DB.merchants.slice(0, 6) };
    },

    /**
     * GET /api/foods/merchants
     * → { code: 0, data: Merchant[] }
     */
    fetchFoodsMerchants: async function () {
      await delay(560); // TODO: replace with fetch('/api/foods/merchants')
      return { code: 0, data: window.DB.merchants };
    },

    /**
     * POST /api/agent/decision
     * req: { text: string, context?: object }
     * → { code: 0, data: { reply, cards } }  —— AI 决策结果（Jev 打分）
     * 当前为原型占位实现，不调用真实模型。
     */
    askAgent: async function (text) {
      await delay(700); // TODO: replace with real agent call
      return { code: 0, data: { reply: '（原型占位）已收到需求：' + text, cards: [] } };
    }
  };

  window.delay = delay;
  window.API = API;
})();