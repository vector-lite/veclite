import { api } from '../api.js';
import { el, toast, openModal, formModal, confirmModal, escapeHtml, truncate } from '../ui.js';
import { icons, emptyStates } from '../icons.js';

const VIEW_KEY = 'veclite.storeView';

export async function renderStores(container) {
  const names = await api.listStores();
  const statsList = await Promise.all(names.map(async (name) => {
    try { return await api.stats(name); } catch { return null; }
  }));
  const stores = statsList.filter(Boolean);
  let view = localStorage.getItem(VIEW_KEY) === 'table' ? 'table' : 'card';
  let keyword = '';

  container.innerHTML = '';

  container.appendChild(el(`
    <div class="page-header">
      <div>
        <h1 class="page-title">向量库 <span class="counter" data-role="count">${stores.length}</span></h1>
      </div>
      <button class="btn btn-primary" data-action="create-store">${icons.plus()} 新建向量库</button>
    </div>
  `));

  const toolbar = el(`
    <div class="toolbar">
      <input class="input input-search" data-role="search" placeholder="按名称筛选向量库…" />
      <div class="spacer"></div>
      <div class="view-toggle" role="group">
        <button class="btn btn-icon" data-view="card" title="卡片视图">${icons.grid()}</button>
        <button class="btn btn-icon" data-view="table" title="表格视图">${icons.table()}</button>
      </div>
    </div>
  `);
  container.appendChild(toolbar);
  const body = el('<div data-role="body"></div>');
  container.appendChild(body);

  const searchInput = toolbar.querySelector('[data-role=search]');
  searchInput.addEventListener('input', () => {
    keyword = searchInput.value.trim().toLowerCase();
    draw();
  });
  toolbar.querySelectorAll('[data-view]').forEach((btn) => {
    btn.addEventListener('click', () => {
      view = btn.dataset.view;
      localStorage.setItem(VIEW_KEY, view);
      draw();
    });
  });

  container.addEventListener('click', async (event) => {
    const actionEl = event.target.closest('[data-action]');
    if (actionEl) {
      const action = actionEl.dataset.action;
      if (action === 'create-store') await createStoreDialog(() => renderStores(container));
      if (action === 'drop-store') {
        const name = actionEl.dataset.store;
        if (await confirmModal(`确定删除向量库「${name}」？持久化数据将一并删除，不可恢复。`, { danger: true, okLabel: '删除' })) {
          try {
            await api.dropStore(name);
            toast('向量库已删除');
            renderStores(container);
          } catch (e) { toast(e.message, true); }
        }
      }
      return;
    }
    // 卡片 / 表格行整体点击进入详情（操作按钮已在上方拦截并返回）
    const navEl = event.target.closest('[data-nav]');
    if (navEl) location.hash = `#/stores/${encodeURIComponent(navEl.dataset.nav)}`;
  });

  function visible() {
    return stores.filter((s) => s.storeName.toLowerCase().includes(keyword));
  }

  function draw() {
    toolbar.querySelectorAll('[data-view]').forEach((b) => {
      b.style.color = b.dataset.view === view ? 'var(--fg)' : '';
      b.style.background = b.dataset.view === view ? 'var(--border-muted)' : '';
    });
    const list = visible();
    container.querySelector('[data-role=count]').textContent = stores.length;
    if (stores.length === 0) {
      body.innerHTML = '';
      body.appendChild(el(`<div class="panel">${emptyStates.stores()}</div>`));
      return;
    }
    if (list.length === 0) {
      body.innerHTML = '';
      body.appendChild(el(`<div class="panel">${emptyStates.storesFiltered()}</div>`));
      return;
    }
    body.innerHTML = '';
    body.appendChild(el(`<div class="panel">${view === 'card' ? cardView(list) : tableView(list)}</div>`));
  }

  function capacityBar(stats) {
    const ratio = stats.maxCapacity > 0 ? stats.docCount / stats.maxCapacity : 0;
    const cls = ratio >= 1 ? 'full' : ratio >= 0.8 ? 'warn' : '';
    return `
      <div class="capacity" title="${stats.docCount} / ${stats.maxCapacity}">
        <div class="capacity-bar"><i class="${cls}" style="width:${Math.min(100, ratio * 100).toFixed(1)}%"></i></div>
        <span class="mono">${stats.docCount}/${stats.maxCapacity}</span>
      </div>`;
  }

  function descBadges(stats) {
    return `
      <span class="pill">${stats.dimension} 维</span>
      <span class="pill pill-accent">${escapeHtml(stats.metric || 'COSINE')}</span>
      ${stats.quantization && stats.quantization !== 'NONE' ? `<span class="pill pill-warn">${escapeHtml(stats.quantization)}</span>` : ''}
      ${stats.embeddingModel ? `<span class="pill pill-success" title="Embedding 模型">${escapeHtml(truncate(stats.embeddingModel, 28))}</span>` : ''}
    `;
  }

  function dropButton(name) {
    return `<button class="btn btn-icon btn-danger" data-action="drop-store" data-store="${escapeHtml(name)}" title="删除向量库">${icons.trash()}</button>`;
  }

  /** 卡片视图：一行 3-4 个（随宽度自适应），整卡点击进入详情 */
  function cardView(list) {
    return `
      <div class="store-grid">
        ${list.map((s) => `
          <div class="store-card" data-nav="${escapeHtml(s.storeName)}" title="点击进入「${escapeHtml(s.storeName)}」">
            <div class="card-head">
              ${icons.store(16)}
              <span class="card-name">${escapeHtml(s.storeName)}</span>
            </div>
            <div class="card-badges">${descBadges(s)}</div>
            <div class="card-foot">
              ${capacityBar(s)}
              <button class="btn btn-icon btn-danger" data-action="drop-store" data-store="${escapeHtml(s.storeName)}" title="删除向量库">${icons.trash()}</button>
            </div>
          </div>`).join('')}
      </div>`;
  }

  /** 表格视图：整行点击进入详情 */
  function tableView(list) {
    return `
      <div class="table-wrap">
        <table class="table">
          <thead><tr>
            <th>名称</th><th>维度</th><th>度量</th><th>量化</th><th>Embedding 模型</th><th>容量</th><th style="width:60px"></th>
          </tr></thead>
          <tbody>
            ${list.map((s) => `
              <tr data-nav="${escapeHtml(s.storeName)}" title="点击进入「${escapeHtml(s.storeName)}」">
                <td style="font-weight:600;color:var(--accent)">${escapeHtml(s.storeName)}</td>
                <td class="mono">${s.dimension}</td>
                <td class="cell-muted">${escapeHtml(s.metric || '-')}</td>
                <td class="cell-muted">${escapeHtml(s.quantization || '-')}</td>
                <td class="cell-muted">${escapeHtml(s.embeddingModel || '-')}</td>
                <td>${capacityBar(s)}</td>
                <td style="text-align:right;white-space:nowrap">
                  ${dropButton(s.storeName)}
                </td>
              </tr>`).join('')}
          </tbody>
        </table>
      </div>`;
  }

  draw();
}

/** 与后端枚举/配置联动的下拉选项：距离度量、量化方式、Embedding 数据源 */
const METRIC_OPTIONS = [
  { value: 'COSINE', label: 'COSINE' },
  { value: 'EUCLIDEAN', label: 'EUCLIDEAN' },
  { value: 'DOT_PRODUCT', label: 'DOT_PRODUCT' },
];
const QUANTIZATION_OPTIONS = [
  { value: 'NONE', label: 'NONE' },
  { value: 'SQ8', label: 'SQ8' },
];

async function createStoreDialog(onDone) {
  let models = [];
  try { models = (await api.embeddingModels()) || []; } catch { /* 模型接口不可用时允许不绑定 */ }

  const modelOptions = [
    { value: '', label: '不绑定' },
    ...models.map((m) => ({
      value: `${m.name}\u001F${m.version || '1'}`,
      label: `${m.name} v${m.version || '1'}${m.defaultModel ? '（默认）' : ''}`,
    })),
  ];

  const values = await formModal({
    title: '新建向量库',
    okLabel: '创建',
    fields: [
      { name: 'storeName', label: '名称', placeholder: '例如 knowledge-base' },
      { name: 'embeddingModel', label: 'Embedding 数据源', type: 'select', options: modelOptions },
      { name: 'dimension', label: '向量维度', type: 'number', value: '512' },
      { name: 'metric', label: '距离度量', type: 'select', options: METRIC_OPTIONS },
      { name: 'maxCapacity', label: '最大容量', type: 'number', value: '100000' },
      { name: 'quantization', label: '量化方式', type: 'select', options: QUANTIZATION_OPTIONS },
      { name: 'indexedMetadataFields', label: '过滤索引字段', placeholder: 'category,userId' },
    ],
  });
  if (!values) return;
  if (!values.storeName) { toast('请输入向量库名称', true); return; }

  const dim = Number(values.dimension);
  if (!Number.isInteger(dim) || dim <= 0) { toast('维度须为正整数', true); return; }
  const cap = Number(values.maxCapacity);
  if (!Number.isInteger(cap) || cap <= 0) { toast('容量须为正整数', true); return; }

  // 选项值为 "模型名\u001F版本"，拆开后（名称, 版本）一起绑定到 Store
  let boundModel = null;
  let boundVersion = null;
  if (values.embeddingModel) {
    const sep = values.embeddingModel.indexOf('\u001F');
    boundModel = sep >= 0 ? values.embeddingModel.slice(0, sep) : values.embeddingModel;
    boundVersion = sep >= 0 ? values.embeddingModel.slice(sep + 1) : null;
  }

  const definition = {
    storeName: values.storeName,
    dimension: dim,
    metric: values.metric || 'COSINE',
    maxCapacity: cap,
    quantization: values.quantization || 'NONE',
    embeddingModel: boundModel,
    embeddingModelVersion: boundVersion,
    indexedMetadataFields: values.indexedMetadataFields
      ? values.indexedMetadataFields.split(',').map((s) => s.trim()).filter(Boolean)
      : [],
  };

  try {
    await api.createStore(values.storeName, definition);
    toast('向量库已创建');
    location.hash = `#/stores/${encodeURIComponent(values.storeName)}`;
  } catch (e) {
    toast(e.message, true);
  }
}
