import { api } from '../api.js';
import { el, toast, formModal, confirmModal, escapeHtml, truncate, prettyJson } from '../ui.js';
import { icons, emptyStates } from '../icons.js';

const PAGE_SIZE_KEY = 'veclite.docPageSize';

export async function renderStore(container, storeName, tab = 'documents') {
  let stats;
  try {
    stats = await api.stats(storeName);
  } catch (e) {
    container.innerHTML = '';
    container.appendChild(el(`
      <div class="panel">
        <div class="empty">
          ${icons.search(40)}
          <div class="empty-title">向量库「${escapeHtml(storeName)}」不存在</div>
          <div class="empty-hint">它可能刚被删除，或尚未在服务端加载</div>
          <a class="btn" href="#/">返回向量库列表</a>
        </div>
      </div>`));
    return;
  }

  container.innerHTML = '';

  // 页头：面包屑 + 徽标 + 运维操作
  container.appendChild(el(`
    <div class="page-header">
      <div>
        <div style="font-size:13px;color:var(--fg-muted);margin-bottom:4px">
          <a href="#/">向量库</a> / <b style="color:var(--fg)">${escapeHtml(storeName)}</b>
        </div>
        <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">
          <span class="pill">${stats.dimension} 维</span>
          <span class="pill pill-accent">${escapeHtml(stats.metric || 'COSINE')}</span>
          <span class="pill ${stats.quantization === 'SQ8' ? 'pill-warn' : ''}">${escapeHtml(stats.quantization || 'NONE')}</span>
          ${stats.embeddingModel ? `<span class="pill pill-success">${escapeHtml(truncate(stats.embeddingModel, 32))}</span>` : ''}
          <span class="pill" title="文档数 / 最大容量">${stats.docCount}/${stats.maxCapacity} 条</span>
        </div>
      </div>
      <div style="display:flex;gap:6px">
        <button class="btn" data-op="refresh" title="将内存数据刷盘持久化">${icons.download()} 刷盘</button>
        <button class="btn" data-op="reload" title="从持久化数据重载内存">${icons.upload()} 重载</button>
        <button class="btn btn-danger" data-op="drop">${icons.trash()} 删除</button>
      </div>
    </div>
  `));

  // Tab 导航（文档数据 / 查询调试）
  const tabs = el(`
    <div class="tabs">
      <a class="tab ${tab === 'documents' ? 'active' : ''}" href="#/stores/${encodeURIComponent(storeName)}/documents">文档数据</a>
      <a class="tab ${tab === 'search' ? 'active' : ''}" href="#/stores/${encodeURIComponent(storeName)}/search">查询调试</a>
    </div>
  `);
  container.appendChild(tabs);

  const body = el('<div></div>');
  container.appendChild(body);

  container.querySelector('[data-op=refresh]').addEventListener('click', async () => {
    try { await api.refresh(storeName); toast('已刷盘'); } catch (e) { toast(e.message, true); }
  });
  container.querySelector('[data-op=reload]').addEventListener('click', async () => {
    try {
      await api.reload(storeName);
      toast('已重载');
      renderStore(container, storeName, tab);
    } catch (e) { toast(e.message, true); }
  });
  container.querySelector('[data-op=drop]').addEventListener('click', async () => {
    if (await confirmModal(`确定删除向量库「${storeName}」？持久化数据将一并删除，不可恢复。`, { danger: true, okLabel: '删除' })) {
      try {
        await api.dropStore(storeName);
        toast('向量库已删除');
        location.hash = '#/';
      } catch (e) { toast(e.message, true); }
    }
  });

  if (tab === 'search') {
    renderSearchDebug(body, storeName, stats);
  } else {
    await renderDocuments(body, storeName, stats);
  }
}

/* ================= 文档数据 ================= */

async function renderDocuments(container, storeName, stats) {
  let page = 1;
  let pageSize = Number(localStorage.getItem(PAGE_SIZE_KEY)) || 20;
  let total = stats.docCount;

  const wrap = el(`
    <div>
      <div class="toolbar">
        <span class="cell-muted" style="font-size:13px">共 <b class="mono">${stats.docCount}</b> 条文档</span>
        <div class="spacer"></div>
        <button class="btn" data-action="delete-docs">${icons.trash()} 删除文档</button>
        <button class="btn btn-primary" data-action="insert-doc">${icons.plus()} 插入文档</button>
      </div>
      <div class="panel" data-role="table"></div>
    </div>
  `);
  container.appendChild(wrap);

  wrap.querySelector('[data-action=insert-doc]').addEventListener('click', () => insertDocDialog());
  wrap.querySelector('[data-action=delete-docs]').addEventListener('click', () => deleteDocsDialog());

  await draw();

  async function draw() {
    const tableHost = wrap.querySelector('[data-role=table]');
    try {
      const result = await api.listDocuments(storeName, page, pageSize);
      const items = result.items || [];
      total = result.total ?? total;

      if (items.length === 0) {
        tableHost.innerHTML = '';
        tableHost.appendChild(el(emptyStates.documents()));
        return;
      }

      tableHost.innerHTML = `
        <div class="table-wrap">
          <table class="table">
            <thead><tr><th style="width:26%">ID</th><th>文本</th><th style="width:30%">Metadata</th><th style="width:60px"></th></tr></thead>
            <tbody>
              ${items.map((d) => `
                <tr>
                  <td class="mono" title="${escapeHtml(d.id)}">${escapeHtml(truncate(d.id, 40))}</td>
                  <td class="cell-truncate" title="${escapeHtml(d.text || '')}">${d.text ? escapeHtml(truncate(d.text, 90)) : '<span class="cell-muted">—</span>'}</td>
                  <td class="cell-truncate mono" title="${escapeHtml(prettyJson(d.metadata || {}))}">${escapeHtml(truncate(prettyJson(d.metadata || {}), 60))}</td>
                  <td style="text-align:right">
                    <button class="btn btn-icon btn-danger" data-del="${escapeHtml(d.id)}" title="删除该文档">${icons.trash()}</button>
                  </td>
                </tr>`).join('')}
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <span>第 ${result.page} 页 · 共 ${Math.max(1, Math.ceil(total / pageSize))} 页 / ${total} 条</span>
          <select class="select" data-role="size" style="padding:2px 8px">
            ${[10, 20, 50, 100].map((n) => `<option value="${n}" ${n === pageSize ? 'selected' : ''}>${n} 条/页</option>`).join('')}
          </select>
          <button class="btn btn-sm" data-page="prev" ${page <= 1 ? 'disabled' : ''}>上一页</button>
          <button class="btn btn-sm" data-page="next" ${page * pageSize >= total ? 'disabled' : ''}>下一页</button>
        </div>`;

      tableHost.querySelectorAll('[data-del]').forEach((btn) => {
        btn.addEventListener('click', async () => {
          const id = btn.dataset.del;
          if (await confirmModal(`确定删除文档「${truncate(id, 60)}」？`, { danger: true, okLabel: '删除' })) {
            try {
              await api.deleteDocuments(storeName, [id]);
              toast('文档已删除');
              draw();
            } catch (e) { toast(e.message, true); }
          }
        });
      });
      tableHost.querySelector('[data-role=size]').addEventListener('change', (event) => {
        pageSize = Number(event.target.value);
        localStorage.setItem(PAGE_SIZE_KEY, String(pageSize));
        page = 1;
        draw();
      });
      tableHost.querySelector('[data-page=prev]').addEventListener('click', () => { page--; draw(); });
      tableHost.querySelector('[data-page=next]').addEventListener('click', () => { page++; draw(); });
    } catch (e) {
      toast(e.message, true);
    }
  }

  async function refreshStats() { /* 条数以重进页面后的 stats 为准，这里仅触发表格刷新 */ }

  async function insertDocDialog() {
    const values = await formModal({
      title: `插入文档 → ${storeName}`,
      okLabel: '写入',
      fields: [
        { name: 'id', label: '文档 ID' },
        { name: 'text', label: '文本', type: 'textarea' },
        { name: 'vector', label: '向量', type: 'textarea', mono: true, placeholder: '[0.1, 0.2, …]' },
        { name: 'metadata', label: 'Metadata', type: 'textarea', mono: true, placeholder: '{"category": "demo"}' },
      ],
    });
    if (!values) return;
    if (!values.id) { toast('文档 ID 必填', true); return; }

    let vector = null;
    if (values.vector) {
      try {
        vector = JSON.parse(values.vector);
        if (!Array.isArray(vector) || vector.length === 0) throw new Error();
      } catch { toast('向量须为非空 JSON 数组', true); return; }
    }
    if (!vector && !values.text) { toast('向量与文本至少填一项', true); return; }

    let metadata = null;
    if (values.metadata) {
      try {
        metadata = JSON.parse(values.metadata);
        if (typeof metadata !== 'object' || Array.isArray(metadata)) throw new Error();
      } catch { toast('Metadata 须为 JSON 对象', true); return; }
    }

    try {
      await api.upsertDocument(storeName, { id: values.id, vector, text: values.text || null, metadata });
      toast('文档已写入');
      draw();
    } catch (e) { toast(e.message, true); }
  }

  async function deleteDocsDialog() {
    const values = await formModal({
      title: `批量删除文档 → ${storeName}`,
      okLabel: '删除',
      fields: [
        { name: 'ids', label: '文档 ID 列表', type: 'textarea', rows: 4, mono: true, placeholder: 'id-1, id-2, …（逗号或换行分隔）' },
      ],
    });
    if (!values || !values.ids) return;
    const ids = values.ids.split(/[\n,，]/).map((s) => s.trim()).filter(Boolean);
    if (ids.length === 0) { toast('请输入至少一个文档 ID', true); return; }
    if (!(await confirmModal(`确定删除 ${ids.length} 条文档？`, { danger: true, okLabel: '删除' }))) return;

    try {
      const result = await api.deleteDocuments(storeName, ids);
      toast(`已删除 ${result.deletedCount ?? ids.length} 条文档`);
      draw();
    } catch (e) { toast(e.message, true); }
  }
}

/* ================= 查询调试 ================= */

function renderSearchDebug(container, storeName, stats) {
  const conditionPanel = el(`
    <div class="panel" style="margin-bottom:16px">
      <div class="panel-header">查询条件</div>
      <div style="padding:16px">
        <div class="toolbar" style="margin-bottom:10px">
          <div style="display:flex;border:1px solid var(--border);border-radius:var(--radius);overflow:hidden">
            <button class="btn" data-mode="text" style="border:none;border-radius:0">文本查询</button>
            <button class="btn" data-mode="vector" style="border:none;border-radius:0">向量查询</button>
          </div>
          <label class="mono" style="font-size:12px;color:var(--fg-muted);display:flex;align-items:center;gap:6px">
            TopK <input class="input" data-role="topk" type="number" value="5" min="1" style="width:80px">
          </label>
          ${!stats.embeddingModel ? '<span class="pill pill-warn" title="未绑定 Embedding 模型，文本查询不可用">未绑定 Embedding 模型</span>' : ''}
        </div>

        <div data-role="query-area">
          <label class="field"><span>查询文本</span>
            <input class="input input-full" data-role="query-text" placeholder="例如：如何检索语义相似的内容">
          </label>
        </div>

        <div class="filter-rows" data-role="filters"></div>
        <div style="display:flex;gap:8px;align-items:center">
          <button class="btn" data-action="add-filter">${icons.plus()} 添加元数据过滤</button>
          <div class="spacer" style="flex:1"></div>
          <button class="btn btn-primary" data-action="run-search">${icons.search(14)} 执行查询</button>
        </div>
      </div>
    </div>
  `);
  const results = el('<div data-role="results"></div>');
  container.appendChild(conditionPanel);
  container.appendChild(results);

  let mode = stats.embeddingModel ? 'text' : 'vector';
  const queryArea = conditionPanel.querySelector('[data-role=query-area]');
  const filters = conditionPanel.querySelector('[data-role=filters]');

  function drawMode() {
    conditionPanel.querySelectorAll('[data-mode]').forEach((b) => {
      b.style.background = b.dataset.mode === mode ? 'var(--fg)' : 'transparent';
      b.style.color = b.dataset.mode === mode ? '#fff' : 'var(--fg-muted)';
    });
    if (mode === 'text') {
      queryArea.innerHTML = `
        <label class="field"><span>查询文本</span>
          <input class="input input-full" data-role="query-text" placeholder="例如：如何检索语义相似的内容">
        </label>`;
    } else {
      queryArea.innerHTML = `
        <label class="field"><span>查询向量</span>
          <textarea class="input input-full mono" data-role="query-vector" rows="3" placeholder="[0.1, 0.2, …]"></textarea>
        </label>`;
    }
  }
  conditionPanel.querySelectorAll('[data-mode]').forEach((b) => b.addEventListener('click', () => { mode = b.dataset.mode; drawMode(); }));
  drawMode();

  conditionPanel.querySelector('[data-action=add-filter]').addEventListener('click', () => {
    const row = el(`
      <div class="filter-row">
        <input class="input" data-f="field" placeholder="字段名，如 category" style="width:180px">
        <select class="select" data-f="op" style="width:90px">
          <option value="EQ">等于</option>
          <option value="IN">属于</option>
          <option value="GT">大于</option>
          <option value="LT">小于</option>
        </select>
        <input class="input" data-f="value" placeholder="值（IN 用逗号分隔）" style="flex:1">
        <button class="btn btn-icon btn-danger" title="移除">${icons.trash(14)}</button>
      </div>`);
    row.querySelector('button').addEventListener('click', () => row.remove());
    row.querySelector('[data-f=op]').addEventListener('change', (e) => onOpChange(row, e.target.value));
    filters.appendChild(row);
  });

  // 算子变更时调整 placeholder，避免 GT/LT 输入"a,b"被误识别
  function onOpChange(row, op) {
    const valueInput = row.querySelector('[data-f=value]');
    if (op === 'GT' || op === 'LT') {
      valueInput.placeholder = '数值（如 0.5、100）';
      valueInput.type = 'number';
      valueInput.step = 'any';
    } else if (op === 'IN') {
      valueInput.placeholder = '值（逗号分隔，如 a,b,c）';
      valueInput.type = 'text';
      valueInput.removeAttribute('step');
    } else {
      valueInput.placeholder = '值';
      valueInput.type = 'text';
      valueInput.removeAttribute('step');
    }
  }

  conditionPanel.querySelector('[data-action=run-search]').addEventListener('click', async () => {
    const topK = Math.max(1, Number(container.querySelector('[data-role=topk]').value) || 5);
    const filter = buildFilter();
    let response;
    try {
      if (mode === 'text') {
        const text = queryArea.querySelector('[data-role=query-text]')?.value.trim();
        if (!text) { toast('请输入查询文本', true); return; }
        response = await api.searchText(storeName, { queryText: text, topK, filter });
      } else {
        let vector;
        try { vector = JSON.parse(queryArea.querySelector('[data-role=query-vector]').value); }
        catch { toast('查询向量须为合法 JSON 数组', true); return; }
        if (!Array.isArray(vector) || vector.length !== stats.dimension) {
          toast(`查询向量须为 ${stats.dimension} 维数组`, true);
          return;
        }
        response = await api.searchVector(storeName, { queryVector: vector, topK, filter });
      }
      drawResults(response || []);
    } catch (e) {
      results.innerHTML = '';
      results.appendChild(el(`<div class="panel"><div class="empty" style="padding:32px">
        <div class="empty-title">查询失败</div><div class="empty-hint">${escapeHtml(e.message)}</div>
      </div></div>`));
    }
  });

  function buildFilter() {
    const rows = [...filters.querySelectorAll('.filter-row')];
    if (rows.length === 0) return null;
    if (rows.length > 1) toast('引擎当前支持单条件过滤，已取第一行', true);
    const first = rows[0];
    const op = first.querySelector('[data-f=op]').value;
    const field = first.querySelector('[data-f=field]').value.trim();
    if (!field) return null;
    const raw = first.querySelector('[data-f=value]').value.trim();

    if (op === 'IN') {
      // IN 列表：只把"纯数字字面量"转成 Number（与元数据 JSONB number 对齐），
      // 字符串"abc"和"true"原样保留——避免过去 coerce 一刀切导致既不命中数字也不命中字符串。
      const values = raw.split(/[,，]/).map((s) => s.trim()).filter(Boolean).map(coerceEq);
      return { field, operator: 'IN', values };
    }
    if (op === 'GT' || op === 'LT') {
      // 强制数值，无法解析则提示
      const num = Number(raw);
      if (raw === '' || Number.isNaN(num)) {
        toast('大于/小于需要数值类型', true);
        return null;
      }
      return { field, operator: op, value: num };
    }
    // EQ：保留字符串（除非前后空格后看起来是数字/布尔，且用户没显式标引号）
    return { field, operator: 'EQ', value: coerceEq(raw) };
  }

  // EQ 专用：尽量"原样"。仅当用户输入的字符串解析后与原值完全相同时才转类型，
  // 避免把"123"盲目转成 Number(123) 导致和字符串"123"不匹配。
  function coerceEq(text) {
    if (text === '') return text;
    if (text === 'true') return true;
    if (text === 'false') return false;
    // 仅当整个串就是个纯数字字面量才转 Number
    if (/^-?\d+(\.\d+)?$/.test(text)) return Number(text);
    return text;
  }

  function drawResults(items) {
    results.innerHTML = '';
    if (items.length === 0) {
      results.appendChild(el(emptyStates.searchEmpty()));
      return;
    }
    const maxScore = Math.max(...items.map((r) => r.score || 0), 0.0001);
    const panel = el('<div class="panel"><div class="panel-header">查询结果 <span class="counter">' + items.length + '</span></div><div style="padding:12px 16px"></div></div>');
    const list = panel.lastElementChild;
    for (const r of items) {
      const card = el(`
        <div class="result-card">
          <div class="result-head">
            <span class="score">${(r.score ?? 0).toFixed(4)}</span>
            <div class="score-bar"><i style="width:${((r.score / maxScore) * 100).toFixed(1)}%"></i></div>
            <span class="mono" style="color:var(--fg-muted);font-size:12px">${escapeHtml(truncate(r.id, 44))}</span>
          </div>
          <div class="result-text">${r.text ? escapeHtml(r.text) : '<span class="cell-muted">（无文本）</span>'}</div>
          ${Object.keys(r.metadata || {}).length ? `<details><summary>metadata</summary><pre>${escapeHtml(prettyJson(r.metadata))}</pre></details>` : ''}
          ${Array.isArray(r.vector) ? `<details><summary>向量（${r.vector.length} 维）</summary><pre>${escapeHtml(prettyJson(r.vector.map((v) => Number(v.toFixed(6)))))}</pre></details>` : ''}
        </div>`);
      list.appendChild(card);
    }
    results.appendChild(panel);
  }
}
