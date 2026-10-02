(() => {
  'use strict';

  const $ = (q, root = document) => root.querySelector(q);
  const $$ = (q, root = document) => [...root.querySelectorAll(q)];
  const state = {
    token: localStorage.getItem('magiskWebUiToken') || '',
    publicConfig: null,
    webuiConfig: null,
    settings: null,
    logKind: 'su',
    page: 'dashboard',
  };

  const titles = {
    dashboard: ['概览', '设备与 Magisk 状态'],
    modules: ['模块', '管理已安装的 Magisk 模块'],
    superuser: ['超级用户', '管理 MagiskSU 授权策略'],
    logs: ['日志', 'SU 与 Magisk 运行日志'],
    settings: ['设置', 'Magisk 核心配置'],
    webui: ['WebUI', '认证、Token 与主题'],
    power: ['电源', '远程重启设备'],
  };

  function esc(value) {
    return String(value ?? '')
      .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;').replaceAll("'", '&#039;');
  }

  function boolText(value) { return value ? '<span class="good">已启用</span>' : '<span class="bad">未启用</span>'; }

  function takeFragmentToken() {
    const hash = location.hash.startsWith('#') ? location.hash.slice(1) : location.hash;
    const params = new URLSearchParams(hash);
    const token = params.get('token');
    if (token) {
      state.token = token;
      localStorage.setItem('magiskWebUiToken', token);
      history.replaceState(null, '', location.pathname + location.search);
    }
  }

  async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (state.token) headers.set('Authorization', `Bearer ${state.token}`);
    if (options.body && typeof options.body !== 'string') {
      headers.set('Content-Type', 'application/json');
      options.body = JSON.stringify(options.body);
    }
    const response = await fetch(path, { ...options, headers, cache: 'no-store' });
    let payload = {};
    try { payload = await response.json(); } catch (_) {}
    if (response.status === 401) {
      showAuth();
      throw new Error('认证失败，请输入正确 Token');
    }
    if (!response.ok) throw new Error(payload.message || `HTTP ${response.status}`);
    return payload;
  }

  async function publicApi() {
    const response = await fetch('/api/public/config', { cache: 'no-store' });
    return response.json();
  }

  function setTheme(mode) {
    document.documentElement.dataset.theme = mode || 'system';
  }

  function showAuth(message = '') {
    $('#authGate').classList.remove('hidden');
    $('#authError').textContent = message;
    $('#authToken').value = state.token;
    setTimeout(() => $('#authToken').focus(), 0);
  }

  function hideAuth() { $('#authGate').classList.add('hidden'); $('#authError').textContent = ''; }

  function connected(ok) {
    $('#connectionDot').classList.toggle('online', ok);
    $('#connectionText').textContent = ok ? '已连接' : '连接失败';
  }

  let toastTimer;
  function toast(message) {
    const node = $('#toast');
    node.textContent = message;
    node.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => node.classList.remove('show'), 2600);
  }

  function showError(error) {
    console.error(error);
    toast(error?.message || String(error));
  }

  function showModal(title, body) {
    $('#modalTitle').textContent = title;
    $('#modalBody').textContent = body || '（无输出）';
    $('#modal').classList.remove('hidden');
  }

  function activatePage(page) {
    state.page = page;
    $$('#nav button').forEach(b => b.classList.toggle('active', b.dataset.page === page));
    $$('.page').forEach(p => p.classList.toggle('active', p.id === `page-${page}`));
    const [title, subtitle] = titles[page] || ['', ''];
    $('#pageTitle').textContent = title;
    $('#pageSubtitle').textContent = subtitle;
    refreshCurrentPage().catch(showError);
  }

  async function loadStatus() {
    const data = await api('/api/status');
    const cards = [
      ['Magisk', data.active ? (data.magiskVersion || `v${data.magiskVersionCode}`) : '未激活', data.active],
      ['Root', data.rooted ? '可用' : '不可用', data.rooted],
      ['Zygisk', data.zygiskRunning ? '运行中' : (data.zygiskConfigured ? '待重启' : '关闭'), data.zygiskRunning],
      ['DenyList', data.denyList ? '启用' : '关闭', data.denyList],
    ];
    $('#statusGrid').innerHTML = cards.map(([label, value, good]) => `<div class="stat"><span>${esc(label)}</span><strong class="${good ? 'good' : ''}">${esc(value)}</strong></div>`).join('');
    const entries = [
      ['设备', `${data.manufacturer || ''} ${data.device || ''}`.trim()],
      ['Android', `${data.android} (API ${data.sdk})`],
      ['App', `${data.appVersion} (${data.appVersionCode})`],
      ['Magisk Code', data.magiskVersionCode],
      ['WebUI', `:${data.webui.port}`],
      ['认证', authLabel(data.webui.authMode)],
    ];
    $('#deviceInfo').innerHTML = entries.map(([k,v]) => `<div class="kv"><span>${esc(k)}</span><strong>${esc(v)}</strong></div>`).join('');
    connected(true);
  }

  async function loadModules() {
    const { modules = [] } = await api('/api/modules');
    $('#moduleList').innerHTML = modules.length ? modules.map(m => `
      <div class="row-card" data-module-id="${esc(m.id)}">
        <div><h3>${esc(m.name)} <span class="pill">${esc(m.version || m.versionCode)}</span></h3><p>${esc(m.description || m.author || m.id)}</p></div>
        <div class="row-actions">
          ${m.hasAction ? '<button data-module-action="run">运行操作</button>' : ''}
          <button data-module-action="toggle">${m.enabled ? '停用' : '启用'}</button>
          <button data-module-action="remove" class="${m.remove ? '' : 'danger-outline'}">${m.remove ? '恢复' : '删除'}</button>
        </div>
      </div>`).join('') : '<div class="empty">没有已安装模块</div>';
  }

  async function moduleAction(target, action) {
    const id = target.closest('[data-module-id]')?.dataset.moduleId;
    if (!id) return;
    if (action === 'run') {
      const result = await api(`/api/modules/${encodeURIComponent(id)}/action`, { method: 'POST', body: {} });
      showModal(`模块操作 · ${id}`, [...(result.stdout || []), ...(result.stderr || [])].join('\n'));
      return;
    }
    const row = target.closest('[data-module-id]');
    const isToggle = action === 'toggle';
    const body = isToggle ? { enabled: target.textContent.trim() === '启用' } : { remove: target.textContent.trim() === '删除' };
    await api(`/api/modules/${encodeURIComponent(id)}/state`, { method: 'POST', body });
    await loadModules();
  }

  async function loadSuperuser() {
    const { policies = [] } = await api('/api/superuser');
    $('#policyList').innerHTML = policies.length ? policies.map(p => {
      const app = p.apps?.[0] || { appName: `UID ${p.uid}`, packageName: '' };
      return `<div class="row-card" data-policy-uid="${p.uid}">
        <div><h3>${esc(app.appName)} <span class="pill">UID ${p.uid}</span></h3><p>${esc(app.packageName)}${p.apps?.length > 1 ? ` · 共享 UID (${p.apps.length})` : ''}</p></div>
        <div class="row-actions">
          <select data-policy-field="policy" aria-label="授权策略"><option value="query" ${p.policy==='query'?'selected':''}>询问</option><option value="deny" ${p.policy==='deny'?'selected':''}>拒绝</option><option value="allow" ${p.policy==='allow'?'selected':''}>允许</option><option value="restrict" ${p.policy==='restrict'?'selected':''}>受限</option></select>
          <label class="check"><input data-policy-field="notification" type="checkbox" ${p.notification?'checked':''}>通知</label>
          <label class="check"><input data-policy-field="logging" type="checkbox" ${p.logging?'checked':''}>日志</label>
          <button data-policy-delete class="danger-outline">删除</button>
        </div>
      </div>`;
    }).join('') : '<div class="empty">没有超级用户策略</div>';
  }

  async function updatePolicy(element) {
    const row = element.closest('[data-policy-uid]');
    if (!row) return;
    const uid = row.dataset.policyUid;
    if (element.matches('[data-policy-delete]')) {
      if (!confirm(`删除 UID ${uid} 的超级用户策略？`)) return;
      await api(`/api/superuser/${uid}`, { method: 'DELETE' });
    } else {
      const field = element.dataset.policyField;
      const value = element.type === 'checkbox' ? element.checked : element.value;
      await api(`/api/superuser/${uid}`, { method: 'POST', body: { [field]: value } });
    }
    await loadSuperuser();
  }

  async function loadLogs() {
    const magisk = state.logKind === 'magisk';
    $('#magiskLog').classList.toggle('hidden', !magisk);
    $('#suLogList').classList.toggle('hidden', magisk);
    $$('[data-log]').forEach(b => b.classList.toggle('active', b.dataset.log === state.logKind));
    if (magisk) {
      const data = await api('/api/logs/magisk');
      $('#magiskLog').textContent = data.log || '（无日志）';
      $('#magiskLog').scrollTop = $('#magiskLog').scrollHeight;
    } else {
      const { logs = [] } = await api('/api/logs/su');
      $('#suLogList').innerHTML = logs.length ? logs.map(log => `<div class="row-card"><div><h3>${esc(log.appName)} <span class="pill">${log.action >= 2 ? '允许' : '拒绝'}</span></h3><p>${esc(log.command || log.packageName)}</p></div><div class="row-actions"><span class="pill">UID ${log.fromUid}</span><span>${new Date(log.time).toLocaleString()}</span></div></div>`).join('') : '<div class="empty">没有 SU 日志</div>';
    }
  }

  async function clearLogs() {
    if (!confirm('确定清空当前日志？')) return;
    await api(state.logKind === 'magisk' ? '/api/logs/magisk' : '/api/logs/su', { method: 'DELETE' });
    await loadLogs();
    toast('日志已清空');
  }

  function setFormValue(form, name, value) {
    const input = form.elements[name];
    if (!input) return;
    if (input.type === 'checkbox') input.checked = !!value;
    else input.value = String(value);
  }

  async function loadSettings() {
    state.settings = await api('/api/settings');
    Object.entries(state.settings).forEach(([k,v]) => setFormValue($('#settingsForm'), k, v));
  }

  async function saveSettings(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const body = {};
    ['zygisk','denyList'].forEach(k => body[k] = form.elements[k].value === 'true');
    ['rootMode','namespaceMode','suAutoResponse','suNotification'].forEach(k => body[k] = Number(form.elements[k].value));
    ['suReAuth','suTapjack','suRestrict','checkUpdate','doh'].forEach(k => body[k] = form.elements[k].checked);
    const result = await api('/api/settings', { method: 'POST', body });
    state.settings = result.settings;
    toast('Magisk 设置已保存');
  }

  async function loadWebUiConfig() {
    const data = await api('/api/config');
    state.webuiConfig = data;
    $('#authMode').value = data.authMode;
    $('#themeMode').value = data.theme;
    $('#currentToken').textContent = data.token || '无需 Token';
    $('#customTokenRow').classList.toggle('hidden', data.authMode !== 'custom');
    $('#currentTokenRow').classList.toggle('hidden', data.authMode === 'none');
    $('#noAuthWarning').classList.toggle('hidden', data.authMode !== 'none');
    $('#regenTokenBtn').classList.toggle('hidden', data.authMode !== 'random');
    setTheme(data.theme);
  }

  async function saveWebUi(event, regenerate = false) {
    if (event) event.preventDefault();
    const mode = $('#authMode').value;
    const body = { authMode: mode, theme: $('#themeMode').value, regenerateRandomToken: regenerate };
    if (mode === 'custom') body.customToken = $('#customToken').value || state.webuiConfig?.token || '';
    if (mode === 'none' && !confirm('无认证会允许局域网内可访问此端口的设备直接控制 Magisk。确定继续？')) return;
    const result = await api('/api/config', { method: 'POST', body });
    state.webuiConfig = result.config;
    if (result.config.authMode === 'none') {
      state.token = '';
      localStorage.removeItem('magiskWebUiToken');
    } else if (result.config.token) {
      state.token = result.config.token;
      localStorage.setItem('magiskWebUiToken', state.token);
    }
    await loadWebUiConfig();
    toast(regenerate ? '随机 Token 已重新生成' : 'WebUI 设置已保存');
  }

  async function power(mode) {
    if (!confirm(`确定执行 ${mode}？设备会立即重启。`)) return;
    await api('/api/power', { method: 'POST', body: { mode } });
    toast('命令已发送');
  }

  function authLabel(mode) { return ({ none:'无认证', random:'随机 Token', custom:'自定义 Token' })[mode] || mode; }

  async function refreshCurrentPage() {
    switch (state.page) {
      case 'dashboard': return loadStatus();
      case 'modules': return loadModules();
      case 'superuser': return loadSuperuser();
      case 'logs': return loadLogs();
      case 'settings': return loadSettings();
      case 'webui': return loadWebUiConfig();
    }
  }

  async function bootstrap() {
    takeFragmentToken();
    state.publicConfig = await publicApi();
    setTheme(state.publicConfig.theme);
    try {
      await loadStatus();
      hideAuth();
    } catch (error) {
      connected(false);
      if (state.publicConfig.requiresAuth) showAuth(error.message);
      else showError(error);
    }
  }

  $('#nav').addEventListener('click', e => { const btn = e.target.closest('[data-page]'); if (btn) activatePage(btn.dataset.page); });
  $('#refreshBtn').addEventListener('click', () => refreshCurrentPage().catch(showError));
  $('#authForm').addEventListener('submit', async e => {
    e.preventDefault();
    state.token = $('#authToken').value.trim();
    localStorage.setItem('magiskWebUiToken', state.token);
    try { await loadStatus(); hideAuth(); toast('认证成功'); } catch (error) { showAuth(error.message); }
  });
  $('#moduleList').addEventListener('click', e => { const btn = e.target.closest('[data-module-action]'); if (btn) moduleAction(btn, btn.dataset.moduleAction).catch(showError); });
  $('#policyList').addEventListener('change', e => { if (e.target.dataset.policyField) updatePolicy(e.target).catch(showError); });
  $('#policyList').addEventListener('click', e => { const btn = e.target.closest('[data-policy-delete]'); if (btn) updatePolicy(btn).catch(showError); });
  $$('[data-log]').forEach(b => b.addEventListener('click', () => { state.logKind = b.dataset.log; loadLogs().catch(showError); }));
  $('#clearLogBtn').addEventListener('click', () => clearLogs().catch(showError));
  $('#settingsForm').addEventListener('submit', e => saveSettings(e).catch(showError));
  $('#authMode').addEventListener('change', () => {
    const mode = $('#authMode').value;
    $('#customTokenRow').classList.toggle('hidden', mode !== 'custom');
    $('#noAuthWarning').classList.toggle('hidden', mode !== 'none');
    $('#regenTokenBtn').classList.toggle('hidden', mode !== 'random');
  });
  $('#themeMode').addEventListener('change', () => setTheme($('#themeMode').value));
  $('#webuiForm').addEventListener('submit', e => saveWebUi(e).catch(showError));
  $('#regenTokenBtn').addEventListener('click', () => saveWebUi(null, true).catch(showError));
  $('#copyTokenBtn').addEventListener('click', async () => { try { await navigator.clipboard.writeText($('#currentToken').textContent); toast('Token 已复制'); } catch (_) { toast('浏览器不允许剪贴板访问'); } });
  $('#powerGrid').addEventListener('click', e => { const btn = e.target.closest('[data-power]'); if (btn) power(btn.dataset.power).catch(showError); });
  $('#modalClose').addEventListener('click', () => $('#modal').classList.add('hidden'));
  $('#modal').addEventListener('click', e => { if (e.target.id === 'modal') $('#modal').classList.add('hidden'); });

  bootstrap().catch(showError);
})();
