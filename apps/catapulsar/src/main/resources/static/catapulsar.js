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

// ── Alerts (Catavigia, ADR 0015) ─────────────────────────────────────────
const ALERTS_API = '/api/v1/alerts';
const LIMITS_API = '/api/v1/limits/backlog';
/** The badge asks Catavigia this often, whatever the auto-refresh, while this browser tab is visible. */
const ALERT_BADGE_MS = 30000;
const ALERT_PAGE_SIZE = 50;
const ALERT_PERIODS = { '24h': 86400e3, '7d': 7 * 86400e3, '30d': 30 * 86400e3, all: null };
/** Every alert type, for the archive filter and the labels. */
const ALERT_TYPES = {
  poison: 'Poison messages in a DLQ',
  'bridge.failed': 'Bridge failed',
  'bridge.blocked': 'Bridge blocked (loop)',
  'collection.stale': 'Collection stale',
  backlog: 'Backlog above its limit',
  'consumer.paused': 'Consumer paused',
  'service.down': 'App down',
  'pulsar.broker.down': 'Pulsar broker down',
  'pulsar.bookie.down': 'Pulsar storage failing',
  'mongodb.down': "Catavigia's MongoDB down",
};
/** Settings → Alerts: label and unit of each threshold (names from GET /api/v1/settings/alerts). */
const ALERT_THRESHOLDS = {
  'backlog-limit': {
    label: 'Backlog limit, global default (messages)',
    unit: '',
    hint:
      'A subscription above this many waiting messages opens a backlog alert, unless it has its own limit ' +
      '(set on its row in the topic view).',
  },
  'collection-stale-intervals': {
    label: 'Collection stale after (intervals)',
    unit: '',
    hint: 'How many Catametricas collection intervals without a sample open collection.stale.',
  },
  'check-interval': {
    label: 'Check interval (seconds)',
    unit: 's',
    hint: 'How often Catavigia runs every check. Applies from the next round.',
  },
  debounce: {
    label: 'Debounce (rounds)',
    unit: '',
    hint: 'Rounds in a row that must see a problem to open its alert, and that must not see it to close it.',
  },
};
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
  try {
    const v = localStorage.getItem(key);
    return allowed.includes(v) ? v : fallback;
  } catch {
    return fallback;
  }
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
    if (body[i] === '*' && body[i + 1] === '*') {
      regex += '.*';
      i++;
    } else if (body[i] === '*') regex += '[^/]*';
    else regex += body[i].replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }
  const compiled = new RegExp(`^${regex}$`);
  const scheme = persistent ? 'persistent://' : nonPersistent;
  return (topic) => typeof topic === 'string' && topic.startsWith(scheme) && compiled.test(topic.slice(scheme.length));
}

/**
 * Whether a topic is allowed by an area's patterns; an area without patterns allows every topic.
 * @param {string[]} patterns the area's globs
 * @param {string} topic full topic name
 * @returns {boolean} true when it matches one pattern, or there are none
 */
function areaAllows(patterns, topic) {
  if (!patterns || patterns.length === 0) return true;
  return patterns.some((p) => areaPatternMatcher(p)(topic));
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
  if (rule.dropSpaces) input = input.replace(/[ \t]/g, ''); // a space around a pattern is a slip, not a "-"
  let clean = [...input].map((c) => (rule.allowed.test(c) ? c : '-')).join('');
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
    el.addEventListener('beforeinput', (e) => {
      if (e.isComposing || !e.inputType?.startsWith('insert')) return;
      if (e.inputType === 'insertLineBreak' || e.inputType === 'insertParagraph') {
        if (expression !== 'pattern') e.preventDefault();
        return;
      }
      const text = e.data ?? e.dataTransfer?.getData('text/plain') ?? '';
      const start = el.selectionStart ?? el.value.length,
        end = el.selectionEnd ?? start;
      const atStart = start === 0 || (expression === 'pattern' && el.value[start - 1] === '\n');
      const clean = cleanNameText(text, rule, atStart);
      if (clean === text) return;
      e.preventDefault();
      el.setRangeText(clean, start, end, 'end');
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });
  });
});

/**
 * Reads the sidebar nodes left open in this browser.
 * @returns {Object<string, boolean>} open node keys (tenant or tenant/namespace) → true; empty when nothing is stored
 */
function storedTreeOpen() {
  try {
    const keys = JSON.parse(localStorage.getItem(TREE_OPEN_KEY) || '[]');
    return Array.isArray(keys)
      ? Object.fromEntries(keys.filter((k) => typeof k === 'string').map((k) => [k, true]))
      : {};
  } catch {
    return {};
  }
}

/**
 * Reads the per-browser auto-refresh switch; on unless explicitly turned off.
 * @returns {boolean} whether auto-refresh is on in this browser
 */
function autoRefreshStored() {
  try {
    return localStorage.getItem(AUTO_REFRESH_KEY) !== 'off';
  } catch {
    return true;
  }
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

/* exported app */
/**
 * The Alpine component of the whole page (`<body x-data="app()">`): state and methods, grouped by feature.
 * @returns {object} the component
 */
function app() {
  return {
    // ── State ──────────────────────────────────────────────────────────────
    tenants: [],
    /** Phones (< 768 px): whether the sidebar drawer is open. */
    drawerOpen: false,
    namespaces: [],
    topics: [],
    sel: { tenant: null, namespace: null, topic: null },
    tab: 'stats',
    stats: null,
    // Cluster / tenant / namespace overview from Catametricas' statistics (ADR 0007).
    overview: {
      data: null,
      dataKey: null,
      loading: false,
      error: '',
      hover: null,
      period: storedChoice('overviewPeriod', '24h', OVERVIEW_PERIODS),
      metric: storedChoice('overviewMetric', 'in', Object.keys(OVERVIEW_METRICS)),
    },
    // "Collect now": a forced collection of the overview's scope by Catametricas (audited there).
    force: { busy: false },
    // Live mode (namespace overviews only): forced collection every 5 s while this browser tab is visible,
    // off by itself after 10 minutes or when the view changes.
    live: { on: false, scope: '', key: '', endsAt: 0, lastRunAt: 0, paused: false, lastError: '', timer: null, now: 0 },
    // Catametricas collection interval, edited in ⚙️ Settings (catapulsar_settings, audited).
    collect: { seconds: 60, defaultSeconds: 60, min: 15, max: 600, custom: false, draft: 60 },
    // Catametricas URL: where the overviews read their statistics (ADR 0013)
    catametricas: {
      url: '',
      defaultUrl: '',
      custom: false,
      draft: '',
      saving: false,
      healthState: null,
      healthMsg: '',
    },
    // Auto-refresh: `on` is per browser; `seconds` is shared by every user (catapulsar_settings, audited).
    auto: {
      on: autoRefreshStored(),
      seconds: 10,
      defaultSeconds: 10,
      min: 2,
      max: 300,
      custom: false,
      menuOpen: false,
      draft: 10,
      saving: false,
      tick: 0,
      busy: false,
      timer: null,
    },
    subscriptions: [],
    newTopic: '',
    newSub: '',
    produce: { payload: '', key: '', lastId: null },
    backlog: { sub: '', count: 10, messages: [], queried: false },
    toast: { msg: '', type: 'success' },
    loading: {
      tenants: false,
      namespaces: false,
      topics: false,
      stats: false,
      subs: false,
      produce: false,
      backlog: false,
      newSub: false,
    },

    settings: {
      open: false,
      tab: 'connections', // connections | refresh | alerts
      pulsarUrl: localStorage.getItem('pulsarUrl') || 'http://192.168.86.35:31080',
      healthState: null, // null | 'checking' | 'ok' | 'error'
      healthMsg: '',
    },
    modal: { type: null, message: null, editPayload: '', editKey: '', saving: false },

    // ── Cataponte / Bridges ─────────────────────────────────────────────────
    bridgesView: false,
    bridges: {
      list: [],
      status: {},
      traffic: {}, // bridgeId → { 'SOURCE topic' | 'DESTINATION topic' → all-time totals }
      settings: { url: '', defaultUrl: '', custom: false, draft: '', saving: false, healthState: null, healthMsg: '' },
      loading: false,
      formSaving: false,
      form: null, // null=closed; object=creating/editing
      topics: [], // all existing topics, full names (persistent://tenant/ns/topic)
      topicsLoading: false,
      srcFilter: '',
      dstFilter: '',
      areas: [], // bridge areas (ADR 0014), with bridgeCount
      tab: 'system', // open tab: 'system', 'general' or an area id
      areaForm: null, // null=closed; { id, name, description, patternsText, version }
      areaSaving: false,
      areaDelete: null, // null=closed; { area, choice: 'delete'|'move', to: '' }
      highlight: null, // id of the bridge card just opened from a topic view
    },
    subConsumersOpen: {}, // subscription name → consumer list shown (topic view)
    // Sidebar: nodes left open (remembered per browser) and the lists of the open nodes that are not selected.
    tree: { open: storedTreeOpen(), namespaces: {}, topics: {} },

    // ── Audit trail (Cataudit, ADR 0009) ───────────────────────────────────
    auditView: false,
    // ── Consumers and dead letters (ADR 0015) ─────────────────────────────
    consumersView: false,
    cons: {
      tab: 'consumers', // 'consumers' | 'deadletters'
      list: [],
      loading: false,
      error: '',
      dlqs: [], // { topic, from, backlog, missing }
      dlqLoading: false,
      open: null, // DLQ topic whose messages are shown
      messages: [],
      peeking: false,
      busy: false,
    },
    dlqSetting: { value: 10, defaultValue: 10, min: 2, max: 100, custom: false, draft: 10 },
    // ── Alerts (Catavigia, ADR 0015) ──────────────────────────────────────
    alertsView: false,
    alertBadge: { open: 0, unseen: 0, critical: 0, warning: 0, worst: null, error: '', loaded: false },
    alerts: {
      tab: 'open', // 'open' | 'archived'
      open: [],
      loading: false,
      error: '',
      expanded: null,
      busy: false,
      archive: {
        items: [],
        total: 0,
        page: 0,
        loading: false,
        error: '',
        expanded: null,
        filters: { type: '', subject: '', period: storedChoice('alertsPeriod', '7d', Object.keys(ALERT_PERIODS)) },
      },
      settings: { url: '', defaultUrl: '', custom: false, draft: '', saving: false, healthState: null, healthMsg: '' },
    },
    alertThresholds: [], // { name, value, defaultValue, min, max, custom, draft }
    // Backlog limits per subscription (Catavigia), for the Limit column of the topic view.
    subLimits: { global: null, byKey: {}, error: '', editing: null, draft: '', saving: false },
    // History of a subscription (Catametricas), shown under its row with 📈.
    subHistory: { open: {}, data: {}, loading: {}, error: {}, period: '24h', hover: null },
    audit: {
      items: [],
      total: 0,
      page: 0,
      loading: false,
      error: '',
      expanded: null,
      settings: { url: '', defaultUrl: '', custom: false, draft: '', saving: false, healthState: null, healthMsg: '' },
      facets: { apps: [], entities: [], actions: [] },
      filters: {
        app: '',
        entity: '',
        action: '',
        subject: '',
        actor: '',
        period: storedChoice('auditPeriod', '24h', Object.keys(AUDIT_PERIODS)),
      },
    },

    // ── Init ───────────────────────────────────────────────────────────────
    /**
     * Starts the page: alert badge, shared refresh interval and timer, tenants, the tree nodes left open and the overview. Alpine calls it once.
     */
    async init() {
      this.startAlertBadge();
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
        Object.assign(this.auto, {
          seconds: r.seconds,
          defaultSeconds: r.defaultSeconds,
          min: r.min,
          max: r.max,
          custom: r.custom,
        });
        if (changed) this.scheduleRefresh();
      } catch {
        /* keep the current interval */
      }
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
        } else if (this.alertsView) {
          work.push(this.alerts.tab === 'open' ? this.loadAlerts(true) : this.loadAlertArchive(true));
        } else if (this.consumersView) {
          work.push(this.loadConsumers(true));
          if (this.cons.tab === 'deadletters') work.push(this.loadDeadLetters(true));
        } else if (this.sel.topic && this.tab === 'stats' && this.stats) {
          work.push(this.refreshStats());
          if (this.auto.tick % INTERVAL_RELOAD_TICKS === 0) work.push(this.refreshBridgeList(), this.loadSubLimits());
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
      try {
        localStorage.setItem(AUTO_REFRESH_KEY, this.auto.on ? 'on' : 'off');
      } catch {
        /* not persisted */
      }
      if (this.auto.on) this.refreshVisibleView();
    },

    /**
     * Opens or closes the auto-refresh menu, with the current interval as the draft.
     */
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
    /**
     * Tells what the overview shows for the current selection.
     * @returns {'cluster'|'tenant'|'namespace'} the scope
     */
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
      return (
        this.overviewScope() === 'namespace' &&
        this.sel.tenant === 'catacumbas69' &&
        this.sel.namespace === 'catametricas' &&
        row.name === 'collect-tasks'
      );
    },

    /**
     * Identifies the current overview, so that answers arriving after the selection changed are dropped.
     * @returns {string} scope and period, e.g. "home|media|24h"
     */
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
        if (key !== this.overviewKey() || this.sel.topic) return; // selection changed meanwhile
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
    /**
     * Builds the query string of the overview's scope for a forced collection.
     * @returns {string} e.g. "?tenant=home&namespace=media", or "" for the whole cluster
     */
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
          this.notify(
            `Collected ${r.namespaces.length} namespace(s) in ${r.durationMillis} ms${shared}${failed}`,
            failed ? 'error' : 'success',
          );
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
      Object.assign(this.live, {
        on: true,
        scope: `${this.sel.tenant}/${this.sel.namespace}`,
        key: this.overviewKey(),
        endsAt: now + LIVE_MAX_MS,
        lastRunAt: 0,
        paused: false,
        lastError: '',
        now,
      });
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
      if (
        this.panelOpen() ||
        this.sel.topic ||
        this.overviewScope() !== 'namespace' ||
        this.overviewKey().split('|').slice(0, 2).join('|') !== this.live.key.split('|').slice(0, 2).join('|')
      ) {
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

    /**
     * Tells how long live mode keeps running.
     * @returns {string} time left, m:ss
     */
    liveRemaining() {
      const left = Math.max(0, Math.ceil((this.live.endsAt - this.live.now) / 1000));
      return `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
    },

    /** Shows the whole-cluster overview (logo and first breadcrumb). */
    showCluster() {
      this.closePanels();
      this.sel = { tenant: null, namespace: null, topic: null };
      this.namespaces = [];
      this.topics = [];
      this.stats = null;
      this.subscriptions = [];
      this.loadOverview();
    },

    /**
     * Changes the overview period and remembers it in this browser.
     * @param {string} period one of OVERVIEW_PERIODS
     */
    setOverviewPeriod(period) {
      this.overview.period = period;
      try {
        localStorage.setItem('overviewPeriod', period);
      } catch {
        /* not remembered */
      }
      this.loadOverview();
    },

    /**
     * Changes what the overview charts show and remembers it in this browser.
     * @param {'in'|'out'|'backlog'} metric published, delivered or backlog
     */
    setOverviewMetric(metric) {
      this.overview.metric = metric;
      try {
        localStorage.setItem('overviewMetric', metric);
      } catch {
        /* not remembered */
      }
    },

    /**
     * Gives the heading of the overview.
     * @returns {string} e.g. "Cluster", "Tenant home", "Namespace home/media"
     */
    overviewTitle() {
      const s = this.overviewScope();
      if (s === 'cluster') return 'Cluster';
      return s === 'tenant' ? `Tenant ${this.sel.tenant}` : `Namespace ${this.sel.tenant}/${this.sel.namespace}`;
    },

    /**
     * Names what the rows of the overview table are.
     * @returns {string} Tenants, Namespaces or Topics
     */
    overviewRowLabel() {
      return { cluster: 'Tenants', tenant: 'Namespaces', namespace: 'Topics' }[this.overviewScope()];
    },

    /**
     * Describes how fresh the statistics are, or why there are none.
     * @returns {string} e.g. "Collected by Catametricas 4 min ago · every 30 s"
     */
    overviewFreshness() {
      const d = this.overview.data;
      if (!d || !d.collectedAt) {
        return 'No statistics yet — start Catametricas; numbers appear after its second collection.';
      }
      const seconds = Math.max(0, Math.round((Date.now() - Date.parse(d.collectedAt)) / 1000));
      const ago =
        seconds < 90
          ? `${seconds} s`
          : seconds < 5400
            ? `${Math.round(seconds / 60)} min`
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
      const listed =
        scope === 'cluster'
          ? this.tenants
          : scope === 'tenant'
            ? this.namespaces
            : [...new Set(this.topics.map((t) => t.replace(/-partition-\d+$/, '')))];
      const rows = new Map(d.rows.map((r) => [r.name, { ...r, gone: !r.current && !listed.includes(r.name) }]));
      listed.forEach((name) => {
        if (!rows.has(name)) rows.set(name, { name, current: null, period: null, total: null, series: null });
      });
      return [...rows.values()].sort((a, b) => a.name.localeCompare(b.name));
    },

    /**
     * Gives the chart values of a row for the selected metric.
     * @param {object} row overview row (or the summary)
     * @returns {Array<?number>} one value per chart bucket; null where there is no data
     */
    overviewSeries(row) {
      const s = row?.series;
      if (!s) return [];
      return this.overview.metric === 'out' ? s.msgsOut : this.overview.metric === 'backlog' ? s.backlog : s.msgsIn;
    },

    /**
     * Tells whether clicking an overview row opens it.
     * @param {object} row overview row
     * @returns {boolean} false for rows that no longer exist and for partitioned topics
     */
    canDrill(row) {
      const scope = this.overviewScope();
      if (row.gone) return false;
      return scope !== 'namespace' || this.topics.includes(row.name);
    },

    /**
     * Opens an overview row: a tenant shows its namespaces, a namespace its topics, a topic the topic view.
     * @param {object} row overview row
     */
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
      const top = max ?? Math.max(1, ...values.filter((v) => v != null));
      const dx = width / (values.length - 1);
      let d = '';
      let pen = false;
      values.forEach((v, i) => {
        if (v == null) {
          pen = false;
          return;
        }
        const x = (i * dx).toFixed(1);
        const y = (height - 1 - (v / top) * (height - 2)).toFixed(1);
        d += `${pen ? 'L' : 'M'}${x},${y}`;
        pen = true;
      });
      return d;
    },

    /**
     * Computes the geometry and labels of the big overview chart.
     * @returns {object} points, axis ticks and the hover labels
     */
    chartModel() {
      const d = this.overview.data;
      const values = this.overviewSeries(d?.summary);
      const max = this.niceMax(Math.max(0, ...values.filter((v) => v != null)));
      const times = d?.times ?? [];
      const long = ['7d', '30d'].includes(d?.period);
      const label = (t) =>
        new Date(t).toLocaleString(
          'en-GB',
          long
            ? { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }
            : { hour: '2-digit', minute: '2-digit' },
        );
      const xs = times.length ? [0, Math.floor((times.length - 1) / 2), times.length - 1] : [];
      return {
        width: 760,
        height: 160,
        max,
        line: this.linePath(values, 760, 160, max),
        yLabels: [max, max / 2, 0].map((v) => this.fmtCompact(v)),
        xLabels: xs.map((i) => ({ x: times.length > 1 ? (i / (times.length - 1)) * 760 : 0, text: label(times[i]) })),
        values,
        times,
        label,
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

    /**
     * Rounds the largest value up to a readable top for the chart scale.
     * @param {number} n largest value
     * @returns {number} 1, 2, 2.5 or 5 times a power of ten
     */
    niceMax(n) {
      if (n <= 0) return 1;
      const p = Math.pow(10, Math.floor(Math.log10(n)));
      return [1, 2, 2.5, 5, 10].map((f) => f * p).find((v) => v >= n);
    },

    /**
     * Formats a number in short form.
     * @param {?number} n value
     * @returns {string} e.g. "1.2k", "3.4M", or "—" when missing
     */
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
      } catch {
        /* keep the current lists */
      }
    },

    /**
     * Reloads whatever is selected: the topic, the namespace, the tenant or the tenant list.
     */
    async reload() {
      if (this.sel.topic) await this.selectTopic(this.sel.topic);
      else if (this.sel.namespace) await this.selectNamespace(this.sel.namespace);
      else if (this.sel.tenant) await this.selectTenant(this.sel.tenant);
      else await this.loadTenants();
    },

    // ── Settings ───────────────────────────────────────────────────────────
    /**
     * Opens the Settings dialog on a tab and loads the current values.
     * @param {'connections'|'refresh'|'alerts'} [tab] tab to show first
     */
    async openSettings(tab = 'connections') {
      this.settings.tab = tab;
      this.settings.open = true;
      this.auto.draft = this.auto.seconds;
      this.bridges.settings.healthState = null;
      this.audit.settings.healthState = null;
      this.catametricas.healthState = null;
      this.alerts.settings.healthState = null;
      await Promise.all([
        this.loadCataponteSettings(),
        this.loadCatauditSettings(),
        this.loadCatametricasSettings(),
        this.loadCatavigiaSettings(),
        this.loadCollectInterval(),
        this.loadDlqSetting(),
        this.loadAlertThresholds(),
      ]);
      this.bridges.settings.draft = this.bridges.settings.url;
      this.audit.settings.draft = this.audit.settings.url;
      this.alerts.settings.draft = this.alerts.settings.url;
      this.catametricas.draft = this.catametricas.url;
      this.collect.draft = this.collect.seconds;
    },

    /**
     * Saves the Pulsar label (this browser) and whatever changed on the server (Cataponte, Cataudit, Catametricas
     * and Catavigia URLs, intervals, deliveries before the DLQ and alert thresholds; all audited). The dialog stays
     * open if a server change is refused.
     */
    async saveSettings() {
      try {
        localStorage.setItem('pulsarUrl', this.settings.pulsarUrl);
      } catch {
        /* label not remembered */
      }
      const s = this.bridges.settings;
      let changed = false;
      if (s.draft && s.draft !== s.url) {
        if (!(await this.changeCataponteUrl('PUT', { url: s.draft }))) return;
        changed = true;
      }
      const a = this.audit.settings;
      if (a.draft && a.draft !== a.url) {
        if (!(await this.changeCatauditUrl('PUT', { url: a.draft }))) return;
        changed = true;
      }
      const m = this.catametricas;
      if (m.draft && m.draft !== m.url) {
        if (!(await this.changeCatametricasUrl('PUT', { url: m.draft }))) return;
        changed = true;
      }
      const v = this.alerts.settings;
      if (v.draft && v.draft !== v.url) {
        if (!(await this.changeCatavigiaUrl('PUT', { url: v.draft }))) return;
        changed = true;
      }
      for (const t of this.alertThresholds) {
        if (Number(t.draft) !== t.value) {
          if (!(await this.saveAlertThreshold(t, 'PUT'))) return;
          changed = true;
        }
      }
      if (Number(this.collect.draft) !== this.collect.seconds) {
        if (!(await this.saveCollectInterval('PUT'))) return;
        changed = true;
      }
      if (Number(this.auto.draft) !== this.auto.seconds) {
        if (!(await this.saveRefreshInterval('PUT'))) return;
        changed = true;
      }
      if (Number(this.dlqSetting.draft) !== this.dlqSetting.value) {
        if (!(await this.saveDlqSetting('PUT'))) return;
        changed = true;
      }
      if (!changed) this.notify('Settings saved');
      this.settings.open = false;
    },

    /** Loads Catametricas' collection interval (stored in catapulsar_settings). */
    async loadCollectInterval() {
      try {
        const r = await this.api('/api/v1/settings/collect-interval');
        Object.assign(this.collect, {
          seconds: r.seconds,
          defaultSeconds: r.defaultSeconds,
          min: r.min,
          max: r.max,
          custom: r.custom,
        });
      } catch (e) {
        this.notify('Settings: ' + e.message, 'error');
      }
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
      s.healthState = 'checking';
      s.healthMsg = '';
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
      s.healthState = 'checking';
      s.healthMsg = '';
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
    /**
     * Reads Catametricas' URL setting (its current, default and whether it was changed).
     */
    async loadCatametricasSettings() {
      try {
        Object.assign(this.catametricas, await this.api('/api/v1/settings/catametricas'));
      } catch (e) {
        this.notify('Settings: ' + e.message, 'error');
      }
    },

    /**
     * Goes back to the default Catametricas URL, after a confirmation.
     */
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
      s.healthState = 'checking';
      s.healthMsg = '';
      try {
        const facets = await this.api(`${AUDIT_API}/facets`);
        s.healthState = 'ok';
        s.healthMsg = `${s.url} · apps: ${(facets.apps || []).join(', ') || 'none yet'}`;
      } catch (e) {
        s.healthState = 'error';
        s.healthMsg = e.message;
      }
    },

    /**
     * Checks that Catapulsar reaches Pulsar's admin API and shows the result in Settings.
     */
    async checkHealth() {
      this.settings.healthState = 'checking';
      this.settings.healthMsg = '';
      try {
        const tenants = await this.api('/api/v1/tenants');
        this.settings.healthState = 'ok';
        this.settings.healthMsg =
          tenants.length + ' tenant(s): ' + tenants.slice(0, 3).join(', ') + (tenants.length > 3 ? '…' : '');
      } catch (e) {
        this.settings.healthState = 'error';
        this.settings.healthMsg = e.message;
      }
    },

    /**
     * Gives the text of the connection pill in the top bar.
     * @returns {string} the Pulsar host, with the check result when there is one
     */
    connLabel() {
      const host = (() => {
        try {
          return new URL(this.settings.pulsarUrl).host;
        } catch {
          return '?';
        }
      })();
      if (this.settings.healthState === 'ok') return host + ' ✓';
      if (this.settings.healthState === 'error') return host + ' ✕';
      return host;
    },

    // ── Modal ──────────────────────────────────────────────────────────────
    /**
     * Opens a peeked message read-only.
     * @param {object} msg the message
     */
    openView(msg) {
      this.modal = { type: 'view', message: msg, editPayload: msg.payload, editKey: msg.key || '', saving: false };
    },
    /**
     * Opens a peeked message to produce it again, with its key and payload editable.
     * @param {object} msg the message
     */
    openEdit(msg) {
      this.modal = { type: 'edit', message: msg, editPayload: msg.payload, editKey: msg.key || '', saving: false };
    },
    /**
     * Closes the message dialog.
     */
    closeModal() {
      this.modal.type = null;
    },

    /**
     * Produces the edited message on the selected topic, then closes the dialog.
     */
    async saveEdit() {
      this.modal.saving = true;
      try {
        const res = await this.api(
          `/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/produce`,
          {
            method: 'POST',
            body: JSON.stringify({ payload: this.modal.editPayload, key: this.modal.editKey || null }),
          },
        );
        this.notify('Re-produced → ' + res.messageId);
        this.closeModal();
        await this.loadStats();
      } catch (e) {
        this.notify('Error: ' + e.message, 'error');
      } finally {
        this.modal.saving = false;
      }
    },

    // ── Skip ───────────────────────────────────────────────────────────────
    // Skips the first `n` messages from the subscription cursor via Spring backend.
    // "Skip" on message #i (0-based) passes n=i+1 so all messages up to and including #i are skipped.
    /**
     * Skips messages of the selected subscription, after a confirmation (irreversible, audited).
     * @param {number} n how many messages
     */
    async skipMessages(n) {
      if (!confirm(`Skip ${n} message(s) from subscription "${this.backlog.sub}"?\nThis is irreversible.`)) return;
      try {
        await this.api(
          `/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/subscriptions/${encodeURIComponent(this.backlog.sub)}/skip/${n}`,
          { method: 'POST' },
        );
        this.notify(`Skipped ${n} message(s) from "${this.backlog.sub}"`);
        await Promise.all([this.peekMessages(), this.refreshStats()]);
      } catch (e) {
        this.notify('Skip failed: ' + e.message, 'error');
      }
    },

    /**
     * Skips every peeked message of the selected subscription.
     */
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
      if (
        !confirm(
          `Clear the whole backlog of subscription "${sub}" (${this.fmtNum(backlog)} message(s))?\n` +
            'The messages will never be delivered to this subscription. This is irreversible.',
        )
      )
        return;
      try {
        await this.api(
          `/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/subscriptions/${encodeURIComponent(sub)}/clear-backlog`,
          { method: 'POST' },
        );
        this.notify(`Backlog of "${sub}" cleared`);
        await this.refreshStats();
      } catch (e) {
        this.notify('Clear backlog failed: ' + e.message, 'error');
      }
    },

    // ── API helper ─────────────────────────────────────────────────────────
    /**
     * Calls Catapulsar's API; the one helper for every request (UI-3). A Problem Details answer becomes an error with its message.
     * @param {string} url path, e.g. /api/v1/tenants
     * @param {RequestInit} [options] fetch options
     * @returns {Promise<any>} the parsed JSON body, or the text of a non-JSON answer
     */
    async api(url, options = {}) {
      const res = await fetch(url, {
        headers: { 'Content-Type': 'application/json' },
        ...options,
      });
      const ct = res.headers.get('content-type') ?? '';
      const body = ct.includes('json') ? await res.json() : await res.text();
      if (!res.ok) throw new Error(problemMessage(body, res.statusText));
      return body;
    },

    /**
     * Shows a toast for 3.5 s.
     * @param {string} msg the message
     * @param {'success'|'error'} [type] its colour
     */
    notify(msg, type = 'success') {
      this.toast = { msg, type };
      setTimeout(() => (this.toast = { msg: '', type: 'success' }), 3500);
    },

    // ── Tenants / Namespaces / Topics ──────────────────────────────────────
    /**
     * Loads the tenant list.
     */
    async loadTenants() {
      this.loading.tenants = true;
      try {
        this.tenants = await this.api('/api/v1/tenants');
      } catch (e) {
        this.notify('Error loading tenants: ' + e.message, 'error');
      } finally {
        this.loading.tenants = false;
      }
    },

    /**
     * Selects a tenant: opens its tree node, shows its overview and loads its namespaces.
     * @param {string} t tenant name
     */
    async selectTenant(t) {
      this.closePanels();
      this.setTreeOpen(t, true);
      this.sel = { tenant: t, namespace: null, topic: null };
      this.loadOverview();
      this.namespaces = [];
      this.topics = [];
      this.stats = null;
      this.subscriptions = [];
      this.loading.namespaces = true;
      try {
        this.namespaces = await this.api(`/api/v1/namespaces/${t}`);
        this.tree.namespaces = { ...this.tree.namespaces, [t]: this.namespaces };
      } catch (e) {
        this.notify('Error loading namespaces: ' + e.message, 'error');
      } finally {
        this.loading.namespaces = false;
      }
    },

    /**
     * Selects a namespace of the selected tenant: opens its tree node, shows its overview and loads its topics.
     * @param {string} ns namespace name
     */
    async selectNamespace(ns) {
      this.closePanels();
      this.setTreeOpen(`${this.sel.tenant}/${ns}`, true);
      this.sel = { ...this.sel, namespace: ns, topic: null };
      this.loadOverview();
      this.topics = [];
      this.stats = null;
      this.subscriptions = [];
      this.loading.topics = true;
      try {
        this.topics = await this.api(`/api/v1/topics/${this.sel.tenant}/${ns}`);
        this.tree.topics = { ...this.tree.topics, [`${this.sel.tenant}/${ns}`]: this.topics };
      } catch (e) {
        this.notify('Error loading topics: ' + e.message, 'error');
      } finally {
        this.loading.topics = false;
      }
    },

    // ── Sidebar tree: several nodes open at once ─────────────────────────────
    /**
     * Tells whether a sidebar node is open.
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
      if (open) next[key] = true;
      else delete next[key];
      this.tree.open = next;
      try {
        localStorage.setItem(TREE_OPEN_KEY, JSON.stringify(Object.keys(next)));
      } catch {
        /* not remembered */
      }
    },

    /** Closes every node except the path to the selection. */
    collapseTree() {
      const keep = {};
      if (this.sel.tenant) keep[this.sel.tenant] = true;
      if (this.sel.tenant && this.sel.namespace) keep[`${this.sel.tenant}/${this.sel.namespace}`] = true;
      this.tree.open = keep;
      try {
        localStorage.setItem(TREE_OPEN_KEY, JSON.stringify(Object.keys(keep)));
      } catch {
        /* not remembered */
      }
    },

    /** Loads the lists of the nodes left open last time (after a reload); failures leave them empty. */
    loadOpenTreeNodes() {
      Object.keys(this.tree.open).forEach((key) => {
        const [tenant, ns] = key.split('/');
        if (!this.tenants.includes(tenant)) return;
        if (ns) this.loadTreeTopics(tenant, ns);
        else this.loadTreeNamespaces(tenant);
      });
    },

    /**
     * Loads the namespaces of a tenant into the sidebar tree; on failure the node stays empty.
     * @param {string} tenant tenant name
     */
    async loadTreeNamespaces(tenant) {
      try {
        const list = await this.api(`/api/v1/namespaces/${tenant}`);
        this.tree.namespaces = { ...this.tree.namespaces, [tenant]: list };
      } catch {
        /* shown empty */
      }
    },

    /**
     * Loads the topics of a namespace into the sidebar tree; on failure the node stays empty.
     * @param {string} tenant tenant name
     * @param {string} ns namespace name
     */
    async loadTreeTopics(tenant, ns) {
      try {
        const list = await this.api(`/api/v1/topics/${tenant}/${ns}`);
        this.tree.topics = { ...this.tree.topics, [`${tenant}/${ns}`]: list };
      } catch {
        /* shown empty */
      }
    },

    /**
     * Gives the namespaces the tree shows under a tenant.
     * @param {string} tenant tenant name
     * @returns {string[]} the live list when the tenant is selected, else the last one loaded
     */
    treeNamespaces(tenant) {
      return tenant === this.sel.tenant ? this.namespaces : (this.tree.namespaces[tenant] ?? []);
    },

    /**
     * Gives the topics the tree shows under a namespace.
     * @param {string} tenant tenant name
     * @param {string} ns namespace name
     * @returns {string[]} the live list when the namespace is selected, else the last one loaded
     */
    treeTopics(tenant, ns) {
      return tenant === this.sel.tenant && ns === this.sel.namespace
        ? this.topics
        : (this.tree.topics[`${tenant}/${ns}`] ?? []);
    },

    /**
     * Click on a tenant: selects it (and opens it); clicking the selected tenant opens or closes it.
     * Selecting closes the drawer on phones.
     * @param {string} tenant tenant
     */
    clickTreeTenant(tenant) {
      if (this.sel.tenant === tenant && !this.sel.namespace && !this.panelOpen()) {
        this.setTreeOpen(tenant, !this.isTreeOpen(tenant));
        return;
      }
      this.drawerOpen = false;
      this.selectTenant(tenant);
    },

    /**
     * Click on a namespace: selects it (and opens it); clicking the selected namespace opens or closes it.
     * Selecting closes the drawer on phones.
     * @param {string} tenant its tenant
     * @param {string} ns namespace
     */
    async clickTreeNamespace(tenant, ns) {
      const key = `${tenant}/${ns}`;
      if (this.sel.tenant === tenant && this.sel.namespace === ns && !this.sel.topic && !this.panelOpen()) {
        this.setTreeOpen(key, !this.isTreeOpen(key));
        return;
      }
      this.drawerOpen = false;
      if (this.sel.tenant !== tenant) await this.selectTenant(tenant);
      await this.selectNamespace(ns);
    },

    /**
     * Click on a topic of any open namespace; closes the drawer on phones.
     * @param {string} tenant its tenant
     * @param {string} ns its namespace
     * @param {string} topic topic
     */
    async clickTreeTopic(tenant, ns, topic) {
      this.drawerOpen = false;
      if (this.sel.tenant !== tenant) await this.selectTenant(tenant);
      if (this.sel.namespace !== ns) await this.selectNamespace(ns);
      await this.selectTopic(topic);
    },

    /**
     * Deletes a topic of any open namespace, after a confirmation.
     * @param {string} tenant its tenant
     * @param {string} ns its namespace
     * @param {string} topic topic name
     * @returns {Promise<void>} settles when the topic is deleted or the user cancels
     */
    async deleteTreeTopic(tenant, ns, topic) {
      if (tenant === this.sel.tenant && ns === this.sel.namespace) return this.deleteTopic(topic);
      if (!confirm(`Delete topic "${topic}"?\nAll messages and subscriptions will be removed.`)) return;
      try {
        await this.api(`/api/v1/topics/${tenant}/${ns}/${topic}`, { method: 'DELETE' });
        this.notify(`Topic "${topic}" deleted`);
        await this.loadTreeTopics(tenant, ns);
      } catch (e) {
        this.notify('Error: ' + e.message, 'error');
      }
    },

    /**
     * Selects a topic of the selected namespace and shows its view: statistics, subscriptions, limits and the bridges reading it.
     * @param {string} t topic name
     */
    async selectTopic(t) {
      this.closePanels(); // leaving the Bridges or Audit panel
      this.sel = { ...this.sel, topic: t };
      this.tab = 'stats';
      this.stats = null;
      this.produce = { payload: '', key: '', lastId: null };
      this.backlog = { sub: '', count: 10, messages: [], queried: false };
      this.subConsumersOpen = {};
      this.subHistory = { ...this.subHistory, open: {}, data: {}, loading: {}, error: {}, hover: null };
      this.subLimits.editing = null;
      this.loadSubLimits(); // the Limit column; shows "—" when Catavigia is down
      await this.loadStats();
      await this.loadSubs();
      this.refreshBridgeList(); // which subscriptions belong to bridges; ignored when Cataponte is down
    },

    /**
     * Creates the topic typed under the selected namespace.
     */
    async createTopic() {
      if (!this.newTopic.trim()) return;
      try {
        await this.api(`/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.newTopic}`, { method: 'POST' });
        this.notify(`Topic "${this.newTopic}" created`);
        this.newTopic = '';
        await this.selectNamespace(this.sel.namespace);
      } catch (e) {
        this.notify('Error: ' + e.message, 'error');
      }
    },

    /**
     * Deletes a topic of the selected namespace, after a confirmation.
     * @param {string} t topic name
     */
    async deleteTopic(t) {
      if (!confirm(`Delete topic "${t}"?\nAll messages and subscriptions will be removed.`)) return;
      try {
        await this.api(`/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${t}`, { method: 'DELETE' });
        this.notify(`Topic "${t}" deleted`);
        if (this.sel.topic === t) this.sel.topic = null;
        await this.selectNamespace(this.sel.namespace);
      } catch (e) {
        this.notify('Error: ' + e.message, 'error');
      }
    },

    // ── Stats ──────────────────────────────────────────────────────────────
    /**
     * Loads the statistics of the selected topic, with a spinner.
     */
    async loadStats() {
      this.loading.stats = true;
      this.stats = null;
      try {
        this.stats = await this.api(`/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/stats`);
      } catch (e) {
        this.notify('Error loading stats: ' + e.message, 'error');
      } finally {
        this.loading.stats = false;
      }
    },

    /** Reloads the statistics in place (no spinner); dropped if the user picked another topic meanwhile. */
    async refreshStats() {
      const { tenant, namespace, topic } = this.sel;
      try {
        const stats = await this.api(`/api/v1/topics/${tenant}/${namespace}/${topic}/stats`);
        const sameTopic = this.sel.tenant === tenant && this.sel.namespace === namespace && this.sel.topic === topic;
        if (sameTopic && (this.tab === 'stats' || this.tab === 'backlog')) this.stats = stats;
      } catch {
        /* keep the last values; the next tick retries */
      }
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
      } catch {
        /* keep the current list */
      }
    },

    /**
     * Loads the subscription list of the selected topic.
     */
    async loadSubs() {
      if (!this.sel.topic) return;
      this.loading.subs = true;
      try {
        this.subscriptions = await this.api(
          `/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/subscriptions`,
        );
      } catch {
        this.subscriptions = [];
      } finally {
        this.loading.subs = false;
      }
    },

    /**
     * Creates the subscription typed on the selected topic.
     */
    async createSub() {
      if (!this.newSub.trim()) return;
      this.loading.newSub = true;
      try {
        await this.api(
          `/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/subscriptions/${this.newSub}`,
          { method: 'POST' },
        );
        this.notify(`Subscription "${this.newSub}" created`);
        this.newSub = '';
        await this.loadSubs();
      } catch (e) {
        this.notify('Error: ' + e.message, 'error');
      } finally {
        this.loading.newSub = false;
      }
    },

    /**
     * Deletes a subscription of the selected topic, after a confirmation.
     * @param {string} sub subscription name
     */
    async deleteSub(sub) {
      if (!confirm(`Delete subscription "${sub}"?`)) return;
      try {
        await this.api(
          `/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/subscriptions/${sub}`,
          { method: 'DELETE' },
        );
        this.notify(`Subscription "${sub}" deleted`);
        await this.loadSubs();
      } catch (e) {
        this.notify('Error: ' + e.message, 'error');
      }
    },

    // ── Produce ────────────────────────────────────────────────────────────
    /**
     * Produces the message typed in the Produce tab on the selected topic.
     */
    async sendMessage() {
      if (!this.produce.payload.trim()) return;
      this.loading.produce = true;
      this.produce.lastId = null;
      try {
        const res = await this.api(
          `/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/produce`,
          {
            method: 'POST',
            body: JSON.stringify({ payload: this.produce.payload, key: this.produce.key }),
          },
        );
        this.produce.lastId = res.messageId;
        this.notify('Message sent');
        await this.loadStats();
      } catch (e) {
        this.notify('Error sending: ' + e.message, 'error');
      } finally {
        this.loading.produce = false;
      }
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
      return [...names].sort().map((name) => ({ name, stats: stats[name] }));
    },

    /**
     * Selects a subscription and peeks its backlog straight away (audited as PEEK_MESSAGES).
     * @param {string} name subscription name
     */
    async selectBacklogSub(name) {
      this.backlog.sub = name;
      await this.peekMessages();
    },

    /**
     * Peeks the first messages of the selected subscription's backlog without consuming them (audited).
     */
    async peekMessages() {
      if (!this.backlog.sub) return;
      const sub = this.backlog.sub;
      const count = Math.min(100, Math.max(1, Number(this.backlog.count) || 10));
      this.backlog.count = count;
      this.loading.backlog = true;
      this.backlog.messages = [];
      this.backlog.queried = false;
      try {
        const messages = await this.api(
          `/api/v1/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/peek/${encodeURIComponent(sub)}?count=${count}`,
        );
        if (this.backlog.sub !== sub) return; // another subscription was clicked meanwhile
        this.backlog.messages = messages;
        this.backlog.queried = true;
      } catch (e) {
        this.notify('Peek error: ' + e.message, 'error');
      } finally {
        if (this.backlog.sub === sub) this.loading.backlog = false;
      }
    },

    /** Leaves the Bridges or Audit panel, e.g. when a tenant, namespace or topic is selected. */
    closePanels() {
      if (this.bridgesView) this.closeBridges();
      if (this.auditView) this.closeAudit();
      this.consumersView = false;
      this.alertsView = false;
    },

    /**
     * Tells whether a panel covers the main view.
     * @returns {boolean} whether Bridges, Audit, Consumers or Alerts is open
     */
    panelOpen() {
      return this.bridgesView || this.auditView || this.consumersView || this.alertsView;
    },

    // ── Alerts (Catavigia, ADR 0015) ───────────────────────────────────────
    /**
     * Starts the badge: asks Catavigia now and every 30 s while this browser tab is visible, whatever the
     * auto-refresh (orch.md decision 78), and again as soon as the tab becomes visible.
     */
    startAlertBadge() {
      this.loadAlertSummary();
      setInterval(() => {
        if (!document.hidden) this.loadAlertSummary();
      }, ALERT_BADGE_MS);
      document.addEventListener('visibilitychange', () => {
        if (!document.hidden) this.loadAlertSummary();
      });
    },

    /** Reads the open alerts not marked as seen, by severity; an unreachable Catavigia greys the badge. */
    async loadAlertSummary() {
      try {
        const r = await this.api(`${ALERTS_API}/summary`);
        Object.assign(this.alertBadge, {
          open: r.open,
          unseen: r.unseen,
          critical: r.critical,
          warning: r.warning,
          worst: r.worst,
          error: '',
          loaded: true,
        });
      } catch (e) {
        Object.assign(this.alertBadge, { error: e.message, loaded: true });
      }
    },

    /**
     * Gives the number on the Alerts badge.
     * @returns {string} open alerts not seen, "?" when Catavigia does not answer, "" when none
     */
    alertBadgeText() {
      const b = this.alertBadge;
      if (b.error) return '?';
      return b.unseen > 0 ? String(b.unseen) : '';
    },

    /**
     * Gives the colour of the Alerts badge.
     * @returns {string} CSS class: critical, warning or unknown
     */
    alertBadgeClass() {
      const b = this.alertBadge;
      if (b.error) return 'alert-badge-unknown';
      return b.critical > 0 ? 'alert-badge-critical' : 'alert-badge-warning';
    },

    /**
     * Gives the tooltip of the Alerts button.
     * @returns {string} what is open and not seen, or why it is unknown
     */
    alertBadgeTitle() {
      const b = this.alertBadge;
      if (b.error) return 'Catavigia does not answer: ' + b.error;
      if (!b.loaded) return 'Alerts';
      if (b.open === 0) return 'No open alert';
      return `${b.unseen} not seen (${b.critical} critical, ${b.warning} warning) of ${b.open} open`;
    },

    /**
     * Opens the Alerts panel.
     * @param {'open'|'archived'} [tab] tab to show
     */
    openAlerts(tab) {
      if (this.bridgesView) this.closeBridges();
      if (this.auditView) this.closeAudit();
      this.consumersView = false;
      this.alertsView = true;
      if (tab) this.alerts.tab = tab;
      this.alerts.expanded = null;
      this.loadCatavigiaSettings();
      if (this.alerts.tab === 'open') this.loadAlerts();
      else this.loadAlertArchive();
    },

    /**
     * Shows a tab of the Alerts panel.
     * @param {'open'|'archived'} tab the tab
     */
    selectAlertsTab(tab) {
      this.alerts.tab = tab;
      if (tab === 'open') this.loadAlerts();
      else this.loadAlertArchive();
    },

    /**
     * Loads the open alerts.
     * @param {boolean} [quiet] no spinner, errors kept silent (auto-refresh)
     */
    async loadAlerts(quiet = false) {
      if (!quiet) this.alerts.loading = true;
      try {
        const list = await this.api(ALERTS_API);
        if (JSON.stringify(list) !== JSON.stringify(this.alerts.open)) this.alerts.open = list;
        this.alerts.error = '';
        this.loadAlertSummary();
      } catch (e) {
        if (!quiet) this.alerts.error = e.message;
      } finally {
        if (!quiet) this.alerts.loading = false;
      }
    },

    /**
     * Builds the query string of the alert archive's filters and page.
     * @returns {string} query string without "?"
     */
    alertArchiveQuery() {
      const a = this.alerts.archive;
      const q = new URLSearchParams();
      if (a.filters.type) q.set('type', a.filters.type);
      if (a.filters.subject.trim()) q.set('subject', a.filters.subject.trim());
      const back = ALERT_PERIODS[a.filters.period];
      if (back) q.set('from', new Date(Date.now() - back).toISOString());
      q.set('page', String(a.page));
      q.set('size', String(ALERT_PAGE_SIZE));
      return q.toString();
    },

    /**
     * Loads one page of the archive.
     * @param {boolean} [quiet] no spinner, errors kept silent (auto-refresh)
     */
    async loadAlertArchive(quiet = false) {
      const a = this.alerts.archive;
      const key = JSON.stringify([a.filters, a.page]);
      if (!quiet) a.loading = true;
      try {
        const r = await this.api(`${ALERTS_API}/archive?${this.alertArchiveQuery()}`);
        if (key !== JSON.stringify([a.filters, a.page])) return;
        if (JSON.stringify(r.items) !== JSON.stringify(a.items)) a.items = r.items;
        a.total = r.total;
        a.error = '';
      } catch (e) {
        if (!quiet) a.error = e.message;
      } finally {
        if (!quiet) a.loading = false;
      }
    },

    /** Applies the archive filters from the first page; the period is remembered in this browser. */
    applyAlertFilters() {
      const a = this.alerts.archive;
      a.page = 0;
      a.expanded = null;
      try {
        localStorage.setItem('alertsPeriod', a.filters.period);
      } catch {
        /* not persisted */
      }
      this.loadAlertArchive();
    },

    /**
     * Clears the alert archive's filters and searches again.
     */
    clearAlertFilters() {
      Object.assign(this.alerts.archive.filters, { type: '', subject: '' });
      this.applyAlertFilters();
    },

    /**
     * Counts the alert archive's pages for the current filters.
     * @returns {number} pages, at least 1
     */
    alertArchivePages() {
      return Math.max(1, Math.ceil(this.alerts.archive.total / ALERT_PAGE_SIZE));
    },

    /**
     * Moves to another archive page.
     * @param {number} delta -1 for newer, +1 for older
     */
    alertArchiveGo(delta) {
      const a = this.alerts.archive;
      const page = a.page + delta;
      if (page < 0 || page >= this.alertArchivePages()) return;
      a.page = page;
      a.expanded = null;
      this.loadAlertArchive();
    },

    /**
     * Shows or hides an alert's details.
     * @param {object} holder `alerts` or `alerts.archive`
     * @param {string} id alert id
     */
    toggleAlert(holder, id) {
      holder.expanded = holder.expanded === id ? null : id;
    },

    /**
     * Describes an alert type.
     * @param {string} type alert type, e.g. service.down
     * @returns {string} what it means, or the type itself when unknown
     */
    alertTypeLabel(type) {
      return ALERT_TYPES[type] ?? type;
    },

    /**
     * Lists every alert type, for the archive filter.
     * @returns {string[]} alert types
     */
    alertTypes() {
      return Object.keys(ALERT_TYPES);
    },

    /**
     * How long ago, in words.
     * @param {string} iso ISO-8601 instant
     * @returns {string} e.g. `5 min`, `2 h 10 min`, `3 d 4 h`
     */
    alertAge(iso) {
      if (!iso) return '—';
      return this.alertDuration(Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000));
    },

    /**
     * A duration in words.
     * @param {number} seconds the duration
     * @returns {string} e.g. `40 s`, `5 min`, `2 h 10 min`, `3 d 4 h`
     */
    alertDuration(seconds) {
      const s = Math.round(seconds ?? 0);
      if (s < 60) return `${s} s`;
      const m = Math.floor(s / 60);
      if (m < 60) return `${m} min`;
      const h = Math.floor(m / 60);
      if (h < 24) return `${h} h ${m % 60} min`;
      return `${Math.floor(h / 24)} d ${h % 24} h`;
    },

    /**
     * Marks one open alert as seen: it stays open and leaves the badge.
     * @param {object} alert the alert
     */
    async markAlertSeen(alert) {
      this.alerts.busy = true;
      try {
        await this.api(`${ALERTS_API}/${alert.id}/seen`, { method: 'POST' });
        await this.loadAlerts(true);
      } catch (e) {
        this.notify('Not marked as seen: ' + e.message, 'error');
      } finally {
        this.alerts.busy = false;
      }
    },

    /** Marks every open alert not yet seen as seen, one by one (each recorded in the audit log). */
    async markAllAlertsSeen() {
      const unseen = this.alerts.open.filter((a) => !a.seenAt && !a.local);
      if (unseen.length === 0) return;
      if (!confirm(`Mark ${unseen.length} alert(s) as seen? They stay open but leave the badge.`)) return;
      this.alerts.busy = true;
      let failed = 0;
      for (const alert of unseen) {
        try {
          await this.api(`${ALERTS_API}/${alert.id}/seen`, { method: 'POST' });
        } catch {
          failed++;
        }
      }
      this.alerts.busy = false;
      this.notify(
        failed ? `${failed} alert(s) not marked as seen` : `${unseen.length} alert(s) marked as seen`,
        failed ? 'error' : 'success',
      );
      await this.loadAlerts(true);
    },

    /**
     * Label of the button that takes an alert to where it is fixed (ADR 0015 section 3).
     * @param {object} alert the alert
     * @returns {string} the label
     */
    alertActionLabel(alert) {
      if (alert.type === 'poison') return 'Open DLQ';
      if (alert.type.startsWith('bridge.')) return 'Open bridge';
      if (alert.type === 'backlog') return 'Open subscription';
      if (alert.type === 'consumer.paused') return 'Open consumers';
      if (alert.type === 'collection.stale') return 'Collection settings';
      return 'Connections';
    },

    /**
     * Takes an alert to where its owner fixes it, with the resource selected.
     * @param {object} alert the alert
     */
    async runAlertAction(alert) {
      try {
        if (alert.type === 'poison') {
          this.openConsumers('deadletters');
          await this.loadDeadLetters();
          await this.peekDeadLetters(alert.subject);
        } else if (alert.type.startsWith('bridge.')) {
          await this.loadBridges();
          const bridge = this.bridges.list.find((b) => b.name === alert.subject);
          if (bridge) await this.openBridgeOf(bridge);
          else this.openBridges();
        } else if (alert.type === 'backlog') {
          await this.openSubscriptionOf(alert.subject);
        } else if (alert.type === 'consumer.paused') {
          this.openConsumers('consumers');
        } else if (alert.type === 'collection.stale') {
          await this.openSettings('refresh');
        } else {
          await this.openSettings('connections');
        }
      } catch (e) {
        this.notify('Could not open it: ' + e.message, 'error');
      }
    },

    /**
     * Opens the topic view of a backlog alert's subject, with the subscription's history open.
     * @param {string} subject `persistent://tenant/namespace/topic|subscription`
     */
    async openSubscriptionOf(subject) {
      const bar = subject.lastIndexOf('|');
      const topic = subject.slice(0, bar);
      const subscription = subject.slice(bar + 1);
      const m = topic.match(/^[a-z-]+:\/\/([^/]+)\/([^/]+)\/(.+)$/);
      if (!m) return;
      await this.clickTreeTopic(m[1], m[2], m[3]);
      this.tab = 'stats';
      this.toggleSubHistory(subscription, true);
      setTimeout(() => document.getElementById(`sub-row-${subscription}`)?.scrollIntoView({ block: 'center' }), 300);
    },

    // ── Catavigia URL (stored in MongoDB, audited) ──────────────────────────
    /**
     * Reads Catavigia's URL setting.
     */
    async loadCatavigiaSettings() {
      try {
        Object.assign(this.alerts.settings, await this.api('/api/v1/settings/catavigia'));
      } catch (e) {
        this.notify('Settings: ' + e.message, 'error');
      }
    },

    /**
     * Goes back to the default Catavigia URL, after a confirmation.
     */
    async resetCatavigiaUrl() {
      if (!confirm(`Go back to the default Catavigia URL ${this.alerts.settings.defaultUrl}?`)) return;
      if (await this.changeCatavigiaUrl('DELETE')) this.alerts.settings.draft = this.alerts.settings.url;
    },

    /**
     * Saves or resets the Catavigia URL, then reloads the badge from the new address.
     * @param {'PUT'|'DELETE'} method PUT saves `body.url`, DELETE goes back to the default
     * @param {{url: string}} [body] new URL
     * @returns {Promise<boolean>} whether the change was saved
     */
    async changeCatavigiaUrl(method, body) {
      const s = this.alerts.settings;
      s.saving = true;
      try {
        const options = { method };
        if (body) options.body = JSON.stringify(body);
        Object.assign(s, await this.api('/api/v1/settings/catavigia', options));
        s.healthState = null;
        this.notify(`Catavigia URL set to ${s.url}`);
        this.loadAlertSummary();
        if (this.alertsView) this.loadAlerts();
        return true;
      } catch (e) {
        this.notify('Catavigia URL not saved: ' + e.message, 'error');
        return false;
      } finally {
        s.saving = false;
      }
    },

    /**
     * Checks that Catapulsar reaches Catavigia and shows the result in Settings.
     */
    async checkCatavigia() {
      const s = this.alerts.settings;
      s.healthState = 'checking';
      s.healthMsg = '';
      try {
        const r = await this.api(`${ALERTS_API}/summary`);
        s.healthState = 'ok';
        s.healthMsg = `${s.url} · ${r.open} open alert(s)`;
      } catch (e) {
        s.healthState = 'error';
        s.healthMsg = e.message;
      }
    },

    // ── Settings → Alerts: thresholds read by Catavigia ─────────────────────
    /**
     * Reads Catavigia's thresholds (Settings → Alerts), each with a draft to edit.
     */
    async loadAlertThresholds() {
      try {
        const list = await this.api('/api/v1/settings/alerts');
        this.alertThresholds = list.map((t) => ({ ...t, draft: t.value }));
      } catch (e) {
        this.notify('Alert settings: ' + e.message, 'error');
      }
    },

    /**
     * Gives the label and hint shown for an alert threshold.
     * @param {string} name threshold name
     * @returns {{label: string, hint: string}} its texts; the name itself when unknown
     */
    thresholdInfo(name) {
      return ALERT_THRESHOLDS[name] ?? { label: name, hint: '' };
    },

    /**
     * Saves or resets one threshold.
     * @param {object} threshold the threshold, with its `draft`
     * @param {'PUT'|'DELETE'} method PUT saves the draft, DELETE goes back to the default
     * @returns {Promise<boolean>} whether it was saved
     */
    async saveAlertThreshold(threshold, method) {
      try {
        const options = { method };
        if (method === 'PUT') options.body = JSON.stringify({ value: Number(threshold.draft) });
        const r = await this.api(`/api/v1/settings/alerts/${threshold.name}`, options);
        Object.assign(threshold, r, { draft: r.value });
        if (method === 'DELETE') this.notify(`${this.thresholdInfo(threshold.name).label}: back to ${r.value}`);
        return true;
      } catch (e) {
        this.notify(`${this.thresholdInfo(threshold.name).label} not saved: ${e.message}`, 'error');
        return false;
      }
    },

    // ── Backlog limit per subscription (topic view, Catavigia) ──────────────
    /** Loads the global default and every subscription's own limit; an unreachable Catavigia shows "—". */
    async loadSubLimits() {
      try {
        const r = await this.api(LIMITS_API);
        const byKey = {};
        (r.limits || []).forEach((l) => {
          byKey[`${l.topic}|${l.subscription}`] = l.limit;
        });
        Object.assign(this.subLimits, { global: r.globalLimit, byKey, error: '' });
      } catch (e) {
        Object.assign(this.subLimits, { global: null, byKey: {}, error: e.message });
      }
    },

    /**
     * Tells whether a subscription is watched by the backlog check (not a DLQ's own, not Pulsar's).
     * @param {string} name subscription name
     * @returns {boolean} whether it has a limit
     */
    subHasLimit(name) {
      if (this.isSystemSub(name)) return false;
      return !(name === 'dlq' && (this.sel.topic || '').endsWith('-DLQ'));
    },

    /**
     * Gives the backlog limit set on a subscription of the selected topic.
     * @param {string} name subscription name
     * @returns {?number} its own limit, or null when the global default applies
     */
    subOwnLimit(name) {
      return this.subLimits.byKey[`${this.currentTopicName()}|${name}`] ?? null;
    },

    /**
     * Gives the backlog limit that applies to a subscription of the selected topic.
     * @param {string} name subscription name
     * @returns {?number} its own limit or the global default; null when unknown
     */
    subLimit(name) {
      return this.subOwnLimit(name) ?? this.subLimits.global;
    },

    /**
     * Gives what the Limit column shows for a subscription.
     * @param {string} name subscription name
     * @returns {string} its limit, "global (N)", or "—"
     */
    subLimitLabel(name) {
      if (!this.subHasLimit(name)) return '—';
      if (this.subLimits.global == null) return '—';
      const own = this.subOwnLimit(name);
      return own != null ? this.fmtNum(own) : `global (${this.fmtNum(this.subLimits.global)})`;
    },

    /**
     * Starts editing the backlog limit of a subscription.
     * @param {string} name subscription name
     */
    editSubLimit(name) {
      this.subLimits.editing = name;
      this.subLimits.draft = this.subOwnLimit(name) ?? this.subLimits.global ?? '';
    },

    /**
     * Saves a subscription's own limit, or removes it to use the global default (audited by both apps).
     * @param {string} name subscription name
     * @param {'PUT'|'DELETE'} method PUT saves the draft, DELETE uses the global default
     */
    async saveSubLimit(name, method) {
      const q = new URLSearchParams({ topic: this.currentTopicName(), subscription: name });
      this.subLimits.saving = true;
      try {
        const options = { method };
        if (method === 'PUT') options.body = JSON.stringify({ limit: Number(this.subLimits.draft) });
        await this.api(`${LIMITS_API}?${q}`, options);
        this.subLimits.editing = null;
        this.notify(
          method === 'PUT'
            ? `Limit of ${name} set to ${this.fmtNum(Number(this.subLimits.draft))}`
            : `${name} uses the global limit again`,
        );
        await this.loadSubLimits();
      } catch (e) {
        this.notify('Limit not saved: ' + e.message, 'error');
      } finally {
        this.subLimits.saving = false;
      }
    },

    // ── History of a subscription (Catametricas, decision 78) ───────────────
    /**
     * Shows or hides the chart under a subscription's row.
     * @param {string} name subscription name
     * @param {boolean} [open] force open
     */
    toggleSubHistory(name, open) {
      const show = open ?? !this.subHistory.open[name];
      this.subHistory.open = { ...this.subHistory.open, [name]: show };
      if (show) this.loadSubHistory(name);
    },

    /**
     * Loads a subscription's history for the chosen period.
     * @param {string} name subscription name
     */
    async loadSubHistory(name) {
      const h = this.subHistory;
      const { tenant, namespace, topic } = this.sel;
      const local = (topic || '').replace(/^.*\//, '');
      h.loading = { ...h.loading, [name]: true };
      try {
        const r = await this.api(
          `/api/v1/overview/${tenant}/${namespace}/${encodeURIComponent(local)}` +
            `/subscriptions/${encodeURIComponent(name)}?period=${h.period}`,
        );
        h.data = { ...h.data, [name]: r };
        h.error = { ...h.error, [name]: '' };
      } catch (e) {
        h.error = { ...h.error, [name]: e.message };
      } finally {
        h.loading = { ...h.loading, [name]: false };
      }
    },

    /**
     * Changes the period of every open subscription chart.
     * @param {string} period 1h, 24h, 7d or 30d
     */
    setSubHistoryPeriod(period) {
      this.subHistory.period = period;
      Object.entries(this.subHistory.open).forEach(([name, open]) => {
        if (open) this.loadSubHistory(name);
      });
    },

    /**
     * Geometry of a subscription's chart: backlog line, its maximum shaded, delivered bars, limit line.
     * @param {string} name subscription name
     * @returns {object} paths, bars and labels
     */
    subChartModel(name) {
      const d = this.subHistory.data[name];
      const width = 760,
        height = 140; // the same as the viewBox in index.html
      const backlog = d?.backlog ?? [];
      const backlogMax = d?.backlogMax ?? [];
      const out = d?.msgsOut ?? [];
      const limit = this.subHasLimit(name) ? this.subLimit(name) : null;
      const top = this.niceMax(
        Math.max(
          0,
          ...backlogMax.filter((v) => v != null),
          ...backlog.filter((v) => v != null),
          limit != null && limit < 10 * Math.max(1, ...backlog.filter((v) => v != null)) ? limit : 0,
        ),
      );
      const outTop = this.niceMax(Math.max(0, ...out));
      const n = Math.max(backlog.length, 1);
      const barWidth = Math.max(1, width / n - 1);
      // One path for every bar: Alpine cannot repeat elements inside an <svg>.
      const barsPath = out
        .map((v, i) => {
          const h = v ? Math.max(1, (v / outTop) * (height * 0.35)) : 0;
          return h
            ? `M${((i * width) / n).toFixed(1)},${height}v${(-h).toFixed(1)}h${barWidth.toFixed(1)}` +
                `v${h.toFixed(1)}Z`
            : '';
        })
        .join('');
      const times = d?.times ?? [];
      const long = ['7d', '30d'].includes(this.subHistory.period);
      const label = (t) =>
        new Date(t).toLocaleString(
          'en-GB',
          long
            ? { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }
            : { hour: '2-digit', minute: '2-digit' },
        );
      const xs = times.length ? [0, Math.floor((times.length - 1) / 2), times.length - 1] : [];
      return {
        width,
        height,
        top,
        outTop,
        barsPath,
        line: this.linePath(backlog, width, height, top),
        maxLine: this.linePath(backlogMax, width, height, top),
        limitY: limit != null && limit <= top ? height - 1 - (limit / top) * (height - 2) : null,
        yLabels: [top, top / 2, 0].map((v) => this.fmtCompact(v)),
        xLabels: xs.map((i) => ({ x: times.length > 1 ? (i / (times.length - 1)) * width : 0, text: label(times[i]) })),
        empty: backlog.every((v) => v == null) && out.every((v) => !v),
        times,
        backlog,
        backlogMax,
        out,
        label,
      };
    },

    /**
     * Updates the hover text of a subscription's chart.
     * @param {string} name subscription name
     * @param {MouseEvent} event mouse move over the chart
     */
    subChartHover(name, event) {
      const m = this.subChartModel(name);
      if (m.times.length < 2) return;
      const rect = event.currentTarget.getBoundingClientRect();
      const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
      const i = Math.round(ratio * (m.times.length - 1));
      const b = m.backlog[i];
      this.subHistory.hover = {
        name,
        x: (i / (m.times.length - 1)) * m.width,
        text:
          `${m.label(m.times[i])} · backlog ${b == null ? 'no data' : this.fmtNum(b)}` +
          (m.backlogMax[i] != null ? ` (max ${this.fmtNum(m.backlogMax[i])})` : '') +
          ` · delivered ${this.fmtNum(m.out[i] ?? 0)}`,
      };
    },

    // ── Consumers and dead letters (ADR 0015) ──────────────────────────────
    /**
     * Opens the Consumers panel (closing the other panels) and loads it.
     * @param {'consumers'|'deadletters'} [tab] tab to show; the last one when omitted
     */
    openConsumers(tab) {
      if (this.bridgesView) this.closeBridges();
      if (this.auditView) this.closeAudit();
      this.alertsView = false;
      this.consumersView = true;
      if (tab) this.cons.tab = tab;
      this.loadConsumers();
      this.loadDeadLetters();
    },

    /**
     * Loads the apps' consumers per replica (from their status topics, through Catapulsar).
     * @param {boolean} [quiet] no spinner, errors kept silent (auto-refresh)
     */
    async loadConsumers(quiet = false) {
      if (!quiet) this.cons.loading = true;
      try {
        const list = await this.api('/api/v1/consumers');
        if (JSON.stringify(list) !== JSON.stringify(this.cons.list)) this.cons.list = list;
        this.cons.error = '';
      } catch (e) {
        if (!quiet) this.cons.error = e.message;
      } finally {
        this.cons.loading = false;
      }
    },

    /**
     * Groups the apps' consumers by app and consumer.
     * @returns {object[]} one group per consumer, each with its replicas
     */
    consumerGroups() {
      const groups = new Map();
      this.cons.list.forEach((c) => {
        const key = `${c.app}/${c.consumer}`;
        if (!groups.has(key))
          groups.set(key, {
            key,
            app: c.app,
            consumer: c.consumer,
            topic: c.topic,
            subscription: c.subscription,
            deadLetterTopic: c.deadLetterTopic,
            maxDeliveries: c.maxDeliveries,
            replicas: [],
          });
        groups.get(key).replicas.push(c);
      });
      return [...groups.values()];
    },

    /**
     * Gives the short label of a consumer state.
     * @param {string} state consumer state, e.g. PAUSED_AUTO
     * @returns {string} e.g. "paused (auto)"
     */
    consumerStateLabel(state) {
      return (
        { RUNNING: 'running', PAUSED_AUTO: 'paused (auto)', PAUSED_MANUAL: 'paused by hand', STARTING: 'starting' }[
          state
        ] ?? (state || '?').toLowerCase()
      );
    },

    /**
     * Asks an app to pause, resume or check consumers; it applies it within seconds and the list shows it.
     * @param {string} app app name
     * @param {string} instance replica, or '*'
     * @param {string} consumer consumer, or '*'
     * @param {'pause'|'resume'|'check'} action what to do
     */
    async consumerCommand(app, instance, consumer, action) {
      const where = instance === '*' ? 'every replica' : instance;
      if (action === 'pause' && !confirm(`Pause ${app}/${consumer} on ${where}?\nIt stays paused until you resume it.`))
        return;
      this.cons.busy = true;
      try {
        await this.api('/api/v1/consumers/commands', {
          method: 'POST',
          body: JSON.stringify({ app, instance, consumer, action }),
        });
        this.notify(`${action} sent to ${app} (${where}); it applies within seconds`);
        setTimeout(() => this.loadConsumers(true), 1500);
        setTimeout(() => this.loadConsumers(true), 4000);
      } catch (e) {
        this.notify('Not sent: ' + e.message, 'error');
      } finally {
        this.cons.busy = false;
      }
    },

    /**
     * Describes one probe of a replica's last connection check.
     * @param {object} check the replica's last connection check
     * @param {'mongodb'|'pulsar'} name which probe
     * @returns {string} e.g. "✓ 3 ms" or "✕ timeout"
     */
    probeLabel(check, name) {
      const r = check?.results?.[name];
      if (!r) return '—';
      return r.up ? `✓ ${r.millis} ms` : `✕ ${r.error || 'down'}`;
    },

    /**
     * Lists the known DLQs (of the apps' consumers and of the bridges) with how many messages wait in each.
     * @param {boolean} [quiet] no spinner
     */
    async loadDeadLetters(quiet = false) {
      if (!quiet) this.cons.dlqLoading = true;
      try {
        const found = new Map();
        this.cons.list.forEach((c) => {
          if (c.deadLetterTopic) found.set(c.deadLetterTopic, `${c.app} · ${c.consumer}`);
        });
        try {
          const status = await this.bridgesApi(`${BRIDGES_API}/status`);
          (status.workers || []).forEach((w) => {
            if (w.deadLetterTopic) found.set(w.deadLetterTopic, `bridge · ${w.name}`);
          });
        } catch {
          /* Cataponte down: only the apps' DLQs */
        }
        const dlqs = await Promise.all(
          [...found.entries()].map(async ([topic, from]) => {
            const m = topic.match(/^persistent:\/\/([^/]+)\/([^/]+)\/(.+)$/);
            if (!m) return { topic, from, backlog: null, missing: true };
            try {
              const stats = await this.api(`/api/v1/topics/${m[1]}/${m[2]}/${encodeURIComponent(m[3])}/stats`);
              const sub = stats?.subscriptions?.dlq;
              return { topic, from, backlog: sub ? (sub.msgBacklog ?? 0) : 0, missing: false };
            } catch {
              return { topic, from, backlog: 0, missing: true }; // created with the first dead letter
            }
          }),
        );
        dlqs.sort((a, b) => (b.backlog ?? 0) - (a.backlog ?? 0) || a.topic.localeCompare(b.topic));
        if (JSON.stringify(dlqs) !== JSON.stringify(this.cons.dlqs)) this.cons.dlqs = dlqs;
      } finally {
        this.cons.dlqLoading = false;
      }
    },

    /**
     * Shows the first messages of a DLQ (a peek, recorded in the audit log).
     * @param {string} topic the DLQ
     */
    async peekDeadLetters(topic) {
      const m = topic.match(/^persistent:\/\/([^/]+)\/([^/]+)\/(.+)$/);
      if (!m) return;
      this.cons.open = topic;
      this.cons.peeking = true;
      try {
        this.cons.messages = await this.api(
          `/api/v1/topics/${m[1]}/${m[2]}/${encodeURIComponent(m[3])}/peek/dlq?count=50`,
        );
      } catch (e) {
        this.cons.messages = [];
        this.notify('Peek: ' + e.message, 'error');
      } finally {
        this.cons.peeking = false;
      }
    },

    /**
     * Sends back or discards one dead letter, or all of them, after confirmation; each is audited.
     * @param {'send-back'|'discard'|'send-back-all'|'discard-all'} action what to do
     * @param {?object} message the message, for the single actions
     */
    async deadLetterAction(action, message = null) {
      const topic = this.cons.open;
      const dlq = this.cons.dlqs.find((d) => d.topic === topic);
      const target = message?.properties?.REAL_TOPIC;
      const texts = {
        'send-back': `Send message ${message?.messageId} back to ${target}?\nIt is removed from the DLQ once sent.`,
        discard: `Discard message ${message?.messageId}? It is removed from the DLQ and lost.`,
        'send-back-all': `Send every message of ${topic} (${dlq?.backlog ?? '?'}) back to the topic it came from?`,
        'discard-all': `Discard every message of ${topic} (${dlq?.backlog ?? '?'})? They are lost.`,
      };
      if (!confirm(texts[action])) return;
      this.cons.busy = true;
      try {
        const body = { topic };
        if (message) body.messageId = message.messageId;
        const r = await this.api(`/api/v1/dead-letters/${action}`, { method: 'POST', body: JSON.stringify(body) });
        this.notify(
          {
            'send-back': `Sent back to ${r?.to}`,
            discard: 'Discarded',
            'send-back-all': `${r?.sent ?? 0} sent back${r?.left ? `, ${r.left} left (no origin)` : ''}`,
            'discard-all': 'DLQ emptied',
          }[action],
        );
        await this.loadDeadLetters(true);
        // A new peek is audited, so only when something is left to look at.
        if (action === 'discard-all' || (action === 'send-back-all' && !r?.left)) this.cons.messages = [];
        else await this.peekDeadLetters(topic);
      } catch (e) {
        this.notify('Dead letters: ' + e.message, 'error');
      } finally {
        this.cons.busy = false;
      }
    },

    /** Loads the deliveries before a DLQ (shared setting). */
    async loadDlqSetting() {
      try {
        const r = await this.api('/api/v1/settings/dead-letters');
        Object.assign(this.dlqSetting, r, { draft: r.value });
      } catch (e) {
        this.notify('Settings: ' + e.message, 'error');
      }
    },

    /**
     * Changes or resets the deliveries before a DLQ; the apps subscribe again with it.
     * @param {'PUT'|'DELETE'} method PUT saves the draft, DELETE goes back to the default
     * @returns {Promise<boolean>} whether it was saved
     */
    async saveDlqSetting(method) {
      try {
        const options = { method };
        if (method === 'PUT') options.body = JSON.stringify({ value: Number(this.dlqSetting.draft) });
        const r = await this.api('/api/v1/settings/dead-letters', options);
        Object.assign(this.dlqSetting, r, { draft: r.value });
        this.notify(`A message goes to its DLQ after ${r.value} deliveries`);
        return true;
      } catch (e) {
        this.notify('Deliveries not saved: ' + e.message, 'error');
        return false;
      }
    },

    // ── Audit trail ────────────────────────────────────────────────────────
    /**
     * Opens the Audit panel (closing the other panels) on its first page.
     */
    openAudit() {
      if (this.bridgesView) this.closeBridges();
      this.consumersView = false;
      this.alertsView = false;
      this.auditView = true;
      this.loadCatauditSettings();
      this.audit.page = 0;
      this.audit.expanded = null;
      this.loadAuditFacets();
      this.loadAudit();
    },

    // ── Cataudit URL (stored in MongoDB, audited) ───────────────────────────
    /**
     * Reads Cataudit's URL setting.
     */
    async loadCatauditSettings() {
      try {
        Object.assign(this.audit.settings, await this.api('/api/v1/settings/cataudit'));
      } catch (e) {
        this.notify('Settings: ' + e.message, 'error');
      }
    },

    /**
     * Goes back to the default Cataudit URL, after a confirmation.
     */
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

    /**
     * Closes the Audit panel.
     */
    closeAudit() {
      this.auditView = false;
      this.audit.expanded = null;
    },

    /** Loads the apps, entities and actions in use for the filter lists; failures are ignored. */
    async loadAuditFacets() {
      try {
        this.audit.facets = await this.api(`${AUDIT_API}/facets`);
      } catch {
        /* lists stay as they are */
      }
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
        if (key !== JSON.stringify([a.filters, a.page])) return; // filters or page changed meanwhile
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
      try {
        localStorage.setItem('auditPeriod', this.audit.filters.period);
      } catch {
        /* not persisted */
      }
      this.loadAudit();
    },

    /**
     * Clears the audit trail's filters (the period stays) and searches again.
     */
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

    /**
     * Counts the audit trail's pages for the current filters.
     * @returns {number} pages, at least 1
     */
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
     * Expands or collapses an audit entry.
     * @param {string} id entry id
     */
    toggleAuditEntry(id) {
      this.audit.expanded = this.audit.expanded === id ? null : id;
    },

    /**
     * Formats an instant of the audit trail.
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
     * Lists the keys an audit entry has before or after the change.
     * @param {object} entry audit entry
     * @returns {string[]} sorted keys
     */
    auditChangedKeys(entry) {
      const keys = new Set([...Object.keys(entry.before || {}), ...Object.keys(entry.after || {})]);
      return [...keys].sort();
    },

    // ── Bridges ────────────────────────────────────────────────────────────
    /**
     * Opens the Bridges panel (closing the other panels) and loads the bridges, their status and the areas.
     */
    openBridges() {
      if (this.auditView) this.closeAudit();
      this.consumersView = false;
      this.alertsView = false;
      this.bridgesView = true;
      this.loadCataponteSettings();
      this.loadBridges();
      this.loadBridgeStatus();
      this.loadAreas(true);
    },

    /**
     * Counts the bridges of the open tab whose worker is relaying messages right now.
     * @returns {number} running bridges
     */
    runningBridges() {
      return this.tabBridges().filter((b) => this.bridges.status[b.id]?.running).length;
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
      } catch {
        /* keep the current areas; Cataponte errors show with the bridges */
      }
      if (restoreTab) {
        let stored = null;
        try {
          stored = localStorage.getItem(BRIDGES_TAB_KEY);
        } catch {
          /* not remembered */
        }
        this.bridges.tab = this.bridgeTabExists(stored) ? stored : 'system';
      } else if (!this.bridgeTabExists(this.bridges.tab)) {
        this.bridges.tab = 'system';
      }
    },

    /**
     * Tells whether a Bridges tab exists.
     * @param {?string} key tab key: 'system', 'general' or an area id
     * @returns {boolean} whether the tab exists
     */
    bridgeTabExists(key) {
      return key === 'system' || key === 'general' || this.bridges.areas.some((a) => a.id === key);
    },

    /**
     * Lists the Bridges tabs: System, Bridges, then one per area.
     * @returns {Array<{key: string, label: string, count: number}>} the tabs, with how many bridges each has
     */
    bridgeTabs() {
      const list = this.bridges.list;
      return [
        { key: 'system', label: 'System', count: list.filter((b) => b.system).length },
        { key: 'general', label: 'Bridges', count: list.filter((b) => this.inGeneralGroup(b)).length },
        ...this.bridges.areas.map((a) => ({
          key: a.id,
          label: a.name,
          count: list.filter((b) => b.areaId === a.id).length,
        })),
      ];
    },

    /**
     * Bridges without an area, or whose area no longer exists, are in the general group.
     * @param {object} bridge the bridge
     * @returns {boolean} true when it belongs to the Bridges tab
     */
    inGeneralGroup(bridge) {
      return !bridge.system && (!bridge.areaId || !this.bridges.areas.some((a) => a.id === bridge.areaId));
    },

    /**
     * Lists the bridges of the open tab.
     * @returns {object[]} bridges
     */
    tabBridges() {
      const tab = this.bridges.tab;
      if (tab === 'system') return this.bridges.list.filter((b) => b.system);
      if (tab === 'general') return this.bridges.list.filter((b) => this.inGeneralGroup(b));
      return this.bridges.list.filter((b) => b.areaId === tab);
    },

    /**
     * Opens a tab and remembers it in this browser.
     * @param {string} key 'system', 'general' or an area id
     */
    selectBridgeTab(key) {
      this.bridges.tab = key;
      try {
        localStorage.setItem(BRIDGES_TAB_KEY, key);
      } catch {
        /* not remembered */
      }
    },

    /**
     * Gives the area of the open Bridges tab.
     * @returns {?object} the area, or null on System and Bridges
     */
    currentArea() {
      return this.bridges.areas.find((a) => a.id === this.bridges.tab) ?? null;
    },

    /**
     * Finds an area.
     * @param {?string} id area id
     * @returns {?object} the area, or null
     */
    areaById(id) {
      return id ? (this.bridges.areas.find((a) => a.id === id) ?? null) : null;
    },

    /**
     * Opens the form for a new area, loading the topics for its preview.
     */
    openNewArea() {
      this.bridges.areaForm = { id: null, name: '', description: '', patternsText: '', version: null };
      if (this.bridges.topics.length === 0) this.loadAllTopics();
    },

    /**
     * Opens the form to edit an area.
     * @param {object} area the area
     */
    openEditArea(area) {
      this.bridges.areaForm = {
        id: area.id,
        name: area.name,
        description: area.description || '',
        patternsText: (area.patterns || []).join('\n'),
        version: area.version,
      };
      if (this.bridges.topics.length === 0) this.loadAllTopics();
    },

    /**
     * Reads the patterns typed in the area form.
     * @returns {string[]} one per non-empty line
     */
    areaFormPatterns() {
      return (this.bridges.areaForm?.patternsText || '')
        .split('\n')
        .map((p) => p.trim())
        .filter(Boolean);
    },

    /**
     * Lists the existing topics the area form's patterns match, for the preview.
     * @returns {string[]} matching topics; none while a pattern is invalid
     */
    areaFormMatches() {
      const patterns = this.areaFormPatterns();
      try {
        return this.bridges.topics.filter((t) => areaAllows(patterns, t));
      } catch {
        return [];
      }
    },

    /**
     * Creates or updates the area in the form, then reloads the areas.
     */
    async saveArea() {
      const f = this.bridges.areaForm;
      const isNew = !f.id;
      this.bridges.areaSaving = true;
      try {
        const payload = {
          name: f.name.trim(),
          description: f.description,
          patterns: this.areaFormPatterns(),
          version: f.version ?? null,
        };
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

    /**
     * Starts deleting an area: an empty one only needs a confirmation, otherwise a dialog asks what happens to its bridges.
     * @param {object} area the area
     */
    askDeleteArea(area) {
      const count = this.bridges.list.filter((b) => b.areaId === area.id).length;
      if (count === 0) {
        if (confirm(`Delete area "${area.name}"? It has no bridges.`)) this.deleteArea(area, null, null);
        return;
      }
      this.bridges.areaDelete = { area, count, choice: 'move', to: '' };
    },

    /** Applies the choice of the delete dialog. */
    confirmDeleteArea() {
      const d = this.bridges.areaDelete;
      if (
        d.choice === 'delete' &&
        !confirm(
          `Delete area "${d.area.name}" and its ${d.count} bridge(s)? Their relays stop immediately,` +
            ' and their subscriptions are deleted about 30 s later with whatever is in their backlog.',
        )
      ) {
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

    /**
     * Lists the topics of the bridge form that its area's patterns do not allow.
     * @returns {string[]} topics outside the area
     */
    formOutsideTopics() {
      const f = this.bridges.form;
      const area = this.areaById(f?.areaId);
      if (!f || !area) return [];
      const topics = [f.source.topic, ...f.destinations.map((d) => d.topic)].filter(Boolean);
      return topics.filter((t) => !areaAllows(area.patterns, t));
    },

    // ── Cataponte URL (stored in MongoDB, audited) ──────────────────────────
    /**
     * Reads Cataponte's URL setting.
     */
    async loadCataponteSettings() {
      try {
        Object.assign(this.bridges.settings, await this.api('/api/v1/settings/cataponte'));
      } catch (e) {
        this.notify('Settings: ' + e.message, 'error');
      }
    },

    /**
     * Goes back to the default Cataponte URL, after a confirmation.
     */
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

    /**
     * Closes the Bridges panel and its form.
     */
    closeBridges() {
      this.bridgesView = false;
      this.bridges.form = null;
    },

    /**
     * Loads the bridge list, with a spinner; Cataponte errors show as a toast.
     */
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
      } catch {
        /* keep the current list */
      }
      await this.loadAreas();
    },

    /**
     * Loads the bridges' runtime status, by bridge id; ignored when Cataponte is down.
     */
    async loadWorkerStatus() {
      try {
        const s = await this.bridgesApi(`${BRIDGES_API}/status`);
        const map = {};
        (s.workers || []).forEach((w) => {
          map[w.bridgeId] = w;
        });
        this.bridges.status = map;
      } catch {
        /* ignore — Cataponte may not be running */
      }
    },

    /**
     * Loads the bridges' all-time traffic counts, by bridge and topic; ignored when Cataponte is down.
     */
    async loadBridgeTraffic() {
      try {
        const totals = await this.bridgesApi(`${BRIDGES_API}/traffic`);
        const map = {};
        (totals || []).forEach((t) => {
          (map[t.bridgeId] ??= {})[`${t.role} ${t.topic}`] = t;
        });
        this.bridges.traffic = map;
      } catch {
        /* ignore — counts are optional */
      }
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
      return (
        `${topic}\n${this.fmtNum(t.messages)} messages, ${this.fmtBytes(t.bytes)}` +
        `\nfirst ${this.fmtDate(t.firstAt)} · last ${this.fmtDate(t.lastAt)}`
      );
    },

    /**
     * Enables or disables a bridge, then reloads the list and status.
     * @param {object} bridge the bridge
     */
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
      if (
        !confirm(
          `Skip message ${id} of bridge "${bridge.name}"?\n\nThe destination refuses it: ${status.lastError || 'unknown error'}.` +
            `\nIt will not be relayed to ${this.bridgeDestTopics(bridge).join(', ')}; it stays in ${bridge.source?.topic} until` +
            ' its retention ends. A message sent in a batch is skipped with its whole batch.',
        )
      )
        return;
      try {
        await this.bridgesApi(`${BRIDGES_API}/${bridge.id}/skip`, {
          method: 'POST',
          body: JSON.stringify({ messageId: id }),
        });
        this.notify(`Message ${id} skipped`);
        await this.loadBridgeStatus();
      } catch (e) {
        this.notify('Cataponte: ' + e.message, 'error');
      }
    },

    /**
     * Deletes a bridge after a confirmation that names it and warns about its subscription.
     * @param {string} id bridge id
     */
    async deleteBridge(id) {
      const bridge = this.bridges.list.find((b) => b.id === id);
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

    /**
     * Opens the form for a new bridge, in the area of the open tab, and loads the topics for its pickers.
     */
    openNewBridge() {
      this.bridges.form = {
        id: null,
        name: '',
        description: '',
        enabled: true,
        source: { topic: '', subscription: '', subscriptionType: 'Failover' },
        subAuto: true, // the subscription follows the name (cataponte-<name>) until it is edited
        ordered: true,
        destinations: [],
        preserveKey: true,
        preserveProperties: true,
        areaId: this.currentArea()?.id ?? null,
      };
      this.bridges.srcFilter = '';
      this.bridges.dstFilter = '';
      this.loadAllTopics();
    },

    /**
     * Opens the form to edit a bridge, remembering its source to warn if it changes.
     * @param {object} bridge the bridge
     */
    openEditBridge(bridge) {
      const f = JSON.parse(JSON.stringify(bridge));
      f.subAuto = false;
      f.originalSource = { topic: bridge.source?.topic, subscription: bridge.source?.subscription };
      f.destinations = this.bridgeDestTopics(f).map((topic) => ({ topic }));
      delete f.destination;
      this.bridges.form = f;
      this.bridges.srcFilter = '';
      this.bridges.dstFilter = '';
      this.loadAllTopics();
    },

    // Destination topics of a bridge.
    /**
     * Lists the destination topics of a bridge.
     * @param {?object} b bridge
     * @returns {string[]} its destination topics
     */
    bridgeDestTopics(b) {
      return (b?.destinations || []).map((d) => d.topic).filter(Boolean);
    },

    /**
     * Tells whether a topic is a destination in the bridge form.
     * @param {string} t fully-qualified topic
     * @returns {boolean} whether it is selected
     */
    isDestSelected(t) {
      return this.bridges.form?.destinations.some((d) => d.topic === t);
    },

    /**
     * Adds a topic to the bridge form's destinations, or removes it.
     * @param {string} t fully-qualified topic
     */
    toggleDestination(t) {
      const ds = this.bridges.form.destinations;
      const i = ds.findIndex((d) => d.topic === t);
      if (i >= 0) ds.splice(i, 1);
      else ds.push({ topic: t });
    },

    /**
     * Tells whether the bridge form can be saved.
     * @returns {boolean} name, source, subscription and a destination are set, and nothing conflicts
     */
    bridgeFormValid() {
      const f = this.bridges.form;
      return Boolean(
        f &&
        f.name &&
        f.source.topic &&
        f.source.subscription &&
        f.destinations.length > 0 &&
        !this.orderedOnShared() &&
        this.formOutsideTopics().length === 0,
      );
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
    /**
     * Lists every topic, walking tenants and namespaces through Catapulsar's API, for the bridge pickers (system topics hidden).
     */
    async loadAllTopics() {
      this.bridges.topicsLoading = true;
      try {
        const tenants = await this.api('/api/v1/tenants');
        const perNs = await Promise.all(
          tenants.map(async (t) => {
            const nss = await this.api(`/api/v1/namespaces/${t}`).catch(() => []);
            return Promise.all(
              nss.map(async (nsRaw) => {
                const ns = nsRaw.includes('/') ? nsRaw.split('/').pop() : nsRaw;
                const topics = await this.api(`/api/v1/topics/${t}/${ns}`).catch(() => []);
                return topics.map((tp) => (tp.includes('://') ? tp : `persistent://${t}/${ns}/${tp}`));
              }),
            );
          }),
        );
        this.bridges.topics = perNs
          .flat(2)
          .filter((n) => !n.split('/').pop().startsWith('__')) // hide system topics
          .sort();
      } catch (e) {
        this.notify('Error loading topics: ' + e.message, 'error');
      } finally {
        this.bridges.topicsLoading = false;
      }
    },

    /**
     * Lists the topics a bridge picker offers.
     * @param {string} filter text typed in the picker
     * @param {string|string[]} [exclude] topics already used elsewhere in the form
     * @returns {string[]} topics matching the filter and allowed by the form's area
     */
    filteredTopics(filter, exclude = []) {
      const f = (filter || '').toLowerCase();
      const ex = Array.isArray(exclude) ? exclude : [exclude];
      const area = this.areaById(this.bridges.form?.areaId);
      return this.bridges.topics.filter(
        (t) => !ex.includes(t) && (!f || t.toLowerCase().includes(f)) && (!area || areaAllows(area.patterns, t)),
      );
    },

    /**
     * Closes the bridge form without saving.
     */
    closeBridgeForm() {
      this.bridges.form = null;
    },

    /** Keeps the subscription name at cataponte-<bridge name> until the user edits it. */
    onBridgeNameInput() {
      const f = this.bridges.form;
      if (f?.subAuto) f.source.subscription = f.name ? `cataponte-${f.name}` : '';
    },

    /**
     * Creates or updates the bridge in the form (asking first when an edit changes its source), then reloads the list.
     */
    async saveBridge() {
      const f = this.bridges.form;
      const isNew = !f.id;
      const o = f.originalSource;
      if (!isNew && o && (o.topic !== f.source.topic || o.subscription !== f.source.subscription)) {
        const old = this.bridges.list.find((b) => b.id === f.id);
        const warning = old ? await this.subscriptionWarning(old) : '';
        if (
          !confirm(
            `Save bridge "${f.name}" with another source or subscription?${warning.replace('Its subscription', 'Its old subscription')}`,
          )
        )
          return;
      }
      this.bridges.formSaving = true;
      try {
        const payload = {
          name: f.name,
          description: f.description,
          enabled: f.enabled,
          source: f.source,
          destinations: f.destinations,
          preserveKey: f.preserveKey,
          preserveProperties: f.preserveProperties,
          ordered: f.ordered,
          areaId: f.system ? null : f.areaId || null,
          version: f.version ?? null,
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
    /**
     * Formats a rate with two decimals.
     * @param {?number} n value
     * @returns {string} e.g. "1.50", or "—" when missing
     */
    fmt(n) {
      return n == null ? '—' : parseFloat(n).toFixed(2);
    },
    /**
     * Formats a whole number with thousands separators.
     * @param {?number} n value
     * @returns {string} e.g. "1,234", or "—" when missing
     */
    fmtNum(n) {
      return n == null ? '—' : parseInt(n).toLocaleString('en');
    },
    /**
     * Formats a byte count; a negative or missing value (Pulsar's "not computed") is shown as "—".
     * @param {?number} b bytes
     * @returns {string} e.g. "1.5 KB"
     */
    fmtBytes(b) {
      if (b == null || b < 0) return '—';
      if (!b) return '0 B';
      const k = 1024,
        sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
      const i = Math.floor(Math.log(b) / Math.log(k));
      return (b / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i];
    },
    /**
     * Formats a timestamp as a short date and time.
     * @param {?(number|string)} ts epoch milliseconds or ISO-8601
     * @returns {string} e.g. "13/11/2026, 21:00:00", or "—" when missing
     */
    fmtDate(ts) {
      if (!ts) return '—';
      return new Date(ts).toLocaleString('en-GB', { dateStyle: 'short', timeStyle: 'medium' });
    },
    /**
     * Tells whether a subscription is Pulsar's own (e.g. __compaction): it can be peeked but not changed.
     * @param {?string} name subscription name
     * @returns {boolean} true for names starting with "__"
     */
    isSystemSub(name) {
      return typeof name === 'string' && name.startsWith('__');
    },

    /**
     * Gives the full name of the selected topic.
     * @returns {string} e.g. persistent://home/media/events
     */
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
      return (
        this.bridges.list.find(
          (b) => b.source?.subscription === name && (b.source?.topic === topic || b.source?.topic === base),
        ) ?? null
      );
    },

    /**
     * Tells which Bridges tab shows a bridge.
     * @param {object} bridge a bridge
     * @returns {string} 'system', 'general' or its area id
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
      try {
        localStorage.setItem(BRIDGES_TAB_KEY, this.bridgeTabOf(bridge));
      } catch {
        /* not remembered */
      }
      this.bridges.highlight = bridge.id;
      this.openBridges();
      await this.loadAreas();
      this.selectBridgeTab(this.bridgeTabOf(bridge));
      setTimeout(() => document.getElementById(`bridge-card-${bridge.id}`)?.scrollIntoView({ block: 'center' }), 300);
      setTimeout(() => {
        if (this.bridges.highlight === bridge.id) this.bridges.highlight = null;
      }, 4000);
    },

    /**
     * Shows or hides a subscription's consumer list.
     * @param {string} name subscription name
     */
    toggleSubConsumers(name) {
      this.subConsumersOpen = { ...this.subConsumersOpen, [name]: !this.subConsumersOpen[name] };
    },

    /**
     * Short "who is connected" line for a subscription: the consumer names, or their addresses.
     * @param {object} stats subscription statistics
     * @returns {string} e.g. "cataponte@pod-1/fanout, 10.42.0.7:51234", or '' with no consumers
     */
    consumersLine(stats) {
      return (stats?.consumers ?? []).map((c) => c.consumerName || c.address || '?').join(', ');
    },

    /**
     * Warning about the subscription of a bridge that is deleted or moved to another one (orch.md decision 60):
     * kept when another bridge uses it, otherwise deleted by Cataponte about 30 s later with its backlog.
     * @param {object} bridge the bridge as it is now
     * @returns {Promise<string>} the text to add to the confirmation
     */
    async subscriptionWarning(bridge) {
      const topic = bridge.source?.topic,
        sub = bridge.source?.subscription;
      if (!topic || !sub) return '';
      const other = this.bridges.list.find(
        (b) => b.id !== bridge.id && b.source?.topic === topic && b.source?.subscription === sub,
      );
      if (other) return `\n\nIts subscription "${sub}" is kept: bridge "${other.name}" uses it too.`;
      let backlog = null;
      const m = topic.match(/^persistent:\/\/([^/]+)\/([^/]+)\/(.+)$/);
      if (m) {
        try {
          const stats = await this.api(`/api/v1/topics/${m[1]}/${m[2]}/${encodeURIComponent(m[3])}/stats`);
          backlog = stats?.subscriptions?.[sub]?.msgBacklog ?? 0;
        } catch {
          /* unknown */
        }
      }
      const what =
        backlog == null
          ? 'with whatever is in its backlog (could not be read)'
          : backlog === 0
            ? 'its backlog is empty'
            : `with the ${this.fmtNum(backlog)} message(s) still in its backlog; they will not reach the destinations`;
      return `\n\nIts subscription "${sub}" on ${topic} will also be deleted about 30 s later (${what}).`;
    },
    /**
     * Adds up the backlog of every subscription of a topic.
     * @param {?object} stats topic statistics
     * @returns {number} messages waiting
     */
    sumBacklog(stats) {
      if (!stats?.subscriptions) return 0;
      return Object.values(stats.subscriptions).reduce((s, sub) => s + (sub.msgBacklog ?? 0), 0);
    },
    /**
     * Shortens a text with an ellipsis.
     * @param {?string} s text
     * @param {number} n maximum length
     * @returns {string} the text, cut to n characters
     */
    truncate(s, n) {
      if (!s) return '';
      return s.length > n ? s.slice(0, n) + '…' : s;
    },
    /**
     * Indents a JSON text; anything else is returned as it is.
     * @param {?string} s text
     * @returns {string} the indented JSON, or the text
     */
    prettyJson(s) {
      if (!s) return '';
      try {
        return JSON.stringify(JSON.parse(s), null, 2);
      } catch {
        return s;
      }
    },
  };
}
