/** Bridge API, served by Catapulsar and forwarded to Cataponte (ADR 0001). */
const BRIDGES_API = '/api/v1/bridges';
/** Bridge areas API (ADR 0014), forwarded to Cataponte like the bridges. */
const AREAS_API = '/api/v1/areas';
/** localStorage key of the sidebar nodes left open: tenant names and tenant/namespace keys. */
const TREE_OPEN_KEY = 'treeOpen';
/** localStorage key of the last Bridges tab used: 'system', 'general' or an area id. */
const BRIDGES_TAB_KEY = 'bridgesTab';
/** localStorage key of the auto-refresh on/off switch (per browser). */
const AUTO_REFRESH_KEY = 'autoRefresh';
/** Re-read the shared refresh interval from the server every this many ticks. */
const INTERVAL_RELOAD_TICKS = 6;

/** Audit trail API, served by Catapulsar and forwarded to Cataudit (ADR 0009). */
const AUDIT_API = '/api/v1/audit';
/** Audit period choices: label → milliseconds back from now (null = everything). */
const AUDIT_PERIODS = { '1h': 3600e3, '24h': 86400e3, '7d': 7 * 86400e3, '30d': 30 * 86400e3, all: null };
/** Audit entries per page. */
const AUDIT_PAGE_SIZE = 50;

/** Overview choices remembered per browser. */
const OVERVIEW_PERIODS = ['1h', '24h', '7d', '30d'];
const OVERVIEW_METRICS = { in: 'Published', out: 'Delivered', backlog: 'Backlog' };
/** Live mode: a forced collection every this many ms (Catametricas collects a namespace at most every 5 s). */
const LIVE_EVERY_MS = 5000;
/** Live mode turns itself off after this many ms. */
const LIVE_MAX_MS = 10 * 60 * 1000;

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

/**
 * Compiles one area pattern (ADR 0014), with the same rules as Cataponte: written without persistent://,
 * `*` within one segment, `**` across segments, `non-persistent://` for non-persistent topics.
 * @param {string} pattern the glob
 * @returns {(topic: string) => boolean} whether a full topic name matches
 */
function areaPatternMatcher(pattern) {
  const nonPersistent = 'non-persistent://';
  const persistent = !pattern.startsWith(nonPersistent);
  const body = persistent ? pattern : pattern.slice(nonPersistent.length);
  let regex = '';
  for (let i = 0; i < body.length; i++) {
    if (body[i] === '*' && body[i + 1] === '*') { regex += '.*'; i++; }
    else if (body[i] === '*') regex += '[^/]*';
    else regex += body[i].replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }
  const compiled = new RegExp(`^${regex}$`);
  const scheme = persistent ? 'persistent://' : nonPersistent;
  return topic => typeof topic === 'string' && topic.startsWith(scheme) && compiled.test(topic.slice(scheme.length));
}

/**
 * Whether a topic is allowed by an area's patterns; an area without patterns allows every topic.
 * @param {string[]} patterns the area's globs
 * @param {string} topic full topic name
 * @returns {boolean} true when it matches one pattern, or there are none
 */
function areaAllows(patterns, topic) {
  if (!patterns || patterns.length === 0) return true;
  return patterns.some(p => areaPatternMatcher(p)(topic));
}

/**
 * Characters each kind of name may contain, as the APIs check them: Pulsar names in Catapulsar (topics,
 * subscriptions), Cataponte names (bridges, their subscriptions, areas) and area patterns.
 */
const NAME_RULES = {
  pulsar: { allowed: /[A-Za-z0-9._=:-]/, first: /[A-Za-z0-9_]/ },
  cataponte: { allowed: /[A-Za-z0-9._-]/, first: /[A-Za-z0-9]/ },
  pattern: { allowed: /[A-Za-z0-9._=:*/\n-]/, first: null, dropSpaces: true },
};

/**
 * Replaces the characters a name cannot have (spaces included) with "-", and drops those it cannot start with.
 * In area patterns spaces are dropped instead.
 * @param {string} text typed or pasted text
 * @param {{allowed: RegExp, first: ?RegExp}} rule the name's rule
 * @param {boolean} atStart whether the text goes at the start of the field
 * @returns {string} the text to insert
 */
function cleanNameText(text, rule, atStart) {
  let input = text.replace(/\r\n?/g, '\n');
  if (rule.dropSpaces) input = input.replace(/[ \t]/g, '');   // a space around a pattern is a slip, not a "-"
  let clean = [...input].map(c => (rule.allowed.test(c) ? c : '-')).join('');
  if (rule.first && atStart) {
    while (clean && !rule.first.test(clean[0])) clean = clean.slice(1);
  }
  return clean;
}

/*
 * x-name-chars="pulsar|cataponte|pattern": as you type or paste, characters the name cannot have become "-"
 * (a space too), and a first character it cannot start with is not written. The API still validates.
 */
document.addEventListener('alpine:init', () => {
  window.Alpine.directive('name-chars', (el, { expression }) => {
    const rule = NAME_RULES[expression] ?? NAME_RULES.pulsar;
    el.addEventListener('beforeinput', e => {
      if (e.isComposing || !e.inputType?.startsWith('insert')) return;
      if (e.inputType === 'insertLineBreak' || e.inputType === 'insertParagraph') {
        if (expression !== 'pattern') e.preventDefault();
        return;
      }
      const text = e.data ?? e.dataTransfer?.getData('text/plain') ?? '';
      const start = el.selectionStart ?? el.value.length, end = el.selectionEnd ?? start;
      const atStart = start === 0 || (expression === 'pattern' && el.value[start - 1] === '\n');
      const clean = cleanNameText(text, rule, atStart);
      if (clean === text) return;
      e.preventDefault();
      el.setRangeText(clean, start, end, 'end');
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });
  });
});

/** Reads the sidebar nodes left open in this browser. */
function storedTreeOpen() {
  try {
    const keys = JSON.parse(localStorage.getItem(TREE_OPEN_KEY) || '[]');
    return Array.isArray(keys) ? Object.fromEntries(keys.filter(k => typeof k === 'string').map(k => [k, true])) : {};
  } catch {
    return {};
  }
}

/** Reads the per-browser auto-refresh switch; on unless explicitly turned off. */
function autoRefreshStored() {
  try { return localStorage.getItem(AUTO_REFRESH_KEY) !== 'off'; } catch { return true; }
}

/**
 * Extracts a readable message from an error body: RFC 9457 Problem Details (`detail`/`title`),
 * an `{ error }` / `{ message }` object (e.g. Spring's default error body), or plain text.
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
    // "Collect now": a forced collection of the overview's scope by Catametricas (audited there).
    force: { busy: false },
    // Live mode (namespace overviews only): forced collection every 5 s while this browser tab is visible,
    // off by itself after 10 minutes or when the view changes.
    live: { on: false, scope: '', key: '', endsAt: 0, lastRunAt: 0, paused: false, lastError: '', timer: null,
            now: 0 },
    // Catametricas collection interval, edited in ⚙️ Settings (catapulsar_settings, audited).
    collect: { seconds: 60, defaultSeconds: 60, min: 15, max: 600, custom: false, draft: 60 },
    // Catametricas URL: where the overviews read their statistics (ADR 0013)
    catametricas: { url: '', defaultUrl: '', custom: false, draft: '', saving: false, healthState: null, healthMsg: '' },
    // Auto-refresh: `on` is per browser; `seconds` is shared by every user (catapulsar_settings, audited).
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
      tab: 'connections',   // connections | refresh
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
      areas: [],         // bridge areas (ADR 0014), with bridgeCount
      tab: 'system',     // open tab: 'system', 'general' or an area id
      areaForm: null,    // null=closed; { id, name, description, patternsText, version }
      areaSaving: false,
      areaDelete: null,  // null=closed; { area, choice: 'delete'|'move', to: '' }
      highlight: null,   // id of the bridge card just opened from a topic view
    },
    subConsumersOpen: {},  // subscription name → consumer list shown (topic view)
    // Sidebar: nodes left open (remembered per browser) and the lists of the open nodes that are not selected.
    tree: { open: storedTreeOpen(), namespaces: {}, topics: {} },

    // ── Audit trail (Cataudit, ADR 0009) ───────────────────────────────────
    auditView: false,
    audit: {
      items: [], total: 0, page: 0, loading: false, error: '', expanded: null,
      settings: { url: '', defaultUrl: '', custom: false, draft: '', saving: false, healthState: null, healthMsg: '' },
      facets: { apps: [], entities: [], actions: [] },
      filters: { app: '', entity: '', action: '', subject: '', actor: '',
                 period: storedChoice('auditPeriod', '24h', Object.keys(AUDIT_PERIODS)) },
    },

    // ── Init ───────────────────────────────────────────────────────────────
    async init() {
      await this.loadRefreshInterval();
      this.scheduleRefresh();
      await this.loadTenants();
      this.loadOpenTreeNodes();
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
     * subscription list, in the Bridges panel the bridge list, status and traffic counts, and in the Audit
     * panel the current page. Backlog
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
        } else if (this.auditView) {
          work.push(this.loadAudit(true));
        } else if (this.sel.topic && this.tab === 'stats' && this.stats) {
          work.push(this.refreshStats());
          if (this.auto.tick % INTERVAL_RELOAD_TICKS === 0) work.push(this.refreshBridgeList());
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
     * @returns {Promise<boolean>} whether it was saved
     */
    async saveRefreshInterval(method = 'PUT') {
      const a = this.auto;
      a.saving = true;
      try {
        const options = { method };
        if (method === 'PUT') options.body = JSON.stringify({ seconds: Number(a.draft) });
        const r = await this.api('/api/v1/settings/refresh-interval', options);
        Object.assign(a, { seconds: r.seconds, draft: r.seconds, custom: r.custom, menuOpen: false });
        this.scheduleRefresh();
        this.notify(`Auto-refresh every ${r.seconds} s for every user`);
        return true;
      } catch (e) {
        this.notify('Refresh interval not saved: ' + e.message, 'error');
        return false;
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

    /**
     * Tells whether an overview row is Catametricas' own task topic. It is collected by one of its own tasks,
     * which is not acknowledged until the collection ends, so its backlog always includes that task.
     * @param {{name: string}} row overview row
     * @returns {boolean} true for catacumbas69/catametricas/collect-tasks
     */
    isCollectTasksRow(row) {
      return this.overviewScope() === 'namespace' && this.sel.tenant === 'catacumbas69'
        && this.sel.namespace === 'catametricas' && row.name === 'collect-tasks';
    },

    /** @returns {string} identity of the current overview (scope + period), to drop late answers */
    overviewKey() {
      return [this.sel.tenant ?? '', this.sel.namespace ?? '', this.overview.period].join('|');
    },

    /**
     * Loads the overview of the current selection from Catametricas' statistics (through Catapulsar).
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

    // ── Collect now and live mode ──────────────────────────────────────────
    /** @returns {string} query string of the overview's scope for a forced collection */
    scopeQuery() {
      const params = new URLSearchParams();
      if (this.sel.tenant) params.set('tenant', this.sel.tenant);
      if (this.sel.namespace) params.set('namespace', this.sel.namespace);
      const query = params.toString();
      return query ? '?' + query : '';
    },

    /**
     * Has Catametricas collect the overview's scope now, then reloads the overview. A namespace is collected at
     * most once every 5 s whoever asks; Catametricas records each request in the audit log.
     * @param {boolean} [fromLive] true for live mode: no toast, errors shown in the live banner
     */
    async collectNow(fromLive = false) {
      if (this.force.busy) return;
      this.force.busy = true;
      try {
        const r = await this.api('/api/v1/overview/collect' + this.scopeQuery(), { method: 'POST' });
        await this.loadOverview(true);
        const failed = r.failed?.length ? ` · not collected: ${r.failed.join(', ')}` : '';
        if (fromLive) {
          this.live.lastError = failed ? failed.slice(3) : '';
        } else {
          const shared = r.coalesced ? ` (${r.coalesced} just collected by another request)` : '';
          this.notify(`Collected ${r.namespaces.length} namespace(s) in ${r.durationMillis} ms${shared}${failed}`,
            failed ? 'error' : 'success');
        }
      } catch (e) {
        if (fromLive) this.live.lastError = e.message;
        else this.notify('Collect now failed: ' + e.message, 'error');
      } finally {
        this.force.busy = false;
      }
    },

    /** Turns live mode on for the current namespace overview. */
    startLive() {
      if (this.overviewScope() !== 'namespace' || this.live.on) return;
      const now = Date.now();
      Object.assign(this.live, { on: true, scope: `${this.sel.tenant}/${this.sel.namespace}`,
        key: this.overviewKey(), endsAt: now + LIVE_MAX_MS, lastRunAt: 0, paused: false, lastError: '', now });
      this.live.timer = setInterval(() => this.liveTick(), 1000);
      this.liveTick();
    },

    /**
     * Turns live mode off.
     * @param {string} [reason] shown as a toast
     */
    stopLive(reason) {
      if (!this.live.on) return;
      clearInterval(this.live.timer);
      Object.assign(this.live, { on: false, timer: null, paused: false });
      if (reason) this.notify(reason);
    },

    /** Runs every second while live: applies the three limits and collects every 5 s. */
    liveTick() {
      const now = Date.now();
      this.live.now = now;
      if (this.bridgesView || this.auditView || this.sel.topic || this.overviewScope() !== 'namespace'
          || this.overviewKey().split('|').slice(0, 2).join('|') !== this.live.key.split('|').slice(0, 2).join('|')) {
        this.stopLive(`Live mode off: you left ${this.live.scope}`);
        return;
      }
      if (now >= this.live.endsAt) {
        this.stopLive('Live mode off after 10 minutes');
        return;
      }
      this.live.paused = document.hidden;
      if (!this.live.paused && now - this.live.lastRunAt >= LIVE_EVERY_MS && !this.force.busy) {
        this.live.lastRunAt = now;
        this.collectNow(true);
      }
    },

    /** @returns {string} time left in live mode, m:ss */
    liveRemaining() {
      const left = Math.max(0, Math.ceil((this.live.endsAt - this.live.now) / 1000));
      return `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
    },

    /** Shows the whole-cluster overview (logo and first breadcrumb). */
    showCluster() {
      this.closePanels();
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
        if (namespaces && this.sel.tenant === tenant) {
          this.replaceIfChanged('namespaces', namespaces);
          this.tree.namespaces[tenant] = this.namespaces;
        }
        if (topics && this.sel.tenant === tenant && this.sel.namespace === namespace) {
          this.replaceIfChanged('topics', topics);
          this.tree.topics[`${tenant}/${namespace}`] = this.topics;
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
    /**
     * Opens the Settings dialog on a tab and loads the current values.
     * @param {'connections'|'refresh'} [tab] tab to show first
     */
    async openSettings(tab = 'connections') {
      this.settings.tab = tab;
      this.settings.open = true;
      this.auto.draft = this.auto.seconds;
      this.bridges.settings.healthState = null;
      this.audit.settings.healthState = null;
      this.catametricas.healthState = null;
      await Promise.all([this.loadCataponteSettings(), this.loadCatauditSettings(), this.loadCatametricasSettings(),
                         this.loadCollectInterval()]);
      this.bridges.settings.draft = this.bridges.settings.url;
      this.audit.settings.draft = this.audit.settings.url;
      this.catametricas.draft = this.catametricas.url;
      this.collect.draft = this.collect.seconds;
    },

    /**
     * Saves the Pulsar label (this browser) and whatever changed on the server (Cataponte, Cataudit and
     * Catametricas URLs, Catametricas interval; all audited). The dialog stays open if a server change is refused.
     */
    async saveSettings() {
      try { localStorage.setItem('pulsarUrl', this.settings.pulsarUrl); } catch { /* label not remembered */ }
      const s = this.bridges.settings;
      let changed = false;
      if (s.draft && s.draft !== s.url) {
        if (!await this.changeCataponteUrl('PUT', { url: s.draft })) return;
        changed = true;
      }
      const a = this.audit.settings;
      if (a.draft && a.draft !== a.url) {
        if (!await this.changeCatauditUrl('PUT', { url: a.draft })) return;
        changed = true;
      }
      const m = this.catametricas;
      if (m.draft && m.draft !== m.url) {
        if (!await this.changeCatametricasUrl('PUT', { url: m.draft })) return;
        changed = true;
      }
      if (Number(this.collect.draft) !== this.collect.seconds) {
        if (!await this.saveCollectInterval('PUT')) return;
        changed = true;
      }
      if (Number(this.auto.draft) !== this.auto.seconds) {
        if (!await this.saveRefreshInterval('PUT')) return;
        changed = true;
      }
      if (!changed) this.notify('Settings saved');
      this.settings.open = false;
    },

    /** Loads Catametricas' collection interval (stored in catapulsar_settings). */
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
        s.healthMsg = `${s.url} · ${status.activeCount ?? 0} bridge(s) running`;
      } catch (e) {
        s.healthState = 'error';
        s.healthMsg = e.message;
      }
    },

    /** Asks Catametricas, through Catapulsar, for the last hour of the cluster (save first to test a new URL). */
    async checkCatametricas() {
      const s = this.catametricas;
      s.healthState = 'checking'; s.healthMsg = '';
      try {
        const overview = await this.api('/api/v1/overview?period=1h');
        const age = overview.collectedAt
          ? `last collection ${Math.max(0, Math.round((Date.now() - Date.parse(overview.collectedAt)) / 1000))} s ago`
          : 'nothing collected yet';
        s.healthState = 'ok';
        s.healthMsg = `${s.url} · ${overview.rows.length} tenant(s), ${age}`;
      } catch (e) {
        s.healthState = 'error';
        s.healthMsg = e.message;
      }
    },

    // ── Catametricas URL (stored in MongoDB, audited, published on the settings topic) ──
    async loadCatametricasSettings() {
      try {
        Object.assign(this.catametricas, await this.api('/api/v1/settings/catametricas'));
      } catch (e) { this.notify('Settings: ' + e.message, 'error'); }
    },

    async resetCatametricasUrl() {
      if (!confirm(`Go back to the default Catametricas URL ${this.catametricas.defaultUrl}?`)) return;
      if (await this.changeCatametricasUrl('DELETE')) this.catametricas.draft = this.catametricas.url;
    },

    /**
     * Saves or resets the Catametricas URL, then reloads the overview from the new address.
     * @param {'PUT'|'DELETE'} method PUT saves `body.url`, DELETE goes back to the default
     * @param {{url: string}} [body] new URL
     * @returns {Promise<boolean>} whether the change was saved
     */
    async changeCatametricasUrl(method, body) {
      const s = this.catametricas;
      s.saving = true;
      try {
        const options = { method };
        if (body) options.body = JSON.stringify(body);
        Object.assign(s, await this.api('/api/v1/settings/catametricas', options));
        s.healthState = null;
        this.notify(`Catametricas URL set to ${s.url}`);
        if (!this.sel.topic) await this.loadOverview();
        return true;
      } catch (e) {
        this.notify('Catametricas URL not saved: ' + e.message, 'error');
        return false;
      } finally {
        s.saving = false;
      }
    },

    /** Calls Cataudit through Catapulsar at the URL in use (save first to test a new one). */
    async checkCataudit() {
      const s = this.audit.settings;
      s.healthState = 'checking'; s.healthMsg = '';
      try {
        const facets = await this.api(`${AUDIT_API}/facets`);
        s.healthState = 'ok';
        s.healthMsg = `${s.url} · apps: ${(facets.apps || []).join(', ') || 'none yet'}`;
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
      this.closePanels();
      this.setTreeOpen(t, true);
      this.sel = { tenant: t, namespace: null, topic: null };
      this.loadOverview();
      this.namespaces = []; this.topics = [];
      this.stats = null; this.subscriptions = [];
      this.loading.namespaces = true;
      try {
        this.namespaces = await this.api(`/api/v1/namespaces/${t}`);
        this.tree.namespaces = { ...this.tree.namespaces, [t]: this.namespaces };
      }
      catch (e) { this.notify('Error loading namespaces: ' + e.message, 'error'); }
      finally { this.loading.namespaces = false; }
    },

    async selectNamespace(ns) {
      this.closePanels();
      this.setTreeOpen(`${this.sel.tenant}/${ns}`, true);
      this.sel = { ...this.sel, namespace: ns, topic: null };
      this.loadOverview();
      this.topics = []; this.stats = null; this.subscriptions = [];
      this.loading.topics = true;
      try {
        this.topics = await this.api(`/api/v1/topics/${this.sel.tenant}/${ns}`);
        this.tree.topics = { ...this.tree.topics, [`${this.sel.tenant}/${ns}`]: this.topics };
      }
      catch (e) { this.notify('Error loading topics: ' + e.message, 'error'); }
      finally { this.loading.topics = false; }
    },

    // ── Sidebar tree: several nodes open at once ─────────────────────────────
    /**
     * @param {string} key tenant name or tenant/namespace
     * @returns {boolean} whether the node is open
     */
    isTreeOpen(key) {
      return Boolean(this.tree.open[key]);
    },

    /**
     * Opens or closes a node and remembers it in this browser.
     * @param {string} key tenant name or tenant/namespace
     * @param {boolean} open new state
     */
    setTreeOpen(key, open) {
      if (this.isTreeOpen(key) === open) return;
      const next = { ...this.tree.open };
      if (open) next[key] = true; else delete next[key];
      this.tree.open = next;
      try { localStorage.setItem(TREE_OPEN_KEY, JSON.stringify(Object.keys(next))); } catch { /* not remembered */ }
    },

    /** Closes every node except the path to the selection. */
    collapseTree() {
      const keep = {};
      if (this.sel.tenant) keep[this.sel.tenant] = true;
      if (this.sel.tenant && this.sel.namespace) keep[`${this.sel.tenant}/${this.sel.namespace}`] = true;
      this.tree.open = keep;
      try { localStorage.setItem(TREE_OPEN_KEY, JSON.stringify(Object.keys(keep))); } catch { /* not remembered */ }
    },

    /** Loads the lists of the nodes left open last time (after a reload); failures leave them empty. */
    loadOpenTreeNodes() {
      Object.keys(this.tree.open).forEach(key => {
        const [tenant, ns] = key.split('/');
        if (!this.tenants.includes(tenant)) return;
        if (ns) this.loadTreeTopics(tenant, ns); else this.loadTreeNamespaces(tenant);
      });
    },

    /** @param {string} tenant tenant whose namespaces to load into the tree */
    async loadTreeNamespaces(tenant) {
      try {
        const list = await this.api(`/api/v1/namespaces/${tenant}`);
        this.tree.namespaces = { ...this.tree.namespaces, [tenant]: list };
      } catch { /* shown empty */ }
    },

    /**
     * @param {string} tenant tenant
     * @param {string} ns namespace whose topics to load into the tree
     */
    async loadTreeTopics(tenant, ns) {
      try {
        const list = await this.api(`/api/v1/topics/${tenant}/${ns}`);
        this.tree.topics = { ...this.tree.topics, [`${tenant}/${ns}`]: list };
      } catch { /* shown empty */ }
    },

    /**
     * @param {string} tenant tenant
     * @returns {string[]} its namespaces: the live list when selected, else the last one loaded
     */
    treeNamespaces(tenant) {
      return tenant === this.sel.tenant ? this.namespaces : (this.tree.namespaces[tenant] ?? []);
    },

    /**
     * @param {string} tenant tenant
     * @param {string} ns namespace
     * @returns {string[]} its topics: the live list when selected, else the last one loaded
     */
    treeTopics(tenant, ns) {
      return tenant === this.sel.tenant && ns === this.sel.namespace
        ? this.topics : (this.tree.topics[`${tenant}/${ns}`] ?? []);
    },

    /**
     * Click on a tenant: selects it (and opens it); clicking the selected tenant opens or closes it.
     * @param {string} tenant tenant
     */
    clickTreeTenant(tenant) {
      if (this.sel.tenant === tenant && !this.sel.namespace && !this.bridgesView && !this.auditView) {
        this.setTreeOpen(tenant, !this.isTreeOpen(tenant));
        return;
      }
      this.selectTenant(tenant);
    },

    /**
     * Click on a namespace: selects it (and opens it); clicking the selected namespace opens or closes it.
     * @param {string} tenant its tenant
     * @param {string} ns namespace
     */
    async clickTreeNamespace(tenant, ns) {
      const key = `${tenant}/${ns}`;
      if (this.sel.tenant === tenant && this.sel.namespace === ns && !this.sel.topic
          && !this.bridgesView && !this.auditView) {
        this.setTreeOpen(key, !this.isTreeOpen(key));
        return;
      }
      if (this.sel.tenant !== tenant) await this.selectTenant(tenant);
      await this.selectNamespace(ns);
    },

    /**
     * Click on a topic of any open namespace.
     * @param {string} tenant its tenant
     * @param {string} ns its namespace
     * @param {string} topic topic
     */
    async clickTreeTopic(tenant, ns, topic) {
      if (this.sel.tenant !== tenant) await this.selectTenant(tenant);
      if (this.sel.namespace !== ns) await this.selectNamespace(ns);
      await this.selectTopic(topic);
    },

    /**
     * Deletes a topic of any open namespace.
     * @param {string} tenant its tenant
     * @param {string} ns its namespace
     * @param {string} topic topic
     */
    async deleteTreeTopic(tenant, ns, topic) {
      if (tenant === this.sel.tenant && ns === this.sel.namespace) return this.deleteTopic(topic);
      if (!confirm(`Delete topic "${topic}"?\nAll messages and subscriptions will be removed.`)) return;
      try {
        await this.api(`/api/v1/topics/${tenant}/${ns}/${topic}`, { method: 'DELETE' });
        this.notify(`Topic "${topic}" deleted`);
        await this.loadTreeTopics(tenant, ns);
      } catch (e) { this.notify('Error: ' + e.message, 'error'); }
    },

    async selectTopic(t) {
      this.closePanels();   // leaving the Bridges or Audit panel
      this.sel = { ...this.sel, topic: t };
      this.tab = 'stats'; this.stats = null;
      this.produce = { payload: '', key: '', lastId: null };
      this.backlog = { sub: '', count: 10, messages: [], queried: false };
      this.subConsumersOpen = {};
      await this.loadStats();
      await this.loadSubs();
      this.refreshBridgeList();   // which subscriptions belong to bridges; ignored when Cataponte is down
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

    /** Leaves the Bridges or Audit panel, e.g. when a tenant, namespace or topic is selected. */
    closePanels() {
      if (this.bridgesView) this.closeBridges();
      if (this.auditView) this.closeAudit();
    },

    // ── Audit trail ────────────────────────────────────────────────────────
    openAudit() {
      if (this.bridgesView) this.closeBridges();
      this.auditView = true;
      this.loadCatauditSettings();
      this.audit.page = 0;
      this.audit.expanded = null;
      this.loadAuditFacets();
      this.loadAudit();
    },

    // ── Cataudit URL (stored in MongoDB, audited) ───────────────────────────
    async loadCatauditSettings() {
      try {
        Object.assign(this.audit.settings, await this.api('/api/v1/settings/cataudit'));
      } catch (e) { this.notify('Settings: ' + e.message, 'error'); }
    },

    async resetCatauditUrl() {
      if (!confirm(`Go back to the default Cataudit URL ${this.audit.settings.defaultUrl}?`)) return;
      if (await this.changeCatauditUrl('DELETE')) this.audit.settings.draft = this.audit.settings.url;
    },

    /**
     * Saves or resets the Cataudit URL, then reloads the audit trail from the new address.
     * @param {'PUT'|'DELETE'} method PUT saves `body.url`, DELETE goes back to the default
     * @param {{url: string}} [body] new URL
     * @returns {Promise<boolean>} whether the change was saved
     */
    async changeCatauditUrl(method, body) {
      const s = this.audit.settings;
      s.saving = true;
      try {
        const options = { method };
        if (body) options.body = JSON.stringify(body);
        Object.assign(s, await this.api('/api/v1/settings/cataudit', options));
        s.healthState = null;
        this.notify(`Cataudit URL set to ${s.url}`);
        if (this.auditView) await Promise.all([this.loadAuditFacets(), this.loadAudit()]);
        return true;
      } catch (e) {
        this.notify('Cataudit URL not saved: ' + e.message, 'error');
        return false;
      } finally {
        s.saving = false;
      }
    },

    closeAudit() {
      this.auditView = false;
      this.audit.expanded = null;
    },

    /** Loads the apps, entities and actions in use for the filter lists; failures are ignored. */
    async loadAuditFacets() {
      try { this.audit.facets = await this.api(`${AUDIT_API}/facets`); } catch { /* lists stay as they are */ }
    },

    /**
     * Query string of the current filters and page.
     * @returns {string} e.g. `app=catapulsar&from=…&page=0&size=50`
     */
    auditQuery() {
      const f = this.audit.filters;
      const q = new URLSearchParams();
      for (const k of ['app', 'entity', 'action', 'actor']) if (f[k]) q.set(k, f[k]);
      if (f.subject.trim()) q.set('subject', f.subject.trim());
      const back = AUDIT_PERIODS[f.period];
      if (back) q.set('from', new Date(Date.now() - back).toISOString());
      q.set('page', String(this.audit.page));
      q.set('size', String(AUDIT_PAGE_SIZE));
      return q.toString();
    },

    /**
     * Loads the current page. Silent loads (auto-refresh) show no spinner and keep the list on errors.
     * @param {boolean} silent true for auto-refresh
     */
    async loadAudit(silent = false) {
      const a = this.audit;
      const key = JSON.stringify([a.filters, a.page]);
      if (!silent) a.loading = true;
      try {
        const r = await this.api(`${AUDIT_API}?${this.auditQuery()}`);
        if (key !== JSON.stringify([a.filters, a.page])) return;   // filters or page changed meanwhile
        if (JSON.stringify(r.items) !== JSON.stringify(a.items)) a.items = r.items;
        a.total = r.total;
        a.error = '';
      } catch (e) {
        if (!silent) a.error = e.message;
      } finally {
        if (!silent) a.loading = false;
      }
    },

    /** Applies the filters from the first page and remembers the period in this browser. */
    applyAuditFilters() {
      this.audit.page = 0;
      this.audit.expanded = null;
      try { localStorage.setItem('auditPeriod', this.audit.filters.period); } catch { /* not persisted */ }
      this.loadAudit();
    },

    clearAuditFilters() {
      Object.assign(this.audit.filters, { app: '', entity: '', action: '', subject: '', actor: '' });
      this.applyAuditFilters();
    },

    /**
     * Shows the entries about one subject (click on a subject in the list).
     * @param {string} subject topic, bridge id, setting key…
     */
    filterAuditSubject(subject) {
      this.audit.filters.subject = subject;
      this.applyAuditFilters();
    },

    /** @returns {number} number of pages for the current filters */
    auditPages() {
      return Math.max(1, Math.ceil(this.audit.total / AUDIT_PAGE_SIZE));
    },

    /**
     * Moves to another page.
     * @param {number} delta -1 for newer, +1 for older
     */
    auditGo(delta) {
      const page = this.audit.page + delta;
      if (page < 0 || page >= this.auditPages()) return;
      this.audit.page = page;
      this.audit.expanded = null;
      this.loadAudit();
    },

    /**
     * @param {string} id entry id
     */
    toggleAuditEntry(id) {
      this.audit.expanded = this.audit.expanded === id ? null : id;
    },

    /**
     * @param {string} iso ISO-8601 instant
     * @returns {string} local date and time, to the second
     */
    auditTime(iso) {
      if (!iso) return '';
      const d = new Date(iso);
      return d.toLocaleDateString() + ' ' + d.toLocaleTimeString();
    },

    /**
     * Rows of a before/after/details map, sorted by key.
     * @param {Object<string, string>|null} values the map
     * @returns {Array<[string, string]>} key/value pairs
     */
    auditPairs(values) {
      return values ? Object.entries(values).sort(([a], [b]) => a.localeCompare(b)) : [];
    },

    /**
     * Keys of before and after together, for the change table.
     * @param {Object} entry audit entry
     * @returns {string[]} sorted keys
     */
    auditChangedKeys(entry) {
      const keys = new Set([...Object.keys(entry.before || {}), ...Object.keys(entry.after || {})]);
      return [...keys].sort();
    },

    // ── Bridges ────────────────────────────────────────────────────────────
    openBridges() {
      if (this.auditView) this.closeAudit();
      this.bridgesView = true;
      this.loadCataponteSettings();
      this.loadBridges();
      this.loadBridgeStatus();
      this.loadAreas(true);
    },

    /** @returns {number} bridges of the open tab whose worker is relaying messages right now */
    runningBridges() {
      return this.tabBridges().filter(b => this.bridges.status[b.id]?.running).length;
    },

    // ── Bridge areas (ADR 0014) ──────────────────────────────────────────────
    /**
     * Loads the areas; the first time, reopens the last tab used (System when it is gone).
     * @param {boolean} [restoreTab] pick the remembered tab after loading
     */
    async loadAreas(restoreTab = false) {
      try {
        const areas = await this.bridgesApi(AREAS_API);
        if (JSON.stringify(areas) !== JSON.stringify(this.bridges.areas)) this.bridges.areas = areas;
      } catch { /* keep the current areas; Cataponte errors show with the bridges */ }
      if (restoreTab) {
        let stored = null;
        try { stored = localStorage.getItem(BRIDGES_TAB_KEY); } catch { /* not remembered */ }
        this.bridges.tab = this.bridgeTabExists(stored) ? stored : 'system';
      } else if (!this.bridgeTabExists(this.bridges.tab)) {
        this.bridges.tab = 'system';
      }
    },

    /**
     * @param {?string} key tab key
     * @returns {boolean} whether the tab exists
     */
    bridgeTabExists(key) {
      return key === 'system' || key === 'general' || this.bridges.areas.some(a => a.id === key);
    },

    /** @returns {{key: string, label: string, count: number}[]} System, Bridges, then one tab per area */
    bridgeTabs() {
      const list = this.bridges.list;
      return [
        { key: 'system', label: 'System', count: list.filter(b => b.system).length },
        { key: 'general', label: 'Bridges', count: list.filter(b => this.inGeneralGroup(b)).length },
        ...this.bridges.areas.map(a => ({ key: a.id, label: a.name, count: list.filter(b => b.areaId === a.id).length })),
      ];
    },

    /**
     * Bridges without an area, or whose area no longer exists, are in the general group.
     * @param {object} bridge the bridge
     * @returns {boolean} true when it belongs to the Bridges tab
     */
    inGeneralGroup(bridge) {
      return !bridge.system && (!bridge.areaId || !this.bridges.areas.some(a => a.id === bridge.areaId));
    },

    /** @returns {object[]} the bridges of the open tab */
    tabBridges() {
      const tab = this.bridges.tab;
      if (tab === 'system') return this.bridges.list.filter(b => b.system);
      if (tab === 'general') return this.bridges.list.filter(b => this.inGeneralGroup(b));
      return this.bridges.list.filter(b => b.areaId === tab);
    },

    /**
     * Opens a tab and remembers it in this browser.
     * @param {string} key 'system', 'general' or an area id
     */
    selectBridgeTab(key) {
      this.bridges.tab = key;
      try { localStorage.setItem(BRIDGES_TAB_KEY, key); } catch { /* not remembered */ }
    },

    /** @returns {?object} the area of the open tab, or null on System and Bridges */
    currentArea() {
      return this.bridges.areas.find(a => a.id === this.bridges.tab) ?? null;
    },

    /**
     * @param {?string} id area id
     * @returns {?object} the area, or null
     */
    areaById(id) {
      return id ? this.bridges.areas.find(a => a.id === id) ?? null : null;
    },

    openNewArea() {
      this.bridges.areaForm = { id: null, name: '', description: '', patternsText: '', version: null };
      if (this.bridges.topics.length === 0) this.loadAllTopics();
    },

    /** @param {object} area the area to edit */
    openEditArea(area) {
      this.bridges.areaForm = { id: area.id, name: area.name, description: area.description || '',
        patternsText: (area.patterns || []).join('\n'), version: area.version };
      if (this.bridges.topics.length === 0) this.loadAllTopics();
    },

    /** @returns {string[]} the patterns typed in the area form, one per line */
    areaFormPatterns() {
      return (this.bridges.areaForm?.patternsText || '').split('\n').map(p => p.trim()).filter(Boolean);
    },

    /** @returns {string[]} existing topics the area form's patterns match (for the preview) */
    areaFormMatches() {
      const patterns = this.areaFormPatterns();
      try {
        return this.bridges.topics.filter(t => areaAllows(patterns, t));
      } catch {
        return [];
      }
    },

    async saveArea() {
      const f = this.bridges.areaForm;
      const isNew = !f.id;
      this.bridges.areaSaving = true;
      try {
        const payload = { name: f.name.trim(), description: f.description, patterns: this.areaFormPatterns(),
          version: f.version ?? null };
        const saved = isNew
          ? await this.bridgesApi(AREAS_API, { method: 'POST', body: JSON.stringify(payload) })
          : await this.bridgesApi(`${AREAS_API}/${f.id}`, { method: 'PUT', body: JSON.stringify(payload) });
        this.bridges.areaForm = null;
        await this.loadAreas();
        await this.loadBridges();
        if (saved?.id) this.selectBridgeTab(saved.id);
        this.notify(isNew ? `Area "${payload.name}" created` : `Area "${payload.name}" updated`);
      } catch (e) {
        this.notify('Cataponte: ' + e.message, 'error');
      } finally {
        this.bridges.areaSaving = false;
      }
    },

    /** @param {object} area the area to delete; an empty one only needs a confirmation */
    askDeleteArea(area) {
      const count = this.bridges.list.filter(b => b.areaId === area.id).length;
      if (count === 0) {
        if (confirm(`Delete area "${area.name}"? It has no bridges.`)) this.deleteArea(area, null, null);
        return;
      }
      this.bridges.areaDelete = { area, count, choice: 'move', to: '' };
    },

    /** Applies the choice of the delete dialog. */
    confirmDeleteArea() {
      const d = this.bridges.areaDelete;
      if (d.choice === 'delete'
          && !confirm(`Delete area "${d.area.name}" and its ${d.count} bridge(s)? Their relays stop immediately,`
            + ' and their subscriptions are deleted about 30 s later with whatever is in their backlog.')) {
        return;
      }
      this.deleteArea(d.area, d.choice, d.choice === 'move' ? d.to : null);
    },

    /**
     * Deletes an area through Cataponte, which audits it and every bridge it deletes or moves.
     * @param {object} area the area
     * @param {?string} choice 'delete' or 'move' for an area with bridges
     * @param {?string} to area to move them to; empty for Bridges
     */
    async deleteArea(area, choice, to) {
      const query = new URLSearchParams();
      if (choice) query.set('bridges', choice);
      if (to) query.set('to', to);
      const suffix = query.toString() ? `?${query}` : '';
      this.bridges.areaSaving = true;
      try {
        await this.bridgesApi(`${AREAS_API}/${area.id}${suffix}`, { method: 'DELETE' });
        this.bridges.areaDelete = null;
        this.selectBridgeTab(choice === 'move' && to ? to : choice === 'move' ? 'general' : 'system');
        await this.loadAreas();
        await this.loadBridges();
        await this.loadBridgeStatus();
        this.notify(`Area "${area.name}" deleted`);
      } catch (e) {
        this.notify('Cataponte: ' + e.message, 'error');
      } finally {
        this.bridges.areaSaving = false;
      }
    },

    /** @returns {string[]} topics of the bridge form that its area's patterns do not allow */
    formOutsideTopics() {
      const f = this.bridges.form;
      const area = this.areaById(f?.areaId);
      if (!f || !area) return [];
      const topics = [f.source.topic, ...f.destinations.map(d => d.topic)].filter(Boolean);
      return topics.filter(t => !areaAllows(area.patterns, t));
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
      await this.loadAreas();
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

    /**
     * Skips the message an ordered Failover bridge is stuck on, after confirmation (Cataponte audits it).
     * @param {object} bridge the bridge
     */
    async skipStuckMessage(bridge) {
      const status = this.bridges.status[bridge.id];
      const id = status?.stuckMessageId;
      if (!id) return;
      if (!confirm(`Skip message ${id} of bridge "${bridge.name}"?\n\nThe destination refuses it: ${status.lastError || 'unknown error'}.`
          + `\nIt will not be relayed to ${this.bridgeDestTopics(bridge).join(', ')}; it stays in ${bridge.source?.topic} until`
          + ' its retention ends. A message sent in a batch is skipped with its whole batch.')) return;
      try {
        await this.bridgesApi(`${BRIDGES_API}/${bridge.id}/skip`, { method: 'POST', body: JSON.stringify({ messageId: id }) });
        this.notify(`Message ${id} skipped`);
        await this.loadBridgeStatus();
      } catch (e) {
        this.notify('Cataponte: ' + e.message, 'error');
      }
    },

    async deleteBridge(id) {
      const bridge = this.bridges.list.find(b => b.id === id);
      const warning = bridge ? await this.subscriptionWarning(bridge) : '';
      if (!confirm(`Delete bridge "${bridge?.name ?? id}"?\nThis will stop the relay immediately.${warning}`)) return;
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
        source: { topic: '', subscription: '', subscriptionType: 'Failover' },
        subAuto: true,   // the subscription follows the name (cataponte-<name>) until it is edited
        ordered: true,
        destinations: [],
        preserveKey: true, preserveProperties: true,
        areaId: this.currentArea()?.id ?? null
      };
      this.bridges.srcFilter = ''; this.bridges.dstFilter = '';
      this.loadAllTopics();
    },

    openEditBridge(bridge) {
      const f = JSON.parse(JSON.stringify(bridge));
      f.subAuto = false;
      f.originalSource = { topic: bridge.source?.topic, subscription: bridge.source?.subscription };
      f.destinations = this.bridgeDestTopics(f).map(topic => ({ topic }));
      delete f.destination;
      this.bridges.form = f;
      this.bridges.srcFilter = ''; this.bridges.dstFilter = '';
      this.loadAllTopics();
    },

    // Destination topics of a bridge.
    bridgeDestTopics(b) {
      return (b?.destinations || []).map(d => d.topic).filter(Boolean);
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
        && !this.orderedOnShared() && this.formOutsideTopics().length === 0);
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
      const area = this.areaById(this.bridges.form?.areaId);
      return this.bridges.topics.filter(t => !ex.includes(t) && (!f || t.toLowerCase().includes(f))
        && (!area || areaAllows(area.patterns, t)));
    },

    closeBridgeForm() {
      this.bridges.form = null;
    },

    /** Keeps the subscription name at cataponte-<bridge name> until the user edits it. */
    onBridgeNameInput() {
      const f = this.bridges.form;
      if (f?.subAuto) f.source.subscription = f.name ? `cataponte-${f.name}` : '';
    },

    async saveBridge() {
      const f = this.bridges.form;
      const isNew = !f.id;
      const o = f.originalSource;
      if (!isNew && o && (o.topic !== f.source.topic || o.subscription !== f.source.subscription)) {
        const old = this.bridges.list.find(b => b.id === f.id);
        const warning = old ? await this.subscriptionWarning(old) : '';
        if (!confirm(`Save bridge "${f.name}" with another source or subscription?${warning.replace('Its subscription', 'Its old subscription')}`)) return;
      }
      this.bridges.formSaving = true;
      try {
        const payload = {
          name: f.name, description: f.description, enabled: f.enabled,
          source: f.source, destinations: f.destinations,
          preserveKey: f.preserveKey, preserveProperties: f.preserveProperties,
          ordered: f.ordered, areaId: f.system ? null : (f.areaId || null), version: f.version ?? null
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
    /**
     * Formats a byte count; a negative or missing value (Pulsar's "not computed") is shown as "—".
     * @param {?number} b bytes
     * @returns {string} e.g. "1.5 KB"
     */
    fmtBytes(b) {
      if (b == null || b < 0) return '—';
      if (!b) return '0 B';
      const k = 1024, sizes = ['B','KB','MB','GB','TB'];
      const i = Math.floor(Math.log(b) / Math.log(k));
      return (b / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i];
    },
    fmtDate(ts) {
      if (!ts) return '—';
      return new Date(ts).toLocaleString('en-GB', { dateStyle:'short', timeStyle:'medium' });
    },
    /**
     * Tells whether a subscription is Pulsar's own (e.g. __compaction): it can be peeked but not changed.
     * @param {?string} name subscription name
     * @returns {boolean} true for names starting with "__"
     */
    isSystemSub(name) {
      return typeof name === 'string' && name.startsWith('__');
    },

    /** @returns {string} full name of the selected topic, e.g. persistent://home/media/events */
    currentTopicName() {
      const t = this.sel.topic || '';
      return t.includes('://') ? t : `persistent://${this.sel.tenant}/${this.sel.namespace}/${t}`;
    },

    /**
     * The bridge that reads the selected topic (or the partitioned topic it belongs to) with a subscription.
     * @param {string} name subscription name
     * @returns {?object} the bridge, or null
     */
    bridgeForSub(name) {
      const topic = this.currentTopicName();
      const base = topic.replace(/-partition-\d+$/, '');
      return this.bridges.list.find(b => b.source?.subscription === name
        && (b.source?.topic === topic || b.source?.topic === base)) ?? null;
    },

    /**
     * @param {object} bridge a bridge
     * @returns {string} the Bridges tab it is shown in
     */
    bridgeTabOf(bridge) {
      if (bridge.system) return 'system';
      return this.areaById(bridge.areaId) ? bridge.areaId : 'general';
    },

    /**
     * Opens the Bridges panel on the tab of a bridge and highlights its card.
     * @param {object} bridge the bridge
     */
    async openBridgeOf(bridge) {
      try { localStorage.setItem(BRIDGES_TAB_KEY, this.bridgeTabOf(bridge)); } catch { /* not remembered */ }
      this.bridges.highlight = bridge.id;
      this.openBridges();
      await this.loadAreas();
      this.selectBridgeTab(this.bridgeTabOf(bridge));
      setTimeout(() => document.getElementById(`bridge-card-${bridge.id}`)?.scrollIntoView({ block: 'center' }), 300);
      setTimeout(() => { if (this.bridges.highlight === bridge.id) this.bridges.highlight = null; }, 4000);
    },

    /** @param {string} name subscription whose consumer list is shown or hidden */
    toggleSubConsumers(name) {
      this.subConsumersOpen = { ...this.subConsumersOpen, [name]: !this.subConsumersOpen[name] };
    },

    /**
     * Short "who is connected" line for a subscription: the consumer names, or their addresses.
     * @param {object} stats subscription statistics
     * @returns {string} e.g. "cataponte@pod-1/fanout, 10.42.0.7:51234", or '' with no consumers
     */
    consumersLine(stats) {
      return (stats?.consumers ?? []).map(c => c.consumerName || c.address || '?').join(', ');
    },

    /**
     * Warning about the subscription of a bridge that is deleted or moved to another one (orch.md decision 60):
     * kept when another bridge uses it, otherwise deleted by Cataponte about 30 s later with its backlog.
     * @param {object} bridge the bridge as it is now
     * @returns {Promise<string>} the text to add to the confirmation
     */
    async subscriptionWarning(bridge) {
      const topic = bridge.source?.topic, sub = bridge.source?.subscription;
      if (!topic || !sub) return '';
      const other = this.bridges.list.find(b => b.id !== bridge.id && b.source?.topic === topic && b.source?.subscription === sub);
      if (other) return `\n\nIts subscription "${sub}" is kept: bridge "${other.name}" uses it too.`;
      let backlog = null;
      const m = topic.match(/^persistent:\/\/([^/]+)\/([^/]+)\/(.+)$/);
      if (m) {
        try {
          const stats = await this.api(`/api/v1/topics/${m[1]}/${m[2]}/${encodeURIComponent(m[3])}/stats`);
          backlog = stats?.subscriptions?.[sub]?.msgBacklog ?? 0;
        } catch { /* unknown */ }
      }
      const what = backlog == null ? 'with whatever is in its backlog (could not be read)'
        : backlog === 0 ? 'its backlog is empty'
        : `with the ${this.fmtNum(backlog)} message(s) still in its backlog; they will not reach the destinations`;
      return `\n\nIts subscription "${sub}" on ${topic} will also be deleted about 30 s later (${what}).`;
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
