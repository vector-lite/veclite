/** 简洁线性 SVG 图标与空态插画（替代旧版的重插画风格） */

export const icons = {
  store: (size = 16) => `
    <svg class="store-icon" viewBox="0 0 16 16" width="${size}" height="${size}" fill="currentColor" aria-hidden="true">
      <path d="M1.75 1h8.5c.966 0 1.75.784 1.75 1.75v5.5A1.75 1.75 0 0 1 10.25 10H8.06l1.72 1.72a.75.75 0 1 1-1.06 1.06L5.72 9.78a.75.75 0 0 1 0-1.06l3-3a.75.75 0 1 1 1.06 1.06L8.06 8.5h2.19a.25.25 0 0 0 .25-.25v-5.5a.25.25 0 0 0-.25-.25h-8.5a.25.25 0 0 0-.25.25v5.5c0 .138.112.25.25.25H4a.75.75 0 0 1 0 1.5H1.75A1.75 1.75 0 0 1 0 8.25v-5.5C0 1.784.784 1 1.75 1ZM4.5 13.5a1 1 0 1 1-2 0 1 1 0 0 1 2 0Z"/>
    </svg>`,
  database: (size = 20) => `
    <svg viewBox="0 0 16 16" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.3" aria-hidden="true">
      <ellipse cx="8" cy="3.5" rx="5.5" ry="2.5"/>
      <path d="M2.5 3.5v9c0 1.38 2.46 2.5 5.5 2.5s5.5-1.12 5.5-2.5v-9"/>
      <path d="M2.5 8c0 1.38 2.46 2.5 5.5 2.5S13.5 9.38 13.5 8"/>
    </svg>`,
  search: (size = 20) => `
    <svg viewBox="0 0 16 16" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.4" aria-hidden="true">
      <circle cx="7" cy="7" r="4.6"/><path d="m10.5 10.5 3.5 3.5" stroke-linecap="round"/>
    </svg>`,
  refresh: (size = 16) => `
    <svg viewBox="0 0 16 16" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" aria-hidden="true">
      <path d="M2.5 8a5.5 5.5 0 0 1 9.6-3.64L13.5 5.7M13.5 2.5v3.2h-3.2M13.5 8a5.5 5.5 0 0 1-9.6 3.64L2.5 10.3M2.5 13.5v-3.2h3.2"/>
    </svg>`,
  trash: (size = 15) => `
    <svg viewBox="0 0 16 16" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" aria-hidden="true">
      <path d="M2.5 4h11M5.5 4V2.8c0-.44.36-.8.8-.8h3.4c.44 0 .8.36.8.8V4M4 4l.6 9.2c.04.62.55 1.1 1.17 1.1h4.46c.62 0 1.13-.48 1.17-1.1L12 4"/>
    </svg>`,
  plus: (size = 15) => `
    <svg viewBox="0 0 16 16" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" aria-hidden="true">
      <path d="M8 3v10M3 8h10"/>
    </svg>`,
  download: (size = 15) => `
    <svg viewBox="0 0 16 16" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" aria-hidden="true">
      <path d="M8 2v8m0 0 3-3M8 10 5 7M2.5 13.5h11"/>
    </svg>`,
  upload: (size = 15) => `
    <svg viewBox="0 0 16 16" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" aria-hidden="true">
      <path d="M8 10V2m0 0L5 5m3-3 3 3M2.5 13.5h11"/>
    </svg>`,
  grid: (size = 15) => `
    <svg viewBox="0 0 16 16" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.4" aria-hidden="true">
      <rect x="2" y="2" width="5" height="5" rx="1"/><rect x="9.5" y="2" width="5" height="5" rx="1"/>
      <rect x="2" y="9.5" width="5" height="5" rx="1"/><rect x="9.5" y="9.5" width="5" height="5" rx="1"/>
    </svg>`,
  list: (size = 15) => `
    <svg viewBox="0 0 16 16" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" aria-hidden="true">
      <path d="M5.5 4h9M5.5 8h9M5.5 12h9"/><circle cx="2.2" cy="4" r=".9" fill="currentColor" stroke="none"/><circle cx="2.2" cy="8" r=".9" fill="currentColor" stroke="none"/><circle cx="2.2" cy="12" r=".9" fill="currentColor" stroke="none"/>
    </svg>`,
  table: (size = 15) => `
    <svg viewBox="0 0 16 16" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.3" aria-hidden="true">
      <rect x="2" y="2.5" width="12" height="11" rx="1.2"/><path d="M2 6h12M2 9.7h12M6.5 6v7.5M10.8 6v7.5"/>
    </svg>`,
};

/** 空态插画：简单、留白、线性 */
export const emptyStates = {
  stores: () => `
    <div class="empty">
      ${icons.database(44)}
      <div class="empty-title">还没有向量库</div>
      <button class="btn btn-primary" data-action="create-store">新建向量库</button>
    </div>`,
  storesFiltered: () => `
    <div class="empty">
      ${icons.search(40)}
      <div class="empty-title">没有匹配的向量库</div>
      <div class="empty-hint">换个关键词试试</div>
    </div>`,
  documents: () => `
    <div class="empty">
      ${icons.database(40)}
      <div class="empty-title">暂无文档</div>
    </div>`,
  search: () => `
    <div class="empty">
      ${icons.search(40)}

    </div>`,
  searchEmpty: () => `
    <div class="empty">
      ${icons.search(40)}
      <div class="empty-title">无匹配结果</div>
    </div>`,
  models: () => `
    <div class="empty">
      ${icons.database(40)}
      <div class="empty-title">未配置 Embedding 数据源</div>
    </div>`,
};
