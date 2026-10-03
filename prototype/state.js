/* MealMind Prototype · state.js
   前端状态单源（window.Store）：游客会话 + 购物车（localStorage 持久化）
   说明：登录态无过期时间，退出登录 / 清除浏览器数据即重置 */

(function () {
  var LS = {
    read: function (key, fallback) {
      try { var v = localStorage.getItem(key); return v ? JSON.parse(v) : fallback; } catch (e) { return fallback; }
    },
    write: function (key, value) {
      try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* 忽略 */ }
    },
    remove: function (key) {
      try { localStorage.removeItem(key); } catch (e) { /* 忽略 */ }
    }
  };

  var Store = {
    /* ---------- 游客会话 ---------- */
    session: LS.read('mealmind.session', null),

    login: function () {
      var prefix = (window.DB && window.DB.nicknamePrefix) || '干饭人_';
      this.session = {
        nickname: prefix + Math.random().toString(16).slice(2, 6).toUpperCase(),
        loginAt: Date.now()
      };
      LS.write('mealmind.session', this.session);
      return this.session;
    },

    logout: function () {
      this.session = null;
      LS.remove('mealmind.session');
      this.cart = [];
      this.saveCart();
    },

    /* ---------- 购物车 ---------- */
    cart: LS.read('mealmind.cart', []),

    saveCart: function () {
      LS.write('mealmind.cart', this.cart);
      document.dispatchEvent(new CustomEvent('cart:change'));
    },

    addItem: function (item) {
      var exist = null;
      for (var i = 0; i < this.cart.length; i++) {
        if (this.cart[i].dishId === item.dishId && this.cart[i].spec === item.spec) { exist = this.cart[i]; break; }
      }
      if (exist) { exist.qty += (item.qty || 1); } else {
        this.cart.push({
          dishId: item.dishId, name: item.name, spec: item.spec || '标准',
          price: item.price, qty: item.qty || 1,
          merchantId: item.merchantId, merchantName: item.merchantName, img: item.img || ''
        });
      }
      this.saveCart();
    },

    setQty: function (dishId, spec, qty) {
      for (var i = 0; i < this.cart.length; i++) {
        if (this.cart[i].dishId === dishId && this.cart[i].spec === spec) {
          this.cart[i].qty = qty;
          if (qty <= 0) this.cart.splice(i, 1);
          break;
        }
      }
      this.saveCart();
    },

    clear: function () { this.cart = []; this.saveCart(); },

    loadSeed: function () {
      this.cart = (window.DB && window.DB.cartSeed ? window.DB.cartSeed : []).map(function (i) {
        return Object.assign({}, i);
      });
      this.saveCart();
    },

    count: function () {
      return this.cart.reduce(function (s, i) { return s + i.qty; }, 0);
    },

    total: function () {
      return this.cart.reduce(function (s, i) { return s + i.price * i.qty; }, 0);
    }
  };

  window.Store = Store;
})();