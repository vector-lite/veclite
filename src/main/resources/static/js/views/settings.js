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

  container.appendChild(bulkSeedPanel());

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

/**
 * 批量入库压测面板：1w 条随机数据灌入指定 store。
 * - 按钮 1（文本输入）：随机生成 1w 条文本，走后端自动 Embedding
 * - 按钮 2（向量输入）：随机生成 1w 条 1024 维 float32 向量，L2 normalize 后入库
 * 后端走 /veclite/api/v1/stores/{name}/documents/batch，单次 500 条分批。
 */
function bulkSeedPanel() {
  const root = el(`
    <div class="panel" style="margin-top:16px">
      <div class="panel-header">批量入库压测 <span class="cell-muted" style="font-weight:400;font-size:12px;margin-left:8px">1w 条随机数据 → store_bulk_test</span></div>
      <div data-role="body" style="padding:16px">
        <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:center;margin-bottom:12px">
          <label class="cell-muted" style="font-size:13px">目标 store：
            <input class="input" data-role="store" value="store_bulk_test" style="width:200px;margin-left:6px" />
          </label>
          <label class="cell-muted" style="font-size:13px">总条数：
            <input class="input" data-role="total" type="number" value="10000" min="100" step="100" style="width:120px;margin-left:6px" />
          </label>
          <label class="cell-muted" style="font-size:13px">单批条数：
            <input class="input" data-role="batch" type="number" value="500" min="50" step="50" style="width:100px;margin-left:6px" />
          </label>
        </div>
        <div style="display:flex;gap:8px">
          <button class="btn btn-primary" data-action="seed-text">随机文本 → Embedding 入库</button>
          <button class="btn" data-action="seed-vector">随机 1024 维向量入库</button>
        </div>
        <div data-role="progress" style="margin-top:14px;font-family:monospace;font-size:12px;color:#475569;min-height:18px"></div>
      </div>
    </div>
  `);

  const progress = root.querySelector('[data-role=progress]');
  const storeInput = root.querySelector('[data-role=store]');
  const totalInput = root.querySelector('[data-role=total]');
  const batchInput = root.querySelector('[data-role=batch]');

  const log = (msg, isErr = false) => {
    progress.innerHTML = '';
    progress.appendChild(el(`<div style="color:${isErr ? '#dc2626' : '#475569'}">${escapeHtml(msg)}</div>`));
  };

  const ensureStore = async (storeName, dimension) => {
    // 必须绑定 embedding model，后端 autoEmbed 才会跑
    let embeddingModel = null;
    let embeddingModelVersion = null;
    try {
      const models = await api.embeddingModels();
      const def = models.find((m) => m.defaultModel) || models[0];
      if (def) {
        embeddingModel = def.name;
        embeddingModelVersion = def.version;
      }
    } catch (e) {
      // 拿不到就继续尝试，createStore 会自己报错
    }
    const def = {
      storeName,
      dimension,
      metric: 'COSINE',
      maxCapacity: 200000,
      quantization: 'NONE',
      indexedMetadataFields: [],
      embeddingModel: embeddingModel || undefined,
      embeddingModelVersion: embeddingModelVersion || undefined,
    };
    try {
      await api.createStore(storeName, def);
      log(`[OK] store「${storeName}」已创建（dim=${dimension}, model=${embeddingModel || '(none)'}）`);
    } catch (e) {
      // 可能是已存在 / 也可能是 binding 不对。先 drop 重建一次确保干净
      log(`[retry] store「${storeName}」首次创建失败（${e.message}），尝试 drop 后重建…`);
      try {
        await api.dropStore(storeName);
        await api.createStore(storeName, def);
        log(`[OK] store「${storeName}」重建完成（dim=${dimension}, model=${embeddingModel || '(none)'}）`);
      } catch (e2) {
        log(`[fail] store「${storeName}」重建失败：${e2.message}`, true);
        throw e2;
      }
    }
  };

  /** Mulberry32：固定种子伪随机，保证可复现 */
  const rng = (seed) => () => {
    let t = (seed += 0x6D2B79F5);
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };

  const randomText = (rand, len = 50) => {
    // 中英混合字表，避免空字符串 / 纯空白
    const corpus = '你好世界VecLite向量检索相似度Embedding批量入库压测ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    let s = '';
    for (let i = 0; i < len; i++) s += corpus[Math.floor(rand() * corpus.length)];
    return s;
  };

  const randomUnitVector = (rand, dim) => {
    // Box-Muller 高斯 + L2 normalize；为 cosine 相似度提供合理分布
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

  const submitChunks = async (storeName, docs, batchSize, onProgress) => {
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
    const storeName = storeInput.value.trim() || 'store_bulk_test';
    const total = Math.max(100, parseInt(totalInput.value, 10) || 10000);
    const batchSize = Math.max(50, parseInt(batchInput.value, 10) || 500);

    const buttons = root.querySelectorAll('button[data-action]');
    buttons.forEach((b) => { b.disabled = true; });
    const startTs = performance.now();
    log(`[start] mode=${mode}, store=${storeName}, total=${total}, batch=${batchSize}`);

    try {
      if (mode === 'text') {
        await ensureStore(storeName, 1024);
        const rand = rng(20260831);
        const docs = [];
        for (let i = 0; i < total; i++) {
          docs.push({
            id: `seed_text_${i.toString().padStart(6, '0')}`,
            text: randomText(rand, 50),
            metadata: { source: 'bulk-seed-text', idx: i, ts: Date.now() },
          });
        }
        log(`[gen] ${total} 条文本就绪，开始分批提交（${Math.ceil(total / batchSize)} 批）…`);
        await submitChunks(storeName, docs, batchSize, (done, totalAll) => {
          const elapsed = ((performance.now() - startTs) / 1000).toFixed(1);
          const qps = (done / Math.max(elapsed, 0.001)).toFixed(1);
          log(`[run] ${done}/${totalAll} | 耗时 ${elapsed}s | QPS ${qps}`);
        });
      } else {
        await ensureStore(storeName, 1024);
        const rand = rng(20260831);
        const docs = [];
        for (let i = 0; i < total; i++) {
          docs.push({
            id: `seed_vec_${i.toString().padStart(6, '0')}`,
            vector: randomUnitVector(rand, 1024),
            metadata: { source: 'bulk-seed-vector', idx: i, ts: Date.now() },
          });
        }
        log(`[gen] ${total} 条向量就绪，开始分批提交（${Math.ceil(total / batchSize)} 批）…`);
        await submitChunks(storeName, docs, batchSize, (done, totalAll) => {
          const elapsed = ((performance.now() - startTs) / 1000).toFixed(1);
          const qps = (done / Math.max(elapsed, 0.001)).toFixed(1);
          log(`[run] ${done}/${totalAll} | 耗时 ${elapsed}s | QPS ${qps}`);
        });
      }
      const elapsed = ((performance.now() - startTs) / 1000).toFixed(1);
      log(`[done] ${total} 条全部入库，耗时 ${elapsed}s`);
      toast(`压测完成：${total} 条 / ${elapsed}s`);
    } catch (e) {
      log(`[fail] ${e.message}`, true);
      toast(e.message, true);
    } finally {
      buttons.forEach((b) => { b.disabled = false; });
    }
  };

  root.querySelector('[data-action=seed-text]').addEventListener('click', () => runSeed('text'));
  root.querySelector('[data-action=seed-vector]').addEventListener('click', () => runSeed('vector'));
  return root;
}
