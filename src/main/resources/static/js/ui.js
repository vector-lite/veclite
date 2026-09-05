/** DOM 构建辅助、弹窗与 Toast */

export function el(html) {
  const template = document.createElement('template');
  template.innerHTML = html.trim();
  return template.content.firstElementChild;
}

export function toast(message, isError = false) {
  const node = document.getElementById('toast');
  node.textContent = message;
  node.className = 'toast' + (isError ? ' toast-error' : '');
  node.hidden = false;
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => { node.hidden = true; }, 2600);
}

const modal = () => document.getElementById('modal');

/**
 * 打开内容弹窗。content 为 DOM 节点；footer 按钮回调后自动关闭（返回 false 除外）。
 */
export function openModal({ title, content, footer = [], closeOnBackdrop = true }) {
  const dialog = modal();
  dialog.innerHTML = '';
  if (title) {
    const header = el(`
      <div class="modal-header" style="display:flex;align-items:center;justify-content:space-between">
        <span style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis">${escapeHtml(title)}</span>
        <button class="btn btn-icon" data-modal-close style="margin:-4px -8px -4px 8px;padding:2px 6px;color:var(--fg-muted)" title="关闭">✕</button>
      </div>`);
    header.querySelector('[data-modal-close]').addEventListener('click', () => dialog.close());
    dialog.appendChild(header);
  }
  const body = el('<div class="modal-body"></div>');
  body.appendChild(content);
  dialog.appendChild(body);

  if (footer.length) {
    const bar = el('<div class="modal-footer"></div>');
    for (const { label, kind = '', onClick } of footer) {
      const btn = el(`<button class="btn ${kind}">${escapeHtml(label)}</button>`);
      btn.addEventListener('click', () => {
        if (onClick?.() !== false) dialog.close();
      });
      bar.appendChild(btn);
    }
    dialog.appendChild(bar);
  }

  dialog.onclick = (event) => {
    if (closeOnBackdrop && event.target === dialog) {
      const rect = dialog.getBoundingClientRect();
      const isInside = (
        rect.top <= event.clientY &&
        event.clientY <= rect.bottom &&
        rect.left <= event.clientX &&
        event.clientX <= rect.right
      );
      if (!isInside) dialog.close();
    }
  };

  dialog.returnValue = '';
  dialog.showModal();
  return dialog;
}

export function confirmModal(message, { danger = false, okLabel = '确认' } = {}) {
  return new Promise((resolve) => {
    openModal({
      title: '操作确认',
      content: el(`<p class="modal-msg">${escapeHtml(message)}</p>`),
      footer: [
        { label: '取消', onClick: () => resolve(false) },
        { label: okLabel, kind: danger ? 'btn-danger' : 'btn-primary', onClick: () => resolve(true) },
      ],
    }).addEventListener('close', () => resolve(false));
  });
}

export function closeModal() { modal().close(); }

/** 表单弹窗：fields 为 [{name,label,type,placeholder,value,hint,mono,options,disabled}]，type 支持 text/textarea/select；
 *  secondary 为可选的次操作按钮 {label, onClick}（位于取消与主按钮之间） */
export function formModal({ title, fields, okLabel = '提交', secondary = null }) {
  const form = el('<form method="dialog" style="margin:0"></form>');
  for (const f of fields) {
    let control;
    if (f.type === 'textarea') {
      control = `<textarea class="input input-full ${f.mono ? 'mono' : ''}" rows="${f.rows || 3}" name="${f.name}" placeholder="${f.placeholder || ''}">${escapeHtml(f.value || '')}</textarea>`;
    } else if (f.type === 'select') {
      const options = (f.options || []).map((o) =>
        `<option value="${escapeHtml(o.value)}" ${o.value === (f.value ?? '') ? 'selected' : ''}>${escapeHtml(o.label)}</option>`).join('');
      control = `<select class="select input-full" name="${f.name}">${options}</select>`;
    } else {
      control = `<input class="input input-full ${f.mono ? 'mono' : ''}" name="${f.name}" placeholder="${f.placeholder || ''}" value="${escapeHtml(f.value || '')}" ${f.type === 'number' ? 'type="number"' : ''} ${f.disabled ? 'disabled' : ''}>`;
    }
    form.appendChild(el(`<label class="field"><span>${f.label}${f.hint ? ` <i class="hint">${f.hint}</i>` : ''}</span>${control}</label>`));
  }

  return new Promise((resolve) => {
    let settled = false;
    const dialog = openModal({
      title,
      content: form,
      footer: [
        { label: '取消', onClick: () => { settled = true; resolve(null); } },
        ...(secondary ? [{
          label: secondary.label,
          onClick: () => {
            if (!form.reportValidity()) return false;
            const values = {};
            for (const f of fields) values[f.name] = form.elements[f.name].value.trim();
            secondary.onClick(values);
          },
        }] : []),
        {
          label: okLabel,
          kind: 'btn-primary',
          onClick: () => {
            if (!form.reportValidity()) return false;
            settled = true;
            const values = {};
            for (const f of fields) values[f.name] = form.elements[f.name].value.trim();
            resolve(values);
          },
        },
      ],
    });
    dialog.addEventListener('close', () => { if (!settled) resolve(null); });
  });
}

export function escapeHtml(text) {
  return String(text ?? '')
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&#39;');
}

export function truncate(text, max = 120) {
  const s = String(text ?? '');
  return s.length > max ? s.slice(0, max) + '…' : s;
}

export function prettyJson(value) {
  return JSON.stringify(value, null, 2);
}

export async function copyToClipboard(text, successMsg = '已复制到剪贴板') {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      toast(successMsg);
      return true;
    }
  } catch { /* fallback */ }

  try {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    textarea.style.left = '-9999px';
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(textarea);
    if (ok) {
      toast(successMsg);
      return true;
    }
  } catch { /* ignore */ }

  toast('复制失败，请手动选择复制', true);
  return false;
}
