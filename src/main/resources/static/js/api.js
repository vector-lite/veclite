const BASE = '/veclite/api/v1';

async function request(path, options = {}) {
  const resp = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!resp.ok) {
    let message = `HTTP ${resp.status}`;
    try {
      const body = await resp.json();
      message = body.message || body.error || message;
    } catch { /* 非 JSON 错误体 */ }
    throw new Error(message);
  }
  if (resp.status === 204) return null;
  const text = await resp.text();
  return text ? JSON.parse(text) : null;
}

const post = (path, body) => request(path, { method: 'POST', body: body != null ? JSON.stringify(body) : '{}' });
const del = (path, body) => request(path, { method: 'DELETE', body: body != null ? JSON.stringify(body) : undefined });

export const api = {
  listStores: () => request('/stores'),
  createStore: (name, definition) => post(`/stores/${encodeURIComponent(name)}`, definition),
  dropStore: (name) => del(`/stores/${encodeURIComponent(name)}`),
  stats: (name) => request(`/stores/${encodeURIComponent(name)}/stats`),
  listDocuments: (name, page, size) =>
    request(`/stores/${encodeURIComponent(name)}/documents?page=${page}&size=${size}`),
  getDocument: (name, id) => request(`/stores/${encodeURIComponent(name)}/documents/${encodeURIComponent(id)}`),
  upsertDocument: (name, doc) => post(`/stores/${encodeURIComponent(name)}/documents`, doc),
  upsertDocuments: (name, docs) => post(`/stores/${encodeURIComponent(name)}/documents/batch`, docs),
  deleteDocuments: (name, ids) => del(`/stores/${encodeURIComponent(name)}/documents`, ids),
  searchVector: (name, body) => post(`/stores/${encodeURIComponent(name)}/search/vector`, body),
  searchText: (name, body) => post(`/stores/${encodeURIComponent(name)}/search/text`, body),
  reload: (name) => post(`/stores/${encodeURIComponent(name)}/reload`),
  refresh: (name) => post(`/stores/${encodeURIComponent(name)}/refresh`),
  embeddingModels: () => request('/embedding/models'),
  saveEmbeddingModel: (model) => post('/embedding/models', model),
  deleteEmbeddingModel: (name, version) =>
    del(`/embedding/models/${encodeURIComponent(name)}?version=${encodeURIComponent(version || '')}`),
  setDefaultEmbeddingModel: (name, version) =>
    post(`/embedding/models/${encodeURIComponent(name)}/default?version=${encodeURIComponent(version || '')}`),
};
