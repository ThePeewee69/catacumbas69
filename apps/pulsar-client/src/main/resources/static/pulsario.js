function app() {
  return {
    // ── State ──────────────────────────────────────────────────────────────
    tenants: [], namespaces: [], topics: [],
    sel: { tenant: null, namespace: null, topic: null },
    tab: 'stats',
    stats: null,
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

    // ── Init ───────────────────────────────────────────────────────────────
    async init() {
      await this.loadTenants();
    },

    async reload() {
      if (this.sel.topic) await this.selectTopic(this.sel.topic);
      else if (this.sel.namespace) await this.selectNamespace(this.sel.namespace);
      else if (this.sel.tenant) await this.selectTenant(this.sel.tenant);
      else await this.loadTenants();
    },

    // ── Settings ───────────────────────────────────────────────────────────
    saveSettings() {
      localStorage.setItem('pulsarUrl', this.settings.pulsarUrl);
      this.settings.open = false;
      this.notify('Settings saved');
    },

    async checkHealth() {
      this.settings.healthState = 'checking';
      this.settings.healthMsg = '';
      try {
        const r = await fetch(this.settings.pulsarUrl + '/admin/v2/clusters');
        if (r.ok) {
          const clusters = await r.json();
          this.settings.healthState = 'ok';
          this.settings.healthMsg = 'clusters: ' + clusters.join(', ');
        } else {
          this.settings.healthState = 'error';
          this.settings.healthMsg = 'HTTP ' + r.status;
        }
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
          `/api/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/produce`,
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
          `/api/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/subscriptions/${encodeURIComponent(this.backlog.sub)}/skip/${n}`,
          { method: 'POST' }
        );
        this.notify(`Skipped ${n} message(s) from "${this.backlog.sub}"`);
        await this.peekMessages();
      } catch (e) { this.notify('Skip failed: ' + e.message, 'error'); }
    },

    async skipAll() {
      const n = this.backlog.messages.length;
      await this.skipMessages(n);
    },

    // ── API helper ─────────────────────────────────────────────────────────
    async api(url, options = {}) {
      const res = await fetch(url, {
        headers: { 'Content-Type': 'application/json' },
        ...options
      });
      const ct = res.headers.get('content-type') ?? '';
      const body = ct.includes('json') ? await res.json() : await res.text();
      if (!res.ok) throw new Error(body?.error ?? body ?? res.statusText);
      return body;
    },

    notify(msg, type = 'success') {
      this.toast = { msg, type };
      setTimeout(() => this.toast = { msg: '', type: 'success' }, 3500);
    },

    // ── Tenants / Namespaces / Topics ──────────────────────────────────────
    async loadTenants() {
      this.loading.tenants = true;
      try { this.tenants = await this.api('/api/tenants'); }
      catch (e) { this.notify('Error loading tenants: ' + e.message, 'error'); }
      finally { this.loading.tenants = false; }
    },

    async selectTenant(t) {
      this.sel = { tenant: t, namespace: null, topic: null };
      this.namespaces = []; this.topics = [];
      this.stats = null; this.subscriptions = [];
      this.loading.namespaces = true;
      try { this.namespaces = await this.api(`/api/namespaces/${t}`); }
      catch (e) { this.notify('Error loading namespaces: ' + e.message, 'error'); }
      finally { this.loading.namespaces = false; }
    },

    async selectNamespace(ns) {
      this.sel = { ...this.sel, namespace: ns, topic: null };
      this.topics = []; this.stats = null; this.subscriptions = [];
      this.loading.topics = true;
      try { this.topics = await this.api(`/api/topics/${this.sel.tenant}/${ns}`); }
      catch (e) { this.notify('Error loading topics: ' + e.message, 'error'); }
      finally { this.loading.topics = false; }
    },

    async selectTopic(t) {
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
        await this.api(`/api/topics/${this.sel.tenant}/${this.sel.namespace}/${this.newTopic}`, { method: 'POST' });
        this.notify(`Topic "${this.newTopic}" created`);
        this.newTopic = '';
        await this.selectNamespace(this.sel.namespace);
      } catch (e) { this.notify('Error: ' + e.message, 'error'); }
    },

    async deleteTopic(t) {
      if (!confirm(`Delete topic "${t}"?\nAll messages and subscriptions will be removed.`)) return;
      try {
        await this.api(`/api/topics/${this.sel.tenant}/${this.sel.namespace}/${t}`, { method: 'DELETE' });
        this.notify(`Topic "${t}" deleted`);
        if (this.sel.topic === t) this.sel.topic = null;
        await this.selectNamespace(this.sel.namespace);
      } catch (e) { this.notify('Error: ' + e.message, 'error'); }
    },

    // ── Stats ──────────────────────────────────────────────────────────────
    async loadStats() {
      this.loading.stats = true; this.stats = null;
      try {
        this.stats = await this.api(`/api/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/stats`);
      } catch (e) { this.notify('Error loading stats: ' + e.message, 'error'); }
      finally { this.loading.stats = false; }
    },

    // ── Subscriptions ──────────────────────────────────────────────────────
    async loadSubs() {
      if (!this.sel.topic) return;
      this.loading.subs = true;
      try {
        this.subscriptions = await this.api(`/api/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/subscriptions`);
      } catch (e) { this.subscriptions = []; }
      finally { this.loading.subs = false; }
    },

    async createSub() {
      if (!this.newSub.trim()) return;
      this.loading.newSub = true;
      try {
        await this.api(`/api/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/subscriptions/${this.newSub}`, { method: 'POST' });
        this.notify(`Subscription "${this.newSub}" created`);
        this.newSub = '';
        await this.loadSubs();
      } catch (e) { this.notify('Error: ' + e.message, 'error'); }
      finally { this.loading.newSub = false; }
    },

    async deleteSub(sub) {
      if (!confirm(`Delete subscription "${sub}"?`)) return;
      try {
        await this.api(`/api/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/subscriptions/${sub}`, { method: 'DELETE' });
        this.notify(`Subscription "${sub}" deleted`);
        await this.loadSubs();
      } catch (e) { this.notify('Error: ' + e.message, 'error'); }
    },

    // ── Produce ────────────────────────────────────────────────────────────
    async sendMessage() {
      if (!this.produce.payload.trim()) return;
      this.loading.produce = true; this.produce.lastId = null;
      try {
        const res = await this.api(`/api/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/produce`, {
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
    async peekMessages() {
      if (!this.backlog.sub) return;
      this.loading.backlog = true; this.backlog.messages = []; this.backlog.queried = false;
      try {
        this.backlog.messages = await this.api(
          `/api/topics/${this.sel.tenant}/${this.sel.namespace}/${this.sel.topic}/peek/${this.backlog.sub}?count=${this.backlog.count}`
        );
        this.backlog.queried = true;
      } catch (e) { this.notify('Peek error: ' + e.message, 'error'); }
      finally { this.loading.backlog = false; }
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
