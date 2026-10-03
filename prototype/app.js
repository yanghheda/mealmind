/* MealMind Prototype · app.js
   全局脚本：Tab 激活、占位入口统一 Toast、购物车角标、加购飞入动效 */

(function () {
  /* ---------- 1. Tab 激活（唯一机制：按 body[data-page] 匹配 data-nav） ---------- */
  var page = document.body.dataset.page;
  if (page) {
    document.querySelectorAll('.tabbar [data-nav]').forEach(function (a) {
      if (a.dataset.nav === page) a.classList.add('is-active');
    });
  }

  /* ---------- 2. Toast ---------- */
  var toastEl = null, toastTimer = null;
  function showToast(msg) {
    if (!toastEl) {
      toastEl = document.createElement('div');
      toastEl.className = 'toast';
      document.body.appendChild(toastEl);
    }
    toastEl.textContent = msg;
    requestAnimationFrame(function () { toastEl.classList.add('show'); });
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastEl.classList.remove('show'); }, 1800);
  }
  window.showToast = showToast;

  /* ---------- 3. 占位入口统一提示（禁止死链） ---------- */
  document.addEventListener('click', function (e) {
    var ph = e.target.closest ? e.target.closest('.js-placeholder') : null;
    if (ph) {
      e.preventDefault();
      showToast(ph.dataset.toast || '演示版本未开放');
    }
  });

  /* ---------- 4. 购物车角标 ---------- */
  function syncBadge() {
    var n = window.Store ? Store.count() : 0;
    document.querySelectorAll('[data-cart-badge]').forEach(function (b) {
      b.textContent = n > 99 ? '99+' : String(n);
      b.hidden = n === 0;
    });
  }
  document.addEventListener('cart:change', syncBadge);
  syncBadge();
  window.syncCartBadge = syncBadge;

  /* ---------- 5. 加购飞入购物车动效（从卡片飞向购物车 Tab） ---------- */
  window.flyToCart = function (fromEl, opts) {
    opts = opts || {};
    if (!fromEl) { if (opts.done) opts.done(); return; }
    var target = document.querySelector('.tabbar [data-nav="cart"]') || document.querySelector('[data-cart-target]');

    var s = fromEl.getBoundingClientRect();
    var t = target ? target.getBoundingClientRect()
                   : { left: window.innerWidth - 64, top: 40, width: 36, height: 36 };
    var size = Math.max(s.width, 44);
    var fly = document.createElement('img');
    var srcImg = fromEl.querySelector && fromEl.querySelector('img');
    var src = opts.image || (srcImg ? (srcImg.currentSrc || srcImg.src) : '');
    if (src) { fly.src = src; } else { fly.style.background = 'linear-gradient(135deg,#FF7A00,#FF3D6E)'; }
    fly.alt = '';
    fly.style.cssText =
      'position:fixed;left:' + s.left + 'px;top:' + s.top + 'px;width:' + size + 'px;height:' + size + 'px;' +
      'border-radius:50%;object-fit:cover;z-index:300;pointer-events:none;' +
      'box-shadow:0 6px 16px rgba(0,0,0,.22);' +
      'transition:transform .55s cubic-bezier(.3,-.2,.7,1),opacity .55s ease;';
    document.body.appendChild(fly);

    var dx = (t.left + t.width / 2) - (s.left + size / 2);
    var dy = (t.top + t.height / 2) - (s.top + size / 2);
    requestAnimationFrame(function () {
      fly.style.transform = 'translate(' + dx + 'px,' + dy + 'px) scale(.35)';
      fly.style.opacity = '.55';
    });
    setTimeout(function () {
      fly.remove();
      var badge = document.querySelector('[data-cart-badge]');
      if (badge) { badge.classList.remove('pop'); void badge.offsetWidth; badge.classList.add('pop'); }
      if (opts.done) opts.done();
    }, 560);
  };
})();