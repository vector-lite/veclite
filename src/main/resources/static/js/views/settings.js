import { api } from '../api.js';
import { el, toast, formModal, confirmModal, escapeHtml, truncate } from '../ui.js';
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
      <div>
        <h1 class="page-title">数据源管理</h1>
      </div>
      <button class="btn btn-primary" data-action="add-model">${icons.plus()} 新增数据源</button>
    </div>
  `));

  const card = el(`
    <div class="panel">
      <div class="panel-header">Embedding 数据源 <span class="counter" data-role="count">0</span></div>
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
          <thead><tr><th>模型</th><th>版本</th><th>Provider</th><th>URL</th><th>鉴权</th><th>维度</th><th>超时</th><th>批量</th><th style="width:110px"></th></tr></thead>
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
                  <button class="btn btn-sm" data-edit="${escapeHtml(m.name)}" data-version="${escapeHtml(m.version || '')}">编辑</button>
                  <button class="btn btn-sm btn-danger" data-delete="${escapeHtml(m.name)}" data-version="${escapeHtml(m.version || '')}">删除</button>
                </td>
              </tr>`).join('')}
          </tbody>
        </table>
      </div>`;

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
