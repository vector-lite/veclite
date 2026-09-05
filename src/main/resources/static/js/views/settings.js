import { api } from '../api.js';
import { el, toast, formModal, confirmModal, openModal, escapeHtml, truncate, copyToClipboard } from '../ui.js';
import { icons, emptyStates } from '../icons.js';

/** provider 协议选项，与后端 EmbeddingModelRegistry.SUPPORTED_PROVIDERS 一致 */
const PROVIDER_OPTIONS = [
  { value: 'http', label: 'http' },
  { value: 'openai', label: 'openai' },
  { value: 'ollama', label: 'ollama' },
  { value: 'ollama-embed', label: 'ollama-embed' },
];

export async function renderSettings(container) {
  container.innerHTML = '';

  container.appendChild(el(`
    <div class="page-header">
      <h1 class="page-title">数据源管理</h1>
    </div>
  `));

  const card = el(`
    <div class="panel">
      <div class="panel-header">
        Embedding 数据源 <span class="counter" data-role="count">0</span>
        <button class="btn btn-primary" data-action="add-model" style="margin-left:auto">${icons.plus()} 新增数据源</button>
      </div>
      <div data-role="body" style="min-height:80px"></div>
    </div>
  `);
  container.appendChild(card);


  container.querySelector('button[data-action=add-model]').addEventListener('click', () => modelDialog());

  await draw();

  async function draw() {
    const body = card.querySelector('[data-role=body]');
    let models = [];
    try {
      models = await api.embeddingModels();
    } catch (e) {
      body.innerHTML = '';
      body.appendChild(el(`<div class="empty" style="padding:28px">
        <div class="empty-hint">加载失败：${escapeHtml(e.message)}</div>
      </div>`));
      return;
    }

    card.querySelector('[data-role=count]').textContent = models.length;
    if (models.length === 0) {
      body.innerHTML = '';
      body.appendChild(el(emptyStates.models()));
      return;
    }

    body.innerHTML = `
      <div class="table-wrap">
        <table class="table">
          <thead><tr><th>模型</th><th>版本</th><th>Provider</th><th>URL</th><th>鉴权</th><th>维度</th><th>超时</th><th>批量</th><th style="width:170px"></th></tr></thead>
          <tbody>
            ${models.map((m) => `
              <tr>
                <td style="font-weight:600">${escapeHtml(m.name)}
                  ${m.defaultModel ? '<span class="pill pill-accent">默认</span>' : ''}
                </td>
                <td class="mono">${escapeHtml(m.version || '-')}</td>
                <td><span class="pill">${escapeHtml(m.provider || '-')}</span></td>
                <td class="mono cell-muted cell-truncate" style="max-width:260px" title="${escapeHtml(m.url || '')}">${escapeHtml(truncate(m.url || '-', 40))}</td>
                <td class="mono cell-muted">${m.apiKey ? '<span class="pill">已配置</span>' : '—'}</td>
                <td class="mono cell-muted">${m.dimension > 0 ? m.dimension : '自动'}</td>
                <td class="mono cell-muted">${m.timeoutMillis}ms</td>
                <td class="mono cell-muted">${m.batchSize}</td>
                <td style="text-align:right;white-space:nowrap">
                  <button class="btn btn-sm" data-embed="${escapeHtml(m.name)}" data-version="${escapeHtml(m.version || '')}">试算</button>
                  <button class="btn btn-sm" data-edit="${escapeHtml(m.name)}" data-version="${escapeHtml(m.version || '')}">编辑</button>
                  <button class="btn btn-sm btn-danger" data-delete="${escapeHtml(m.name)}" data-version="${escapeHtml(m.version || '')}">删除</button>
                </td>
              </tr>`).join('')}
          </tbody>
        </table>
      </div>`;

    body.querySelectorAll('[data-embed]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const model = models.find((m) => m.name === btn.dataset.embed && (m.version || '') === (btn.dataset.version || ''));
        if (model) embedDialog(model);
      });
    });
    body.querySelectorAll('[data-edit]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const model = models.find((m) => m.name === btn.dataset.edit && (m.version || '') === (btn.dataset.version || ''));
        if (model) modelDialog(model);
      });
    });
    body.querySelectorAll('[data-delete]').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const name = btn.dataset.delete;
        const version = btn.dataset.version;
        if (!(await confirmModal(`确定删除数据源「${name} v${version}」？已绑定该模型的向量库将无法再进行文本向量化。`, { danger: true, okLabel: '删除' }))) return;
        try {
          await api.deleteEmbeddingModel(name, version);
          toast('数据源已删除');
          draw();
        } catch (e) { toast(e.message, true); }
      });
    });
  }

  /**
   * 向量化试算弹窗：与指定数据源（名称+版本）绑定，输入文本返回向量。
   * 结果以卡片展示（维度 / 耗时徽标 + 等宽向量内容 + 展开与复制），Ctrl+Enter 快捷触发。
   */
  function embedDialog(model) {
    const content = el(`
      <div>
        <label class="field">
          <span>输入文本</span>
          <textarea class="input input-full" rows="4" data-role="text" placeholder="输入需要向量化的文本，例如：今天天气不错"></textarea>
        </label>
        <div style="display:flex;align-items:center;gap:12px;margin:14px 0 2px">
          <button class="btn btn-primary" data-role="run">生成向量</button>
          <span class="cell-muted" style="font-size:12px" data-role="meta"></span>
        </div>
        <div data-role="result" style="margin-top:14px"></div>
      </div>
    `);

    const textInput = content.querySelector('[data-role=text]');
    const runBtn = content.querySelector('[data-role=run]');
    const meta = content.querySelector('[data-role=meta]');
    const result = content.querySelector('[data-role=result]');

    let lastVector = null;
    let lastElapsed = 0;

    const renderVector = (vector, expanded) => {
      result.innerHTML = '';
      if (!vector.length) {
        result.appendChild(el('<div class="cell-muted" style="font-size:12px">服务端返回空向量</div>'));
        return;
      }
      const previewLimit = expanded ? vector.length : Math.min(8, vector.length);
      const preview = vector.slice(0, previewLimit)
        .map((v) => (typeof v === 'number' ? Number(v.toFixed(8)) : v)).join(', ');
      const box = el(`
        <div style="border:1px solid var(--border-muted);border-radius:6px;background:var(--bg-subtle);padding:12px 14px">
          <div style="display:flex;gap:6px;margin-bottom:10px">
            <span class="pill pill-success">维度 ${vector.length}</span>
            <span class="pill">耗时 ${lastElapsed}ms</span>
          </div>
          <div class="mono" style="color:var(--fg);word-break:break-all;line-height:1.7;white-space:pre-wrap;${expanded ? 'max-height:240px;overflow-y:auto;' : ''}">[${escapeHtml(preview)}${vector.length > previewLimit ? ', …' : ''}]</div>
          <div style="display:flex;gap:8px;margin-top:12px">
            ${vector.length > 8 ? `<button class="btn btn-sm" data-role="toggle">${expanded ? '收起' : `展开全部 ${vector.length} 维`}</button>` : ''}
            <button class="btn btn-sm" data-role="copy">复制 JSON</button>
          </div>
        </div>`);
      const toggle = box.querySelector('[data-role=toggle]');
      if (toggle) toggle.addEventListener('click', () => renderVector(lastVector, !expanded));
      box.querySelector('[data-role=copy]').addEventListener('click', async () => {
        await copyToClipboard(JSON.stringify(lastVector), '向量已复制到剪贴板');
      });
      result.appendChild(box);
    };

    const run = async () => {
      const text = textInput.value.trim();
      if (!text) { toast('请输入文本', true); textInput.focus(); return; }
      runBtn.disabled = true;
      meta.textContent = '向量化中…';
      const startTs = performance.now();
      try {
        const res = await api.embedText(model.name, model.version, text);
        lastVector = Array.isArray(res.vector) ? res.vector : [];
        lastElapsed = Math.max(1, Math.round(performance.now() - startTs));
        meta.textContent = '';
        renderVector(lastVector, false);
      } catch (e) {
        meta.textContent = '';
        result.innerHTML = '';
        result.appendChild(el(`<div style="font-size:12px;color:var(--danger);padding:10px 12px;border:1px solid rgba(207,34,46,.3);border-radius:6px;background:rgba(207,34,46,.04)">${escapeHtml(e.message)}</div>`));
      } finally {
        runBtn.disabled = false;
      }
    };

    runBtn.addEventListener('click', run);
    textInput.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') run();
    });

    openModal({
      title: `向量化试算 — ${model.name}${model.version ? ` v${model.version}` : ''}`,
      content,
      footer: [{ label: '关闭' }],
    });
  }

  /** 新增 / 编辑数据源弹窗；model 为空表示新增 */
  async function modelDialog(model = null) {
    const isEdit = model != null;
    const buildPayload = (values) => ({
      name: values.name,
      provider: values.provider,
      url: values.url,
      version: values.version || '1',
      apiKey: values.apiKey || '',
      dimension: Number(values.dimension) || 0,
      timeoutMillis: Number(values.timeoutMillis) || 3000,
      batchSize: Number(values.batchSize) || 1,
    });

    const values = await formModal({
      title: isEdit ? '编辑数据源' : '新增数据源',
      okLabel: isEdit ? '保存' : '新增',
      fields: [
        { name: 'provider', label: 'Provider 协议', type: 'select', options: PROVIDER_OPTIONS, value: model?.provider || 'http' },
        { name: 'url', label: '服务地址', value: model?.url || '', placeholder: 'http://localhost:11434/api/embeddings' },
        { name: 'name', label: '模型名称', value: model?.name || '', disabled: isEdit },
        { name: 'version', label: '模型版本', value: model?.version || '1', disabled: isEdit },
        { name: 'apiKey', label: 'API Key（可选）', value: model?.apiKey || '', placeholder: '留空表示无需鉴权' },
        { name: 'dimension', label: '输出维度', type: 'number', value: String(model?.dimension ?? 0), placeholder: '0 = 由服务端决定' },
        { name: 'timeoutMillis', label: '超时（毫秒）', type: 'number', value: String(model?.timeoutMillis || 3000) },
        { name: 'batchSize', label: '批量大小', type: 'number', value: String(model?.batchSize ?? 1) },
      ],
      secondary: {
        label: '置为默认',
        onClick: async (v) => {
          try {
            await api.saveEmbeddingModel(buildPayload(v));
            await api.setDefaultEmbeddingModel(v.name.trim(), v.version);
            toast(`已将「${v.name.trim()} v${v.version}」置为默认`);
            draw();
          } catch (e) { toast(e.message, true); }
        },
      },
    });
    if (!values) return;

    try {
      await api.saveEmbeddingModel(buildPayload(values));
      toast(isEdit ? '数据源已保存' : '数据源已新增');
      draw();
    } catch (e) { toast(e.message, true); }
  }
}

