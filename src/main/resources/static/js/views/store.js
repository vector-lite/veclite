import { api } from '../api.js';
import { el, toast, formModal, confirmModal, openModal, escapeHtml, truncate, prettyJson, copyToClipboard } from '../ui.js';
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
        <span class="cell-muted" style="font-size:13px">共 <b class="mono" data-role="total-count">${stats.docCount}</b> 条文档</span>
        <div class="spacer"></div>
        <button class="btn" data-action="seed-docs">随机入库</button>
        <button class="btn" data-action="delete-docs">${icons.trash()} 删除文档</button>
        <button class="btn btn-primary" data-action="insert-doc">${icons.plus()} 插入文档</button>
      </div>
      <div class="panel" data-role="table"></div>
    </div>
  `);
  container.appendChild(wrap);

  wrap.querySelector('[data-action=seed-docs]').addEventListener('click', () => seedDocsDialog());
  wrap.querySelector('[data-action=insert-doc]').addEventListener('click', () => insertDocDialog());
  wrap.querySelector('[data-action=delete-docs]').addEventListener('click', () => deleteDocsDialog());

  await draw();

  async function draw() {
    const tableHost = wrap.querySelector('[data-role=table]');
    try {
      const result = await api.listDocuments(storeName, page, pageSize);
      const items = result.items || [];
      total = result.total ?? total;
      const countEl = wrap.querySelector('[data-role=total-count]');
      if (countEl) countEl.textContent = total;

      if (items.length === 0) {
        tableHost.innerHTML = '';
        tableHost.appendChild(el(emptyStates.documents()));
        return;
      }

      tableHost.innerHTML = `
        <div class="table-wrap">
          <table class="table">
            <thead><tr>
              <th style="width:20%">ID</th>
              <th>文本</th>
              <th style="width:70px;text-align:center">向量</th>
              <th style="width:28%">Metadata</th>
              <th style="width:50px"></th>
            </tr></thead>
            <tbody>
              ${items.map((d) => `
                <tr>
                  <td class="mono" title="${escapeHtml(d.id)}">${escapeHtml(truncate(d.id, 36))}</td>
                  <td>
                    <div style="display:inline-flex;align-items:center;gap:4px;max-width:100%">
                      <span class="cell-truncate" style="min-width:0" title="${escapeHtml(d.text || '')}">${d.text ? escapeHtml(truncate(d.text, 80)) : '<span class="cell-muted">—</span>'}</span>
                      ${d.text ? `<button class="btn btn-icon" data-copy-text="${escapeHtml(d.id)}" style="flex-shrink:0;padding:2px 4px" title="复制文本">${icons.copy()}</button>` : ''}
                    </div>
                  </td>
                  <td style="text-align:center;white-space:nowrap">
                    <button class="btn btn-icon" data-view-vector="${escapeHtml(d.id)}" title="查看向量数据与可视化">${icons.chart()}</button>
                  </td>
                  <td>
                    <div style="display:inline-flex;align-items:center;gap:4px;max-width:100%">
                      <span class="cell-truncate mono" style="min-width:0;cursor:pointer" data-view-meta="${escapeHtml(d.id)}" title="点击查看完整 Metadata">${escapeHtml(truncate(prettyJson(d.metadata || {}), 45))}</span>
                      <button class="btn btn-icon" data-view-meta="${escapeHtml(d.id)}" style="flex-shrink:0;padding:2px 4px" title="查看完整 Metadata">${icons.eye()}</button>
                    </div>
                  </td>
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

      tableHost.querySelectorAll('[data-copy-text]').forEach((btn) => {
        btn.addEventListener('click', async (e) => {
          e.stopPropagation();
          const id = btn.dataset.copyText;
          const d = items.find((it) => it.id === id);
          if (!d || !d.text) return;
          await copyToClipboard(d.text, '文本已复制到剪贴板');
        });
      });

      tableHost.querySelectorAll('[data-view-meta]').forEach((el) => {
        el.addEventListener('click', (e) => {
          e.stopPropagation();
          const id = el.dataset.viewMeta;
          const d = items.find((it) => it.id === id);
          if (!d) return;
          showMetadataModal(d.id, d.metadata);
        });
      });

      tableHost.querySelectorAll('[data-view-vector]').forEach((btn) => {
        btn.addEventListener('click', async (e) => {
          e.stopPropagation();
          const id = btn.dataset.viewVector;
          const d = items.find((it) => it.id === id);
          if (!d) return;
          try {
            if (!d.vector) {
              const doc = await api.getDocument(storeName, id);
              d.vector = doc?.vector;
            }
            if (!d.vector || !d.vector.length) {
              toast('该文档暂无向量数据', true);
              return;
            }
            showVectorModal(d.id, d.vector);
          } catch (err) {
            toast(err.message || '获取向量失败', true);
          }
        });
      });

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

  function showVectorModal(docId, vector) {
    if (!Array.isArray(vector) || vector.length === 0) {
      toast('该文档暂无有效向量', true);
      return;
    }
    const dim = vector.length;
    let min = vector[0];
    let max = vector[0];
    let sum = 0;
    let sumSq = 0;
    for (let i = 0; i < dim; i++) {
      const v = vector[i];
      if (v < min) min = v;
      if (v > max) max = v;
      sum += v;
      sumSq += v * v;
    }
    const norm = Math.sqrt(sumSq);
    const avg = sum / dim;

    // SVG 可视化图谱：中心零点对称柱状图
    const svgWidth = 460;
    const svgHeight = 90;
    const zeroY = 45;
    const absMax = Math.max(Math.abs(min), Math.abs(max), 0.0001);

    const barCount = Math.min(dim, 120);
    const step = dim / barCount;
    const barWidth = Math.max(1.5, ((svgWidth - 20) / barCount) - 0.8);
    const bars = [];

    for (let i = 0; i < barCount; i++) {
      const idx = Math.floor(i * step);
      const val = vector[idx];
      const x = 10 + i * ((svgWidth - 20) / barCount);
      const barH = Math.min(38, Math.max(1, (Math.abs(val) / absMax) * 38));
      const y = val >= 0 ? (zeroY - barH) : zeroY;
      const color = val >= 0 ? 'var(--accent)' : 'var(--danger)';
      bars.push(`<rect x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${barWidth.toFixed(1)}" height="${barH.toFixed(1)}" fill="${color}" rx="0.5" opacity="0.85"><title>dim[${idx}]: ${val.toFixed(8)}</title></rect>`);
    }

    const svgChart = `
      <div style="background:var(--bg-subtle);border:1px solid var(--border-muted);border-radius:var(--radius);padding:10px;margin-bottom:12px">
        <div style="display:flex;justify-content:space-between;align-items:center;font-size:11px;color:var(--fg-muted);margin-bottom:6px">
          <span>向量维度分布谱图（${dim} 维${dim > 120 ? ` · 采样展示 ${barCount} 点` : ''}）</span>
          <span>值域: [${min.toFixed(4)}, ${max.toFixed(4)}]</span>
        </div>
        <svg viewBox="0 0 ${svgWidth} ${svgHeight}" style="width:100%;height:90px;display:block">
          <line x1="10" y1="${zeroY}" x2="${svgWidth - 10}" y2="${zeroY}" stroke="var(--border)" stroke-dasharray="2 2" stroke-width="1"/>
          ${bars.join('')}
        </svg>
      </div>
    `;

    const vectorJson = JSON.stringify(vector);
    const displayJson = `[\n  ${vector.map((v) => (typeof v === 'number' ? v.toFixed(8) : v)).join(', ')}\n]`;

    const content = el(`
      <div>
        <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap;margin-bottom:12px">
          <span class="pill pill-accent">${dim} 维</span>
          <span class="pill pill-success">L2 范数: ${norm.toFixed(4)}</span>
          <span class="pill">均值: ${avg.toFixed(4)}</span>
          <span class="pill">Max: ${max.toFixed(4)}</span>
          <span class="pill">Min: ${min.toFixed(4)}</span>
        </div>
        ${svgChart}
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px">
          <span style="font-size:12px;font-weight:600;color:var(--fg)">向量数值</span>
          <button class="btn btn-sm" data-role="modal-copy">${icons.copy()} 复制完整向量</button>
        </div>
        <pre class="mono" style="margin:0;padding:10px;background:var(--bg-subtle);border:1px solid var(--border-muted);border-radius:var(--radius);max-height:180px;overflow-y:auto;font-size:11px;line-height:1.6;white-space:pre-wrap;word-break:break-all">${escapeHtml(displayJson)}</pre>
      </div>
    `);

    content.querySelector('[data-role=modal-copy]').addEventListener('click', async () => {
      await copyToClipboard(vectorJson, '完整向量已复制到剪贴板');
    });

    openModal({
      title: `向量详情 — ${truncate(docId, 40)}`,
      content,
    });
  }

  function showMetadataModal(docId, metadata) {
    const meta = metadata || {};
    const metaJson = prettyJson(meta);
    const count = Object.keys(meta).length;

    const content = el(`
      <div>
        <div style="display:flex;gap:6px;align-items:center;margin-bottom:12px">
          <span class="pill pill-accent">${count} 个字段</span>
          <div class="spacer" style="flex:1"></div>
          <button class="btn btn-sm" data-role="modal-copy">${icons.copy()} 复制 JSON</button>
        </div>
        <pre class="mono" style="margin:0;padding:12px;background:var(--bg-subtle);border:1px solid var(--border-muted);border-radius:var(--radius);max-height:280px;overflow-y:auto;font-size:12px;line-height:1.6;white-space:pre-wrap;word-break:break-all">${escapeHtml(metaJson)}</pre>
      </div>
    `);

    content.querySelector('[data-role=modal-copy]').addEventListener('click', async () => {
      await copyToClipboard(metaJson, 'Metadata 已复制到剪贴板');
    });

    openModal({
      title: `Metadata 详情 — ${truncate(docId, 40)}`,
      content,
    });
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

  function seedDocsDialog() {
    const content = el(`
      <div>
        <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:center;margin-bottom:14px">
          <label class="field" style="flex:1;min-width:140px;margin-bottom:0">
            <span>总条数</span>
            <input class="input input-full" data-role="total" type="number" value="10000" min="100" step="100" />
          </label>
          <label class="field" style="flex:1;min-width:140px;margin-bottom:0">
            <span>单批条数</span>
            <input class="input input-full" data-role="batch" type="number" value="500" min="50" step="50" />
          </label>
        </div>
        <div style="font-size:12px;color:var(--fg-muted);margin-bottom:14px;display:flex;gap:12px;align-items:center">
          <span>向量维度：<b>${stats.dimension} 维</b></span>
          ${stats.embeddingModel ? `<span>Embedding 模型：<b>${escapeHtml(stats.embeddingModel)}</b></span>` : '<span style="color:var(--danger)">未绑定 Embedding 模型</span>'}
        </div>
        <div style="display:flex;gap:8px">
          <button class="btn btn-primary" data-action="seed-text" ${!stats.embeddingModel ? 'disabled title="未绑定 Embedding 模型，无法生成文本向量"' : ''}>随机文本 → Embedding 入库</button>
          <button class="btn" data-action="seed-vector">随机 ${stats.dimension} 维向量入库</button>
        </div>
        <div data-role="progress" style="margin-top:14px;font-family:monospace;font-size:12px;color:var(--fg-muted);min-height:18px"></div>
      </div>
    `);

    const progress = content.querySelector('[data-role=progress]');
    const totalInput = content.querySelector('[data-role=total]');
    const batchInput = content.querySelector('[data-role=batch]');

    const log = (msg, isErr = false) => {
      progress.innerHTML = '';
      progress.appendChild(el(`<div style="color:${isErr ? 'var(--danger)' : 'var(--fg-muted)'}">${escapeHtml(msg)}</div>`));
    };

    /** Mulberry32：固定种子伪随机，保证可复现 */
    const rng = (seed) => () => {
      let t = (seed += 0x6D2B79F5);
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };

    const randomText = (rand, len = 50) => {
      const corpus = '你好世界VecLite向量检索相似度Embedding批量入库压测ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
      let s = '';
      for (let i = 0; i < len; i++) s += corpus[Math.floor(rand() * corpus.length)];
      return s;
    };

    const randomUnitVector = (rand, dim) => {
      const v = new Array(dim);
      for (let i = 0; i < dim; i++) {
        const u1 = Math.max(rand(), 1e-9);
        const u2 = rand();
        v[i] = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
      }
      let norm = 0;
      for (const x of v) norm += x * x;
      norm = Math.sqrt(norm) || 1;
      for (let i = 0; i < dim; i++) v[i] /= norm;
      return v;
    };

    const submitChunks = async (docs, batchSize, onProgress) => {
      let done = 0;
      for (let i = 0; i < docs.length; i += batchSize) {
        const chunk = docs.slice(i, i + batchSize);
        await api.upsertDocuments(storeName, chunk);
        done += chunk.length;
        onProgress(done, docs.length);
      }
      return done;
    };

    const runSeed = async (mode) => {
      const total = Math.max(100, parseInt(totalInput.value, 10) || 10000);
      const batchSize = Math.max(50, parseInt(batchInput.value, 10) || 500);
      const dim = stats.dimension;

      const buttons = content.querySelectorAll('button[data-action]');
      buttons.forEach((b) => { b.disabled = true; });
      const startTs = performance.now();
      log(`[start] mode=${mode}, store=${storeName}, dim=${dim}, total=${total}, batch=${batchSize}`);

      const batchTag = Date.now().toString(36);
      try {
        if (mode === 'text') {
          const rand = rng(20260831);
          const docs = [];
          for (let i = 0; i < total; i++) {
            docs.push({
              id: `seed_text_${batchTag}_${i.toString().padStart(6, '0')}`,
              text: randomText(rand, 50),
              metadata: { source: 'bulk-seed-text', idx: i, ts: Date.now() },
            });
          }
          log(`[gen] ${total} 条文本就绪，开始分批提交（${Math.ceil(total / batchSize)} 批）…`);
          await submitChunks(docs, batchSize, (done, totalAll) => {
            const elapsed = ((performance.now() - startTs) / 1000).toFixed(1);
            const qps = (done / Math.max(elapsed, 0.001)).toFixed(1);
            log(`[run] ${done}/${totalAll} | 耗时 ${elapsed}s | QPS ${qps}`);
          });
        } else {
          const rand = rng(20260831);
          const docs = [];
          for (let i = 0; i < total; i++) {
            docs.push({
              id: `seed_vec_${batchTag}_${i.toString().padStart(6, '0')}`,
              vector: randomUnitVector(rand, dim),
              metadata: { source: 'bulk-seed-vector', idx: i, ts: Date.now() },
            });
          }
          log(`[gen] ${total} 条向量就绪，开始分批提交（${Math.ceil(total / batchSize)} 批）…`);
          await submitChunks(docs, batchSize, (done, totalAll) => {
            const elapsed = ((performance.now() - startTs) / 1000).toFixed(1);
            const qps = (done / Math.max(elapsed, 0.001)).toFixed(1);
            log(`[run] ${done}/${totalAll} | 耗时 ${elapsed}s | QPS ${qps}`);
          });
        }
        const elapsed = ((performance.now() - startTs) / 1000).toFixed(1);
        log(`[done] ${total} 条全部入库，耗时 ${elapsed}s`);
        toast(`入库完成：${total} 条 / ${elapsed}s`);
        await draw();
      } catch (e) {
        log(`[fail] ${e.message}`, true);
        toast(e.message, true);
      } finally {
        buttons.forEach((b) => {
          if (b.dataset.action === 'seed-text' && !stats.embeddingModel) return;
          b.disabled = false;
        });
      }
    };

    content.querySelector('[data-action=seed-text]')?.addEventListener('click', () => runSeed('text'));
    content.querySelector('[data-action=seed-vector]')?.addEventListener('click', () => runSeed('vector'));

    openModal({
      title: `随机入库 — ${storeName}`,
      content,
      footer: [{ label: '关闭' }],
    });
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
          <div style="display:flex;align-items:center;gap:6px">
            <span class="mono" style="font-size:12px;color:var(--fg-muted)">得分计算:</span>
            <select class="select" data-role="score-expr-select" style="font-size:12px;padding:3px 8px;height:28px">
              <option value="none">不归一化 (原始得分)</option>
              <option value="score * 2.0 - 1.0">score * 2.0 - 1.0</option>
              <option value="(score + 1.0) / 2.0">ES 余弦归一化 ((score + 1) / 2)</option>
              <option value="custom">自定义表达式...</option>
            </select>
            <input class="input mono" data-role="custom-score-expr" placeholder="如 score * 100" style="display:none;width:140px;font-size:12px;padding:3px 8px;height:28px">
          </div>
          ${!stats.embeddingModel ? '<span class="pill pill-warn" title="未绑定 Embedding 模型，文本查询不可用">未绑定 Embedding 模型</span>' : ''}
        </div>

        <div data-role="query-area">
          <label class="field"><span>查询文本</span>
            <input class="input input-full" data-role="query-text" placeholder="例如：如何检索语义相似的内容">
          </label>
        </div>

        <div class="filter-builder" data-role="filter-builder">
          <div class="filter-bracket-wrap" data-role="bracket-wrap" style="display:none">
            <div class="bracket-line bracket-top"></div>
            <button type="button" class="bracket-btn" data-role="filter-combine-btn" title="点击切换条件组合 (AND / OR)">AND</button>
            <div class="bracket-line bracket-bottom"></div>
          </div>
          <div class="filter-rows" data-role="filters"></div>
        </div>
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
  let combineOperator = 'AND';
  const bracketWrap = conditionPanel.querySelector('[data-role=bracket-wrap]');
  const combineBtn = conditionPanel.querySelector('[data-role=filter-combine-btn]');

  function updateBracketVisibility() {
    const rowCount = filters.querySelectorAll('.filter-row').length;
    bracketWrap.style.display = rowCount >= 2 ? 'flex' : 'none';
  }

  if (combineBtn) {
    combineBtn.addEventListener('click', () => {
      combineOperator = combineOperator === 'AND' ? 'OR' : 'AND';
      combineBtn.textContent = combineOperator;
      combineBtn.classList.toggle('is-or', combineOperator === 'OR');
    });
  }

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

  function setupTagInput(row) {
    const tagWrap = row.querySelector('[data-f=tag-wrap]');
    const tagBadges = row.querySelector('.tag-badges');
    const tagInput = row.querySelector('.tag-inner-input');
    row._tags = [];

    function renderTags() {
      tagBadges.innerHTML = '';
      row._tags.forEach((tag, idx) => {
        const badge = el(`
          <span class="tag-badge" title="${escapeHtml(tag)}">
            <span class="tag-badge-text">${escapeHtml(tag)}</span>
            <button type="button" class="tag-badge-del" title="删除标签">&times;</button>
          </span>`);
        badge.querySelector('.tag-badge-del').addEventListener('click', (e) => {
          e.stopPropagation();
          row._tags.splice(idx, 1);
          renderTags();
        });
        tagBadges.appendChild(badge);
      });
    }

    tagWrap.addEventListener('click', () => tagInput.focus());

    tagInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        const val = tagInput.value.trim();
        if (val) {
          row._tags.push(val);
          tagInput.value = '';
          renderTags();
        }
      } else if (e.key === 'Backspace' && !tagInput.value && row._tags.length > 0) {
        row._tags.pop();
        renderTags();
      }
    });

    row._renderTags = renderTags;
  }

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
        <input class="input" data-f="value" placeholder="值" style="flex:1">
        <div class="tag-input-wrap" data-f="tag-wrap" style="display:none">
          <div class="tag-badges" style="display:inline-flex;flex-wrap:wrap;gap:4px"></div>
          <input class="tag-inner-input" placeholder="输入值后回车添加标签...">
        </div>
        <button class="btn btn-icon btn-danger" data-action="remove-row" title="移除">${icons.trash(14)}</button>
      </div>`);
    setupTagInput(row);
    row.querySelector('[data-action=remove-row]').addEventListener('click', () => {
      row.remove();
      updateBracketVisibility();
    });
    row.querySelector('[data-f=op]').addEventListener('change', (e) => onOpChange(row, e.target.value));
    filters.appendChild(row);
    updateBracketVisibility();
  });

  // 算子变更时调整输入展示（IN 切换为标签输入模式）
  function onOpChange(row, op) {
    const valueInput = row.querySelector('[data-f=value]');
    const tagWrap = row.querySelector('[data-f=tag-wrap]');
    const tagInput = row.querySelector('.tag-inner-input');

    if (op === 'IN') {
      valueInput.style.display = 'none';
      tagWrap.style.display = 'flex';
      const raw = valueInput.value.trim();
      if (raw && (!row._tags || row._tags.length === 0)) {
        row._tags = [raw];
        row._renderTags();
        valueInput.value = '';
      }
      tagInput.focus();
    } else {
      tagWrap.style.display = 'none';
      valueInput.style.display = 'block';
      if (row._tags && row._tags.length > 0 && !valueInput.value) {
        valueInput.value = row._tags[0];
      }
      if (op === 'GT' || op === 'LT') {
        valueInput.placeholder = '数值（如 0.5、100）';
        valueInput.type = 'number';
        valueInput.step = 'any';
      } else {
        valueInput.placeholder = '值';
        valueInput.type = 'text';
        valueInput.removeAttribute('step');
      }
    }
  }

  const exprSelect = conditionPanel.querySelector('[data-role=score-expr-select]');
  const customExprInput = conditionPanel.querySelector('[data-role=custom-score-expr]');
  if (exprSelect && customExprInput) {
    exprSelect.addEventListener('change', () => {
      if (exprSelect.value === 'custom') {
        customExprInput.style.display = 'inline-block';
        customExprInput.focus();
      } else {
        customExprInput.style.display = 'none';
      }
    });
  }

  conditionPanel.querySelector('[data-action=run-search]').addEventListener('click', async () => {
    const topK = Math.max(1, Number(container.querySelector('[data-role=topk]').value) || 5);
    const selVal = exprSelect ? exprSelect.value : 'none';
    let scoreExpression = null;
    if (selVal === 'custom') {
      scoreExpression = customExprInput.value.trim() || null;
    } else if (selVal !== 'none') {
      scoreExpression = selVal;
    }
    const normalizeScore = selVal === '(score + 1.0) / 2.0';
    const filter = buildFilter();
    let response;
    try {
      if (mode === 'text') {
        const text = queryArea.querySelector('[data-role=query-text]')?.value.trim();
        if (!text) { toast('请输入查询文本', true); return; }
        response = await api.searchText(storeName, { queryText: text, topK, filter, normalizeScore, scoreExpression });
      } else {
        let vector;
        try { vector = JSON.parse(queryArea.querySelector('[data-role=query-vector]').value); }
        catch { toast('查询向量须为合法 JSON 数组', true); return; }
        if (!Array.isArray(vector) || vector.length !== stats.dimension) {
          toast(`查询向量须为 ${stats.dimension} 维数组`, true);
          return;
        }
        response = await api.searchVector(storeName, { queryVector: vector, topK, filter, normalizeScore, scoreExpression });
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
    const children = [];
    for (const row of rows) {
      const op = row.querySelector('[data-f=op]').value;
      const field = row.querySelector('[data-f=field]').value.trim();
      if (!field) continue;
      if (op === 'IN') {
        const tagInput = row.querySelector('.tag-inner-input');
        const pending = tagInput ? tagInput.value.trim() : '';
        const tags = [...(row._tags || [])];
        if (pending) {
          tags.push(pending);
          if (row._tags) row._tags.push(pending);
          tagInput.value = '';
          if (row._renderTags) row._renderTags();
        }
        if (tags.length === 0) {
          toast(`字段「${field}」属于(IN)过滤未添加任何值标签`, true);
          return null;
        }
        const values = tags.map(coerceEq);
        children.push({ field, operator: 'IN', values });
      } else if (op === 'GT' || op === 'LT') {
        const raw = row.querySelector('[data-f=value]').value.trim();
        const num = Number(raw);
        if (raw === '' || Number.isNaN(num)) {
          toast('大于/小于需要数值类型', true);
          return null;
        }
        children.push({ field, operator: op, value: num });
      } else {
        const raw = row.querySelector('[data-f=value]').value.trim();
        children.push({ field, operator: 'EQ', value: coerceEq(raw) });
      }
    }
    if (children.length === 0) return null;
    if (children.length === 1) return children[0];
    return { operator: combineOperator, children };
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
          ${Array.isArray(r.vector) ? `<details><summary>向量（${r.vector.length} 维）</summary><pre>${escapeHtml(prettyJson(r.vector.map((v) => Number(v.toFixed(8)))))}</pre></details>` : ''}
        </div>`);
      list.appendChild(card);
    }
    results.appendChild(panel);
  }
}
