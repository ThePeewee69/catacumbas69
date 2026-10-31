/** Bridge API, served by Catapulsar and forwarded to Cataponte (ADR 0001). */
const BRIDGES_API = '/api/v1/bridges';
/** localStorage key of the auto-refresh on/off switch (per browser). */
const AUTO_REFRESH_KEY = 'autoRefresh';
/** Re-read the shared refresh interval from the server every this many ticks. */
const INTERVAL_RELOAD_TICKS = 6;

/** Overview choices remembered per browser. */
const OVERVIEW_PERIODS = ['1h', '24h', '7d', '30d'];
const OVERVIEW_METRICS = { in: 'Published', out: 'Delivered', backlog: 'Backlog' };

/**
 * Reads a per-browser preference.
 * @param {string} key localStorage key
 * @param {string} fallback value when missing or storage is unavailable
 * @param {string[]} allowed accepted values
 * @returns {string} the stored value or the fallback
 */
function storedChoice(key, fallback, allowed) {
  try { const v = localStorage.getItem(key); return allowed.includes(v) ? v : fallback; } catch { return fallback; }
}

/** Reads the per-browser auto-refresh switch; on unless explicitly turned off. */
function autoRefreshStored() {
  try { return localStorage.getItem(AUTO_REFRESH_KEY) !== 'off'; } catch { return true; }
}

/**
 * Extracts a readable message from an error body: RFC 9457 Problem Details (`detail`/`title`),
 * a legacy `{ error }` / `{ message }` object, or plain text.
 * @param {object|string} body parsed response body
 * @param {string} fallback text to use when the body has no message
 * @returns {string} the message to show
 */
function problemMessage(body, fallback) {
  if (typeof body === 'string') return body || fallback;
  return body?.detail ?? body?.title ?? body?.error ?? body?.message ?? fallback;
}

function app() {
  return {
    // ── State ──────────────────────────────────────────────────────────────
    tenants: [], namespaces: [], topics: [],
    sel: { tenant: null, namespace: null, topic: null },
    tab: 'stats',
    stats: null,
    // Cluster / tenant / namespace overview from Catametricas' statistics (ADR 0007).
    overview: { data: null, dataKey: null, loading: false, error: '', hover: null,
                period: storedChoice('overviewPeriod', '24h', OVERVIEW_PERIODS),
                metric: storedChoice('overviewMetric', 'in', Object.keys(OVERVIEW_METRICS)) },
    // Catametricas collection interval, edited in ⚙️ Settings (pulsario_settings, audited).
    collect: { seconds: 60, defaultSeconds: 60, min: 15, max: 600, custom: false, draft: 60 },
    // Auto-refresh: `on` is per browser; `seconds` is shared by every user (pulsario_settings, audited).
    auto: { on: autoRefreshStored(), seconds: 10, defaultSeconds: 10, min: 2, max: 300, custom: false,
            menuOpen: false, draft: 10, saving: false, tick: 0, busy: false, timer: null },
    subscriptions: [],
    newTopic: '',
    newSub: '',
    produce: { payload: '', key: '', lastId: null },
    backlog: { sub: '', count: 10, messages: [], queried: false },
    toast: { msg: '', type: 'success' },
    loading: { tenants: false, namespaces: false, topics: false, stats: false,
               subs: false, produce: false, backlog: false, newSub: false },

    settings: {
      open: false,
      pulsarUrl: localStorage.getItem('pulsarUrl') || 'http://192.168.86.35:31080',
      healthState: null,   // null | 'checking' | 'ok' | 'error'
      healthMsg: ''
    },
    modal: { type: null, message: null, editPayload: '', editKey: '', saving: false },

    // ── Cataponte / Bridges ─────────────────────────────────────────────────
    bridgesView: false,
    bridges: {
      list: [],
      status: {},
      traffic: {},  // bridgeId → { 'SOURCE topic' | 'DESTINATION topic' → all-time totals }
      settings: { url: '', defaultUrl: '', custom: false, draft: '', saving: false, healthState: null, healthMsg: '' },
      loading: false,
      formSaving: false,
      form: null,   // null=closed; object=creating/editing
      topics: [],          // all existing topics, full names (persistent://tenant/ns/topic)
      topicsLoading: false,
      srcFilter: '',
      dstFilter: '',
    },

    // ── Init ───────────────────────────────────────────────────────────────
    async init() {
      await this.loadRefreshInterval();
      this.scheduleRefresh();
      await this.loadTenants();
      await this.loadOverview();
    },

    // ── Auto-refresh ───────────────────────────────────────────────────────
    /** Loads the shared interval; keeps the current one if the server cannot be reached. */
    async loadRefreshInterval() {
      try {
        const r = await this.api('/api/v1/settings/refresh-interval');
        const changed = r.seconds !== this.auto.seconds;
        Object.assign(this.auto, { seconds: r.seconds, defaultSeconds: r.defaultSeconds, min: r.min,
                                   max: r.max, custom: r.custom });
        if (changed) this.scheduleRefresh();
      } catch { /* keep the current interval */ }
    },

    /** (Re)arms the timer with the current interval; one pending timer at a time. */
    scheduleRefresh() {
      clearTimeout(this.auto.timer);
      this.auto.timer = setTimeout(async () => {
        await this.refreshVisibleView();
        this.scheduleRefresh();
      }, this.auto.seconds * 1000);
    },

    /**
     * Refreshes everything on screen that only reads: the sidebar lists, Overview statistics, the
     * subscription list, and in the Bridges panel the bridge list, status and traffic counts. Backlog
     * peek is never repeated automatically because each peek is written to the audit log. Nothing is
     * cleared and no spinner is shown; errors are ignored and the next tick retries. Skipped while
     * switched off, while the browser tab is hidden, or while the previous refresh is still running.
     */
    async refreshVisibleView() {
      if (!this.auto.on || document.hidden || this.auto.busy) return;
      this.auto.busy = true;
      this.auto.tick++;
      try {
        const work = [this.refreshSidebar()];
        if (this.bridgesView) {
          work.push(this.refreshBridgeList(), this.loadBridgeStatus());
        } else if (this.sel.topic && this.tab === 'stats' && this.stats) {
          work.push(this.refreshStats());
        } else if (this.sel.topic && this.tab === 'subs') {
          work.push(this.refreshSubs());
        } else if (this.sel.topic && this.tab === 'backlog') {
          // Statistics and the subscription list only: re-peeking would write an audit entry every tick.
          work.push(this.refreshStats(), this.refreshSubs());
        } else if (!this.sel.topic) {
          work.push(this.loadOverview(true));
        }
        if (this.auto.tick % INTERVAL_RELOAD_TICKS === 0) work.push(this.loadRefreshInterval());
        await Promise.all(work);
      } finally {
        this.auto.busy = false;
      }
    },

    /** Turns auto-refresh on or off in this browser only. */
    toggleAutoRefresh() {
      this.auto.on = !this.auto.on;
      try { localStorage.setItem(AUTO_REFRESH_KEY, this.auto.on ? 'on' : 'off'); } catch { /* not persisted */ }
      if (this.auto.on) this.refreshVisibleView();
    },

    openRefreshMenu() {
      this.auto.draft = this.auto.seconds;
      this.auto.menuOpen = !this.auto.menuOpen;
    },

    /**
     * Saves the interval for every user (stored in MongoDB and audited), or resets it to the default.
     * @param {'PUT'|'DELETE'} method PUT saves `auto.draft`, DELETE goes back to the default
     */
    async saveRefreshInterval(method = 'PUT') {
      const a = this.auto;
      a.saving = true;
      try {
        const options = { method };
        if (method === 'PUT') options.body = JSON.stringify({ seconds: Number(a.draft) });
        const r = await this.api('/api/v1/settings/refresh-interval', options);
        Object.assign(a, { seconds: r.seconds, custom: r.custom, menuOpen: false });
        this.scheduleRefresh();
        this.notify(`Auto-refresh every ${r.seconds} s for every user`);
      } catch (e) {
        this.notify('Refresh interval not saved: ' + e.message, 'error');
      } finally {
        a.saving = false;
      }
    },

    /**
     * Replaces a list only when its content changed, so Alpine does not re-render it on every tick.
     * @param {string} key property of this component holding the list
     * @param {Array} value new list
     */
    replaceIfChanged(key, value) {
      if (JSON.stringify(this[key]) !== JSON.stringify(value)) this[key] = value;
    },

    // ── Overview: cluster → tenants, tenant → namespaces, namespace → topics ────
    /** @returns {'cluster'|'tenant'|'namespace'} what the overview shows for the current selection */
    overviewScope() {
      return this.sel.namespace ? 'namespace' : this.sel.tenant ? 'tenant' : 'cluster';
    },

    /** @returns {string} identity of the current overview (scope + period), to drop late answers */
    overviewKey() {
      return [this.sel.tenant ?? '', this.sel.namespace ?? '', this.overview.period].join('|');
    },

    /**
     * Loads the overview of the current selection from Catametricas' statistics (MongoDB reads only).
     * @param {boolean} silent true for auto-refresh: no spinner, errors keep the last data
     */
    async loadOverview(silent = false) {
      const key = this.overviewKey();
      const parts = [this.sel.tenant, this.sel.namespace].filter(Boolean).map(encodeURIComponent);
      const url = ['/api/v1/overview', ...parts].join('/') + `?period=${this.overview.period}`;
      if (!silent) {
        this.overview.loading = this.overview.dataKey !== key;
        this.overview.error = '';
      }
      try {
        const data = await this.api(url);
        if (key !== this.overviewKey() || this.sel.topic) return;   // selection changed meanwhile
        this.overview.data = data;
        this.overview.dataKey = key;
        this.overview.error = '';
      } catch (e) {
        if (!silent && key === this.overviewKey()) this.overview.error = e.message;
      } finally {
        if (!silent && key === this.overviewKey()) this.overview.loading = false;
      }
    },

    /** Shows the whole-cluster overview (logo and first breadcrumb). */
    showCluster() {
      if (this.bridgesView) this.closeBridges();
      this.sel = { tenant: null, namespace: null, topic: null };
      this.namespaces = []; this.topics = []; this.stats = null; this.subscriptions = [];
      this.loadOverview();
    },

    /** @param {string} period one of OVERVIEW_PERIODS; remembered in this browser */
    setOverviewPeriod(period) {
      this.overview.period = period;
      try { localStorage.setItem('overviewPeriod', period); } catch { /* not remembered */ }
      this.loadOverview();
    },

    /** @param {'in'|'out'|'backlog'} metric what the charts show; remembered in this browser */
    setOverviewMetric(metric) {
      this.overview.metric = metric;
      try { localStorage.setItem('overviewMetric', metric); } catch { /* not remembered */ }
    },

    /** @returns {string} heading of the overview */
    overviewTitle() {
      const s = this.overviewScope();
      if (s === 'cluster') return 'Cluster';
      return s === 'tenant' ? `Tenant ${this.sel.tenant}` : `Namespace ${this.sel.tenant}/${this.sel.namespace}`;
    },

    /** @returns {string} what the rows are */
    overviewRowLabel() {
      return { cluster: 'Tenants', tenant: 'Namespaces', namespace: 'Topics' }[this.overviewScope()];
    },

    /** @returns {string} freshness of the statistics, or why there are none */
    overviewFreshness() {
      const d = this.overview.data;
      if (!d || !d.collectedAt) {
        return 'No statistics yet — start Catametricas; numbers appear after its second collection.';
      }
      const seconds = Math.max(0, Math.round((Date.now() - Date.parse(d.collectedAt)) / 1000));
      const ago = seconds < 90 ? `${seconds} s` : seconds < 5400 ? `${Math.round(seconds / 60)} min`
        : `${Math.round(seconds / 3600)} h`;
      return `Collected by Catametricas ${ago} ago` + (d.intervalSeconds ? ` · every ${d.intervalSeconds} s` : '');
    },

    /**
     * Rows of the overview: everything Catametricas knows plus tenants/namespaces/topics listed in the sidebar
     * that have no statistics yet; partitions are shown as their topic.
     * @returns {object[]} rows sorted by name; `gone` marks names that no longer exist
     */
    overviewRows() {
      const d = this.overview.data;
      if (!d) return [];
      const scope = this.overviewScope();
      const listed = scope === 'cluster' ? this.tenants : scope === 'tenant' ? this.namespaces
        : [...new Set(this.topics.map(t => t.replace(/-partition-\d+$/, '')))];
      const rows = new Map(d.rows.map(r => [r.name, { ...r, gone: !r.current && !listed.includes(r.name) }]));
      listed.forEach(name => {
        if (!rows.has(name)) rows.set(name, { name, current: null, period: null, total: null, series: null });
      });
      return [...rows.values()].sort((a, b) => a.name.localeCompare(b.name));
    },

    /**
     * @param {object} row overview row (or the summary)
     * @returns {(number|null)[]} the values of the selected metric per chart bucket
     */
    overviewSeries(row) {
      const s = row?.series;
      if (!s) return [];
      return this.overview.metric === 'out' ? s.msgsOut : this.overview.metric === 'backlog' ? s.backlog : s.msgsIn;
    },

    /** @param {object} row overview row @returns {boolean} whether clicking opens it */
    canDrill(row) {
      const scope = this.overviewScope();
      if (row.gone) return false;
      return scope !== 'namespace' || this.topics.includes(row.name);
    },

    /** Opens a row: tenant → its namespaces, namespace → its topics, topic → topic view. */
    drill(row) {
      if (!this.canDrill(row)) return;
      const scope = this.overviewScope();
      if (scope === 'cluster') this.selectTenant(row.name);
      else if (scope === 'tenant') this.selectNamespace(row.name);
      else this.selectTopic(row.name);
    },

    /**
     * SVG path of a line through the values; null values break the line.
     * @param {(number|null)[]} values data points
     * @param {number} width drawing width
     * @param {number} height drawing height
     * @param {number} [max] top of the scale (defaults to the largest value)
     * @returns {string} path data ('' when there is nothing to draw)
     */
    linePath(values, width, height, max) {
      if (!values || values.length < 2) return '';
      const top = max ?? Math.max(1, ...values.filter(v => v != null));
      const dx = width / (values.length - 1);
      let d = ''; let pen = false;
      values.forEach((v, i) => {
        if (v == null) { pen = false; return; }
        const x = (i * dx).toFixed(1);
        const y = (height - 1 - (v / top) * (height - 2)).toFixed(1);
        d += `${pen ? 'L' : 'M'}${x},${y}`;
        pen = true;
      });
      return d;
    },

    /** @returns {object} geometry and labels of the big overview chart */
    chartModel() {
      const d = this.overview.data;
      const values = this.overviewSeries(d?.summary);
      const max = this.niceMax(Math.max(0, ...values.filter(v => v != null)));
      const times = d?.times ?? [];
      const long = ['7d', '30d'].includes(d?.period);
      const label = t => new Date(t).toLocaleString('en-GB', long
        ? { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }
        : { hour: '2-digit', minute: '2-digit' });
      const xs = times.length ? [0, Math.floor((times.length - 1) / 2), times.length - 1] : [];
      return {
        width: 760, height: 160, max,
        line: this.linePath(values, 760, 160, max),
        yLabels: [max, max / 2, 0].map(v => this.fmtCompact(v)),
        xLabels: xs.map(i => ({ x: times.length > 1 ? (i / (times.length - 1)) * 760 : 0, text: label(times[i]) })),
        values, times, label,
      };
    },

    /**
     * Updates the hover marker of the big chart.
     * @param {MouseEvent} event mouse move over the chart
     */
    chartHover(event) {
      const m = this.chartModel();
      if (m.values.length < 2) return;
      const rect = event.currentTarget.getBoundingClientRect();
      const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
      const i = Math.round(ratio * (m.values.length - 1));
      const v = m.values[i];
      this.overview.hover = {
        x: (i / (m.values.length - 1)) * m.width,
        y: v == null ? null : m.height - 1 - (v / m.max) * (m.height - 2),
        text: `${m.label(m.times[i])} · ${v == null ? 'no data' : this.fmtNum(v)} ${OVERVIEW_METRICS[this.overview.metric].toLowerCase()}`,
      };
    },

    /** @param {number} n largest value @returns {number} a round top for the chart scale */
    niceMax(n) {
      if (n <= 0) return 1;
      const p = Math.pow(10, Math.floor(Math.log10(n)));
      return [1, 2, 2.5, 5, 10].map(f => f * p).find(v => v >= n);
    },

    /** @param {number} n value @returns {string} short form, e.g. 1.2k, 3.4M */
    fmtCompact(n) {
      if (n == null) return '—';
      const a = Math.abs(n);
      if (a >= 1e9) return (n / 1e9).toFixed(1).replace(/\.0$/, '') + 'G';
      if (a >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
      if (a >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'k';
      return String(Math.round(n));
    },

    /** Reloads tenants, the open tenant's namespaces and the open namespace's topics, silently. */
    async refreshSidebar() {
      const { tenant, namespace } = this.sel;
      try {
        const [tenants, namespaces, topics] = await Promise.all([
          this.api('/api/v1/tenants'),
          tenant ? this.api(`/api/v1/namespaces/${tenant}`) : null,
          tenant && namespace ? this.api(`/api/v1/topics/${tenant}/${namespace}`) : null,
        ]);
        this.replaceIfChanged('tenants', tenants);
        if (namespaces && this.sel.tenant === tenant) this.replaceIfChanged('namespaces', namespaces);
        if (topics && this.sel.tenant === tenant && this.sel.namespace === namespace) {
          this.replaceIfChanged('topics', topics);
        }
      } catch { /* keep the current lists */ }
    },

    async reload() {
      if (this.sel.topic) await this.selectTopic(this.sel.topic);
      else if (this.sel.namespace) await this.selectNamespace(this.sel.namespace);
      else if (this.sel.tenant) await this.selectTenant(this.sel.tenant);
      else await this.loadTenants();
    },

    // ── Settings ───────────────────────────────────────────────────────────
    async openSettings() {
      this.settings.open = true;
      this.bridges.settings.healthState = null;
      await Promise.all([this.loadCataponteSettings(), this.loadCollectInterval()]);
      this.bridges.settings.draft = this.bridges.settings.url;
      this.collect.draft = this.collect.seconds;
    },

    /**
     * Saves the Pulsar label (this browser) and whatever changed on the server (Cataponte URL, Catametricas
     * interval; both audited). The dialog stays open if a server change is refused.
     */
    async saveSettings() {
      try { localStorage.setItem('pulsarUrl', this.settings.pulsarUrl); } catch { /* label not remembered */ }
      const s = this.bridges.settings;
      let changed = false;
      if (s.draft && s.draft !== s.url) {
        if (!await this.changeCataponteUrl('PUT', { url: s.draft })) return;
        changed = true;
      }
      if (Number(this.collect.draft) !== this.collect.seconds) {
        if (!await this.saveCollectInterval('PUT')) return;
        changed = true;
      }
      if (!changed) this.notify('Settings saved');
      this.settings.open = false;
    },

    /** Loads Catametricas' collection interval (stored in pulsario_settings). */
    async loadCollectInterval() {
      try {
        const r = await this.api('/api/v1/settings/collect-interval');
        Object.assign(this.collect, { seconds: r.seconds, defaultSeconds: r.defaultSeconds, min: r.min,
                                      max: r.max, custom: r.custom });
      } catch (e) { this.notify('Settings: ' + e.message, 'error'); }
    },

    /**
     * Changes or resets Catametricas' collection interval; Catametricas applies it from its next collection.
     * @param {'PUT'|'DELETE'} method PUT saves `collect.draft`, DELETE goes back to the default
     * @returns {Promise<boolean>} whether it was saved
     */
    async saveCollectInterval(method) {
      try {
        const options = { method };
        if (method === 'PUT') options.body = JSON.stringify({ seconds: Number(this.collect.draft) });
        const r = await this.api('/api/v1/settings/collect-interval', options);
        Object.assign(this.collect, { seconds: r.seconds, custom: r.custom, draft: r.seconds });
        this.notify(`Catametricas collects every ${r.seconds} s`);
        return true;
      } catch (e) {
        this.notify('Collection interval not saved: ' + e.message, 'error');
        return false;
      }
    },

    /** Calls Cataponte through Catapulsar at the URL in use (save first to test a new one). */
    async checkCataponte() {
      const s = this.bridges.settings;
      s.healthState = 'checking'; s.healthMsg = '';
      try {
        const status = await this.bridgesApi(`${BRIDGES_API}/status`);
        s.healthState = 'ok';
        s.healthMsg = `${s.url} · ${status.running ?? 0} bridge(s) running`;
      } catch (e) {
        s.healthState = 'error';
        s.healthMsg = e.message;
      }
    },

    async checkHealth() {
      this.settings.healthState = 'checking';
      this.settings.healthMsg = '';
      try {
        const tenants = await this.api('/api/v1/tenants');
        this.settings.healthState = 'ok';
        this.settings.healthMsg = tenants.length + ' tenant(s): ' + tenants.slice(0, 3).join(', ') + (tenants.length > 3 ? '…' : '');
      } catch (e) {
        this.settings.healthState = 'error';
        this.settings.healthMsg = e.message;
      }
    },

    connLabel() {
      const host = (() => {
        try { return new URL(this.settings.pulsarUrl).host; } catch { return '?'; }
      })();
      if (this.settings.healthState === 'ok') return host + ' ✓';
      if (this.settings.healthState === 'error') return host + ' ✕';
      return host;
    },

    // ── Modal ──────────────────────────────────────────────────────────────
    openView(msg) {
      this.modal = { type: 'view', message: msg, editPayload: msg.payload, editKey: msg.key || '', saving: false };
    },
    openEdit(msg) {
      this.modal = { type: 'edit', message: msg, editPayload: msg.payload, editKey: msg.key || '', saving: false };
    },
    closeModal() { this.modal.type = null; },

    async saveEdit() {
      this.modal.saving = true;
      try {
        const res = await this.api(
          `/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/produce`,
          { method: 'POST', body: JSON.stringify({ payload: this.modal.editPayload, key: this.modal.editKey || null }) }
        );
        this.notify('Re-produced → ' + res.messageId);
        this.closeModal();
        await this.loadStats();
      } catch (e) { this.notify('Error: ' + e.message, 'error'); }
      finally { this.modal.saving = false; }
    },

    // ── Skip ───────────────────────────────────────────────────────────────
    // Skips the first `n` messages from the subscription cursor via Spring backend.
    // "Skip" on message #i (0-based) passes n=i+1 so all messages up to and including #i are skipped.
    async skipMessages(n) {
      if (!confirm(`Skip ${n} message(s) from subscription "${this.backlog.sub}"?\nThis is irreversible.`)) return;
      try {
        await this.api(
          `/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/subscriptions/${encodeURIComponent(this.backlog.sub)}/skip/${n}`,
          { method: 'POST' }
        );
        this.notify(`Skipped ${n} message(s) from "${this.backlog.sub}"`);
        await Promise.all([this.peekMessages(), this.refreshStats()]);
      } catch (e) { this.notify('Skip failed: ' + e.message, 'error'); }
    },

    async skipAll() {
      const n = this.backlog.messages.length;
      await this.skipMessages(n);
    },

    /**
     * Skips every message in a subscription's backlog (Overview → Subscription Details).
     * @param {string} sub subscription name
     * @param {number} backlog current backlog size, shown in the confirmation
     */
    async clearBacklog(sub, backlog) {
      if (!confirm(`Clear the whole backlog of subscription "${sub}" (${this.fmtNum(backlog)} message(s))?\n`
        + 'The messages will never be delivered to this subscription. This is irreversible.')) return;
      try {
        await this.api(
          `/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/subscriptions/${encodeURIComponent(sub)}/clear-backlog`,
          { method: 'POST' }
        );
        this.notify(`Backlog of "${sub}" cleared`);
        await this.refreshStats();
      } catch (e) { this.notify('Clear backlog failed: ' + e.message, 'error'); }
    },

    // ── API helper ─────────────────────────────────────────────────────────
    async api(url, options = {}) {
      const res = await fetch(url, {
        headers: { 'Content-Type': 'application/json' },
        ...options
      });
      const ct = res.headers.get('content-type') ?? '';
      const body = ct.includes('json') ? await res.json() : await res.text();
      if (!res.ok) throw new Error(problemMessage(body, res.statusText));
      return body;
    },

    notify(msg, type = 'success') {
      this.toast = { msg, type };
      setTimeout(() => this.toast = { msg: '', type: 'success' }, 3500);
    },

    // ── Tenants / Namespaces / Topics ──────────────────────────────────────
    async loadTenants() {
      this.loading.tenants = true;
      try { this.tenants = await this.api('/api/v1/tenants'); }
      catch (e) { this.notify('Error loading tenants: ' + e.message, 'error'); }
      finally { this.loading.tenants = false; }
    },

    async selectTenant(t) {
      if (this.bridgesView) this.closeBridges();
      this.sel = { tenant: t, namespace: null, topic: null };
      this.loadOverview();
      this.namespaces = []; this.topics = [];
      this.stats = null; this.subscriptions = [];
      this.loading.namespaces = true;
      try { this.namespaces = await this.api(`/api/v1/namespaces/${t}`); }
      catch (e) { this.notify('Error loading namespaces: ' + e.message, 'error'); }
      finally { this.loading.namespaces = false; }
    },

    async selectNamespace(ns) {
      if (this.bridgesView) this.closeBridges();
      this.sel = { ...this.sel, namespace: ns, topic: null };
      this.loadOverview();
      this.topics = []; this.stats = null; this.subscriptions = [];
      this.loading.topics = true;
      try { this.topics = await this.api(`/api/v1/topics/${this.sel.tenant}/${ns}`); }
      catch (e) { this.notify('Error loading topics: ' + e.message, 'error'); }
      finally { this.loading.topics = false; }
    },

    async selectTopic(t) {
      if (this.bridgesView) this.closeBridges();   // leaving the Bridges panel
      this.sel = { ...this.sel, topic: t };
      this.tab = 'stats'; this.stats = null;
      this.produce = { payload: '', key: '', lastId: null };
      this.backlog = { sub: '', count: 10, messages: [], queried: false };
      await this.loadStats();
      await this.loadSubs();
    },

    async createTopic() {
      if (!this.newTopic.trim()) return;
      try {
        await this.api(`/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.newTopic}`, { method: 'POST' });
        this.notify(`Topic "${this.newTopic}" created`);
        this.newTopic = '';
        await this.selectNamespace(this.sel.namespace);
      } catch (e) { this.notify('Error: ' + e.message, 'error'); }
    },

    async deleteTopic(t) {
      if (!confirm(`Delete topic "${t}"?\nAll messages and subscriptions will be removed.`)) return;
      try {
        await this.api(`/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${t}`, { method: 'DELETE' });
        this.notify(`Topic "${t}" deleted`);
        if (this.sel.topic === t) this.sel.topic = null;
        await this.selectNamespace(this.sel.namespace);
      } catch (e) { this.notify('Error: ' + e.message, 'error'); }
    },

    // ── Stats ──────────────────────────────────────────────────────────────
    async loadStats() {
      this.loading.stats = true; this.stats = null;
      try {
        this.stats = await this.api(`/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/stats`);
      } catch (e) { this.notify('Error loading stats: ' + e.message, 'error'); }
      finally { this.loading.stats = false; }
    },

    /** Reloads the statistics in place (no spinner); dropped if the user picked another topic meanwhile. */
    async refreshStats() {
      const { tenant, namespace, topic } = this.sel;
      try {
        const stats = await this.api(`/api/v1/topics/${tenant}/${namespace}/${topic}/stats`);
        const sameTopic = this.sel.tenant === tenant && this.sel.namespace === namespace && this.sel.topic === topic;
        if (sameTopic && (this.tab === 'stats' || this.tab === 'backlog')) this.stats = stats;
      } catch { /* keep the last values; the next tick retries */ }
    },

    // ── Subscriptions ──────────────────────────────────────────────────────
    /** Reloads the subscription list in place; dropped if another topic was selected meanwhile. */
    async refreshSubs() {
      const { tenant, namespace, topic } = this.sel;
      try {
        const subs = await this.api(`/api/v1/topics/${tenant}/${namespace}/${topic}/subscriptions`);
        if (this.sel.topic === topic && this.sel.namespace === namespace && ['subs', 'backlog'].includes(this.tab)) {
          this.replaceIfChanged('subscriptions', subs);
        }
      } catch { /* keep the current list */ }
    },

    async loadSubs() {
      if (!this.sel.topic) return;
      this.loading.subs = true;
      try {
        this.subscriptions = await this.api(`/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/subscriptions`);
      } catch (e) { this.subscriptions = []; }
      finally { this.loading.subs = false; }
    },

    async createSub() {
      if (!this.newSub.trim()) return;
      this.loading.newSub = true;
      try {
        await this.api(`/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/subscriptions/${this.newSub}`, { method: 'POST' });
        this.notify(`Subscription "${this.newSub}" created`);
        this.newSub = '';
        await this.loadSubs();
      } catch (e) { this.notify('Error: ' + e.message, 'error'); }
      finally { this.loading.newSub = false; }
    },

    async deleteSub(sub) {
      if (!confirm(`Delete subscription "${sub}"?`)) return;
      try {
        await this.api(`/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/subscriptions/${sub}`, { method: 'DELETE' });
        this.notify(`Subscription "${sub}" deleted`);
        await this.loadSubs();
      } catch (e) { this.notify('Error: ' + e.message, 'error'); }
    },

    // ── Produce ────────────────────────────────────────────────────────────
    async sendMessage() {
      if (!this.produce.payload.trim()) return;
      this.loading.produce = true; this.produce.lastId = null;
      try {
        const res = await this.api(`/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/produce`, {
          method: 'POST',
          body: JSON.stringify({ payload: this.produce.payload, key: this.produce.key })
        });
        this.produce.lastId = res.messageId;
        this.notify('Message sent');
        await this.loadStats();
      } catch (e) { this.notify('Error sending: ' + e.message, 'error'); }
      finally { this.loading.produce = false; }
    },

    // ── Backlog peek ───────────────────────────────────────────────────────
    /** Opens the Backlog tab: subscription names and their statistics are loaded in the background. */
    openBacklogTab() {
      this.tab = 'backlog';
      this.loadSubs();
      this.refreshStats();
    },

    /**
     * Subscriptions for the Backlog list, with their statistics from the topic stats when available.
     * @returns {{name: string, stats: object|undefined}[]} sorted by name
     */
    backlogSubs() {
      const stats = this.stats?.subscriptions ?? {};
      const names = new Set([...this.subscriptions, ...Object.keys(stats)]);
      return [...names].sort().map(name => ({ name, stats: stats[name] }));
    },

    /**
     * Selects a subscription and peeks its backlog straight away (audited as PEEK_MESSAGES).
     * @param {string} name subscription name
     */
    async selectBacklogSub(name) {
      this.backlog.sub = name;
      await this.peekMessages();
    },

    async peekMessages() {
      if (!this.backlog.sub) return;
      const sub = this.backlog.sub;
      const count = Math.min(100, Math.max(1, Number(this.backlog.count) || 10));
      this.backlog.count = count;
      this.loading.backlog = true; this.backlog.messages = []; this.backlog.queried = false;
      try {
        const messages = await this.api(
          `/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/peek/${encodeURIComponent(sub)}?count=${count}`
        );
        if (this.backlog.sub !== sub) return;   // another subscription was clicked meanwhile
        this.backlog.messages = messages;
        this.backlog.queried = true;
      } catch (e) { this.notify('Peek error: ' + e.message, 'error'); }
      finally { if (this.backlog.sub === sub) this.loading.backlog = false; }
    },

    // ── Bridges ────────────────────────────────────────────────────────────
    openBridges() {
      this.bridgesView = true;
      this.loadCataponteSettings();
      this.loadBridges();
      this.loadBridgeStatus();
    },

    /** @returns {number} bridges whose worker is relaying messages right now */
    runningBridges() {
      return Object.values(this.bridges.status).filter(s => s.running).length;
    },

    // ── Cataponte URL (stored in MongoDB, audited) ──────────────────────────
    async loadCataponteSettings() {
      try {
        Object.assign(this.bridges.settings, await this.api('/api/v1/settings/cataponte'));
      } catch (e) { this.notify('Settings: ' + e.message, 'error'); }
    },

    async resetCataponteUrl() {
      if (!confirm(`Go back to the default Cataponte URL ${this.bridges.settings.defaultUrl}?`)) return;
      if (await this.changeCataponteUrl('DELETE')) this.bridges.settings.draft = this.bridges.settings.url;
    },

    /**
     * Saves or resets the Cataponte URL, then reloads the bridges from the new address.
     * @param {'PUT'|'DELETE'} method PUT saves `body.url`, DELETE goes back to the default
     * @param {{url: string}} [body] new URL
     * @returns {Promise<boolean>} whether the change was saved
     */
    async changeCataponteUrl(method, body) {
      const s = this.bridges.settings;
      s.saving = true;
      try {
        const options = { method };
        if (body) options.body = JSON.stringify(body);
        Object.assign(s, await this.api('/api/v1/settings/cataponte', options));
        s.healthState = null;
        this.notify(`Cataponte URL set to ${s.url}`);
        this.bridges.status = {};
        this.bridges.traffic = {};
        if (this.bridgesView) await Promise.all([this.loadBridges(), this.loadBridgeStatus()]);
        return true;
      } catch (e) {
        this.notify('Cataponte URL not saved: ' + e.message, 'error');
        return false;
      } finally {
        s.saving = false;
      }
    },

    closeBridges() {
      this.bridgesView = false;
      this.bridges.form = null;
    },

    async loadBridges() {
      this.bridges.loading = true;
      try {
        this.bridges.list = await this.bridgesApi(BRIDGES_API);
      } catch (e) {
        this.notify('Cataponte: ' + e.message, 'error');
      } finally {
        this.bridges.loading = false;
      }
    },

    /** Loads runtime status and all-time traffic counts; failures are ignored (Cataponte may be down). */
    async loadBridgeStatus() {
      await Promise.all([this.loadWorkerStatus(), this.loadBridgeTraffic()]);
    },

    /** Reloads the bridge list in place (no spinner); bridges added or removed elsewhere appear. */
    async refreshBridgeList() {
      try {
        const list = await this.bridgesApi(BRIDGES_API);
        if (JSON.stringify(list) !== JSON.stringify(this.bridges.list)) this.bridges.list = list;
      } catch { /* keep the current list */ }
    },

    async loadWorkerStatus() {
      try {
        const s = await this.bridgesApi(`${BRIDGES_API}/status`);
        const map = {};
        (s.workers || []).forEach(w => { map[w.bridgeId] = w; });
        this.bridges.status = map;
      } catch { /* ignore — Cataponte may not be running */ }
    },

    async loadBridgeTraffic() {
      try {
        const totals = await this.bridgesApi(`${BRIDGES_API}/traffic`);
        const map = {};
        (totals || []).forEach(t => { (map[t.bridgeId] ??= {})[`${t.role} ${t.topic}`] = t; });
        this.bridges.traffic = map;
      } catch { /* ignore — counts are optional */ }
    },

    /**
     * All-time message count stored by Cataponte for one topic of a bridge.
     * @param {string} bridgeId bridge id
     * @param {'SOURCE'|'DESTINATION'} role topic role in the bridge
     * @param {string} topic fully-qualified topic
     * @returns {string} e.g. "1,234 msgs", or '' when nothing was counted yet
     */
    trafficLabel(bridgeId, role, topic) {
      const t = this.bridges.traffic[bridgeId]?.[`${role} ${topic}`];
      return t ? `${this.fmtNum(t.messages)} msgs` : '';
    },

    /**
     * Tooltip with the totals of one topic of a bridge.
     * @param {string} bridgeId bridge id
     * @param {'SOURCE'|'DESTINATION'} role topic role in the bridge
     * @param {string} topic fully-qualified topic
     * @returns {string} tooltip text
     */
    trafficTitle(bridgeId, role, topic) {
      const t = this.bridges.traffic[bridgeId]?.[`${role} ${topic}`];
      if (!t) return topic;
      return `${topic}\n${this.fmtNum(t.messages)} messages, ${this.fmtBytes(t.bytes)}`
        + `\nfirst ${this.fmtDate(t.firstAt)} · last ${this.fmtDate(t.lastAt)}`;
    },

    async toggleBridge(bridge) {
      const action = bridge.enabled ? 'disable' : 'enable';
      try {
        await this.bridgesApi(`${BRIDGES_API}/${bridge.id}/${action}`, { method: 'POST' });
        await this.loadBridges();
        await this.loadBridgeStatus();
        this.notify(`Bridge "${bridge.name}" ${bridge.enabled ? 'disabled' : 'enabled'}`);
      } catch (e) {
        this.notify('Cataponte: ' + e.message, 'error');
      }
    },

    async deleteBridge(id) {
      const bridge = this.bridges.list.find(b => b.id === id);
      if (!confirm(`Delete bridge "${bridge?.name ?? id}"?\nThis will stop the relay immediately.`)) return;
      try {
        await this.bridgesApi(`${BRIDGES_API}/${id}`, { method: 'DELETE' });
        this.notify('Bridge deleted');
        await this.loadBridges();
        await this.loadBridgeStatus();
      } catch (e) {
        this.notify('Cataponte: ' + e.message, 'error');
      }
    },

    openNewBridge() {
      this.bridges.form = {
        id: null, name: '', description: '', enabled: true,
        source: { topic: '', subscription: 'cataponte-bridge', subscriptionType: 'Failover' },
        ordered: true,
        destinations: [],
        preserveKey: true, preserveProperties: true
      };
      this.bridges.srcFilter = ''; this.bridges.dstFilter = '';
      this.loadAllTopics();
    },

    openEditBridge(bridge) {
      const f = JSON.parse(JSON.stringify(bridge));
      f.destinations = this.bridgeDestTopics(f).map(topic => ({ topic }));
      delete f.destination;
      this.bridges.form = f;
      this.bridges.srcFilter = ''; this.bridges.dstFilter = '';
      this.loadAllTopics();
    },

    // Destination topics of a bridge (supports legacy single `destination`).
    bridgeDestTopics(b) {
      if (b?.destinations?.length) return b.destinations.map(d => d.topic).filter(Boolean);
      return b?.destination?.topic ? [b.destination.topic] : [];
    },

    isDestSelected(t) {
      return this.bridges.form?.destinations.some(d => d.topic === t);
    },

    toggleDestination(t) {
      const ds = this.bridges.form.destinations;
      const i = ds.findIndex(d => d.topic === t);
      if (i >= 0) ds.splice(i, 1); else ds.push({ topic: t });
    },

    bridgeFormValid() {
      const f = this.bridges.form;
      return Boolean(f && f.name && f.source.topic && f.source.subscription && f.destinations.length > 0
        && !this.orderedOnShared());
    },

    /**
     * Ordered bridges cannot use a Shared subscription (Cataponte rejects it).
     * @returns {boolean} true when the form combines ordered mode with a Shared subscription
     */
    orderedOnShared() {
      const f = this.bridges.form;
      return Boolean(f?.ordered && f.source.subscriptionType === 'Shared');
    },

    /**
     * Keeps the subscription type consistent when the ordering mode is toggled.
     */
    onOrderedChange() {
      const f = this.bridges.form;
      if (f.ordered && f.source.subscriptionType === 'Shared') f.source.subscriptionType = 'Failover';
      if (!f.ordered && f.source.subscriptionType === 'Failover') f.source.subscriptionType = 'Shared';
    },

    // Walks tenants → namespaces → topics through Catapulsar's own API and
    // builds a flat list of full topic names for the bridge pickers.
    async loadAllTopics() {
      this.bridges.topicsLoading = true;
      try {
        const tenants = await this.api('/api/v1/tenants');
        const perNs = await Promise.all(tenants.map(async t => {
          const nss = await this.api(`/api/v1/namespaces/${t}`).catch(() => []);
          return Promise.all(nss.map(async nsRaw => {
            const ns = nsRaw.includes('/') ? nsRaw.split('/').pop() : nsRaw;
            const topics = await this.api(`/api/v1/topics/${t}/${ns}`).catch(() => []);
            return topics.map(tp => tp.includes('://') ? tp : `persistent://${t}/${ns}/${tp}`);
          }));
        }));
        this.bridges.topics = perNs.flat(2)
          .filter(n => !n.split('/').pop().startsWith('__'))   // hide system topics
          .sort();
      } catch (e) {
        this.notify('Error loading topics: ' + e.message, 'error');
      } finally {
        this.bridges.topicsLoading = false;
      }
    },

    filteredTopics(filter, exclude = []) {
      const f = (filter || '').toLowerCase();
      const ex = Array.isArray(exclude) ? exclude : [exclude];
      return this.bridges.topics.filter(t => !ex.includes(t) && (!f || t.toLowerCase().includes(f)));
    },

    closeBridgeForm() {
      this.bridges.form = null;
    },

    async saveBridge() {
      const f = this.bridges.form;
      const isNew = !f.id;
      this.bridges.formSaving = true;
      try {
        const payload = {
          name: f.name, description: f.description, enabled: f.enabled,
          source: f.source, destinations: f.destinations,
          preserveKey: f.preserveKey, preserveProperties: f.preserveProperties,
          ordered: f.ordered, version: f.version ?? null
        };
        if (isNew) {
          await this.bridgesApi(BRIDGES_API, { method: 'POST', body: JSON.stringify(payload) });
        } else {
          await this.bridgesApi(`${BRIDGES_API}/${f.id}`, { method: 'PUT', body: JSON.stringify(payload) });
        }
        this.bridges.form = null;
        await this.loadBridges();
        await this.loadBridgeStatus();
        this.notify(isNew ? 'Bridge created' : 'Bridge updated');
      } catch (e) {
        this.notify('Cataponte: ' + e.message, 'error');
      } finally {
        this.bridges.formSaving = false;
      }
    },

    /**
     * Calls the bridge API (same origin; Catapulsar forwards it to Cataponte).
     * @param {string} path API path, e.g. BRIDGES_API + '/status'
     * @param {RequestInit} [options] fetch options
     * @returns {Promise<any>} parsed JSON body, or text for non-JSON responses
     */
    bridgesApi(path, options = {}) {
      return this.api(path, options);
    },

    // ── Formatters ─────────────────────────────────────────────────────────
    fmt(n) { return n == null ? '—' : parseFloat(n).toFixed(2); },
    fmtNum(n) { return n == null ? '—' : parseInt(n).toLocaleString('en'); },
    fmtBytes(b) {
      if (!b) return '0 B';
      const k = 1024, sizes = ['B','KB','MB','GB','TB'];
      const i = Math.floor(Math.log(b) / Math.log(k));
      return (b / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i];
    },
    fmtDate(ts) {
      if (!ts) return '—';
      return new Date(ts).toLocaleString('en-GB', { dateStyle:'short', timeStyle:'medium' });
    },
    sumBacklog(stats) {
      if (!stats?.subscriptions) return 0;
      return Object.values(stats.subscriptions).reduce((s, sub) => s + (sub.msgBacklog ?? 0), 0);
    },
    truncate(s, n) {
      if (!s) return '';
      return s.length > n ? s.slice(0, n) + '…' : s;
    },
    prettyJson(s) {
      if (!s) return '';
      try { return JSON.stringify(JSON.parse(s), null, 2); }
      catch { return s; }
    }
  };
}
