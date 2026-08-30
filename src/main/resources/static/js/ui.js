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
export function openModal({ title, content, footer = [] }) {
  const dialog = modal();
  dialog.innerHTML = '';
  if (title) {
    dialog.appendChild(el(`<div class="modal-header">${escapeHtml(title)}</div>`));
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
