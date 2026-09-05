import { renderStores } from './views/stores.js?v=2.5.1';
import { renderStore } from './views/store.js?v=2.5.1';
import { renderSettings } from './views/settings.js?v=2.5.1';
import { el, toast, escapeHtml } from './ui.js';

const app = document.getElementById('app');

const routes = [
  { pattern: /^#\/$/, view: () => renderStores(app), nav: 'stores' },
  { pattern: /^#\/settings$/, view: () => renderSettings(app), nav: 'settings' },
  { pattern: /^#\/stores\/([^/]+)\/documents$/, view: (m) => renderStore(app, decodeURIComponent(m[1]), 'documents'), nav: 'stores' },
  { pattern: /^#\/stores\/([^/]+)\/search$/, view: (m) => renderStore(app, decodeURIComponent(m[1]), 'search'), nav: 'stores' },
  { pattern: /^#\/stores\/([^/]+)$/, view: (m) => renderStore(app, decodeURIComponent(m[1]), 'documents'), nav: 'stores' },
];

async function dispatch() {
  const hash = location.hash || '#/';
  const route = routes.find((r) => r.pattern.test(hash));
  highlightNav(route ? route.nav : 'stores');

  if (!route) {
    app.innerHTML = '';
    app.appendChild(el(`<div class="panel"><div class="empty">
      <div class="empty-title">页面不存在</div>
      <a class="btn" href="#/">返回向量库列表</a>
    </div></div>`));
    return;
  }
  try {
    await route.view(hash.match(route.pattern));
  } catch (e) {
    app.innerHTML = '';
    app.appendChild(el(`<div class="panel"><div class="empty">
      <div class="empty-title">加载失败</div>
      <div class="empty-hint">${escapeHtml(e.message)}</div>
      <button class="btn" onclick="location.reload()">重试</button>
    </div></div>`));
  }
}

function highlightNav(key) {
  document.querySelectorAll('[data-nav]').forEach((a) => {
    a.classList.toggle('active', a.dataset.nav === key);
  });
}

window.addEventListener('hashchange', dispatch);
dispatch();
