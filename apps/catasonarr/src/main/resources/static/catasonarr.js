// Catasonarr's pages (orch.md decisions 109, 112, 114 and 115): the series, adding a series, importing the series
// already on the NAS, a series with its episodes, and the settings. They use Sonarr's API v3 with the Dex session and the CSRF token, as Sonarr's own page uses it.

/** Grows on every page change, so work a page left behind (the import's searches) stops. */
let generation = 0;

/** The cookie Spring Security writes the CSRF token into (SEC-2). */
const CSRF_COOKIE = 'XSRF-TOKEN';

/** Sonarr's monitor options when a series is added. */
const MONITOR_OPTIONS = [
  ['all', 'All episodes'],
  ['future', 'Future episodes'],
  ['missing', 'Missing episodes'],
  ['existing', 'Existing episodes'],
  ['recent', 'Recent episodes'],
  ['pilot', 'Pilot episode'],
  ['firstSeason', 'First season'],
  ['lastSeason', 'Last season'],
  ['none', 'None'],
];

const page = document.getElementById('page');

/**
 * Reads the CSRF token from its cookie.
 *
 * @returns {string} the token, or an empty string when there is none
 */
function csrfToken() {
  const cookie = document.cookie.split('; ').find((c) => c.startsWith(`${CSRF_COOKIE}=`));
  return cookie ? decodeURIComponent(cookie.slice(CSRF_COOKIE.length + 1)) : '';
}

/**
 * Calls Catasonarr's API.
 *
 * @param {string} method GET, POST, PUT or DELETE
 * @param {string} path e.g. /api/v3/series
 * @param {object} [body] sent as JSON
 * @returns {Promise<any>} the answer's JSON, or null when empty
 */
async function api(method, path, body) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (method !== 'GET') {
    headers['X-XSRF-TOKEN'] = csrfToken();
  }
  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (response.status === 401) {
    window.location.reload(); // the session expired: the reload goes through Dex
    throw new Error('Signed out');
  }
  const text = await response.text();
  const json = text ? JSON.parse(text) : null;
  if (!response.ok) {
    const message = Array.isArray(json) ? json.map((f) => f.errorMessage).join('; ') : json && json.message;
    throw new Error(message || `${response.status} ${response.statusText}`);
  }
  return json;
}

/**
 * Creates an element.
 *
 * @param {string} tag the tag
 * @param {object} [props] properties, e.g. className, textContent, href
 * @param {Array<Node|string>} [children] what goes inside
 * @returns {HTMLElement} the element
 */
function el(tag, props = {}, children = []) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (key === 'dataset') {
      Object.assign(node.dataset, value);
    } else if (key.startsWith('on')) {
      node.addEventListener(key.slice(2), value);
    } else if (key.includes('-')) {
      node.setAttribute(key, value);
    } else {
      node[key] = value;
    }
  });
  children.filter((c) => c !== null && c !== undefined).forEach((c) => node.append(c));
  return node;
}

/**
 * Shows a short message at the bottom right.
 *
 * @param {string} text the message
 * @param {boolean} [error] whether it is an error
 */
function toast(text, error = false) {
  const box = document.getElementById('toast');
  box.textContent = text;
  box.classList.toggle('error', error);
  box.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => {
    box.hidden = true;
  }, 6000);
}

/**
 * A poster, or a grey box when there is none.
 *
 * @param {string} [url] the poster's address
 * @param {string} title the series' title, for the image's text
 * @returns {HTMLElement} the image
 */
function poster(url, title) {
  return url ? el('img', { src: url, alt: title, loading: 'lazy' }) : el('div', { className: 'poster-missing' });
}

/**
 * Bytes as GB or TB.
 *
 * @param {number} bytes the bytes
 * @returns {string} e.g. 1.2 TB
 */
function size(bytes) {
  if (bytes === null || bytes === undefined) {
    return '?';
  }
  const tb = bytes / 1024 ** 4;
  return tb >= 1 ? `${tb.toFixed(1)} TB` : `${(bytes / 1024 ** 3).toFixed(1)} GB`;
}

/**
 * A date in the user's format.
 *
 * @param {string} [iso] the date
 * @returns {string} the date, or a dash
 */
function date(iso) {
  return iso ? new Date(iso).toLocaleDateString() : '—';
}

// ── Pages ────────────────────────────────────────────────────────────────────

/** The series in the library, as posters, with a filter. */
async function seriesPage() {
  const series = await api('GET', '/api/v3/series');
  const grid = el('div', { className: 'grid' });
  const filter = el('input', { type: 'search', placeholder: 'Filter', 'aria-label': 'Filter the series' });
  const draw = () => {
    const words = filter.value.trim().toLowerCase();
    grid.replaceChildren(
      ...series
        .filter((s) => !words || s.title.toLowerCase().includes(words))
        .map((s) => {
          const stats = s.statistics || {};
          const percent = stats.episodeCount ? (stats.episodeFileCount * 100) / stats.episodeCount : 0;
          return el('a', { className: 'card', href: `#/series/${s.id}` }, [
            poster(s.remotePoster, s.title),
            el('div', { className: 'card-body' }, [
              el('div', { className: 'card-title', title: s.title, textContent: s.title }),
              el('div', { className: 'muted', textContent: `${s.year || ''} · ${s.network || ''}` }),
              el('div', {
                className: 'muted',
                textContent: `${stats.episodeFileCount}/${stats.episodeCount} episodes`,
              }),
              el('div', { className: 'progress' }, [el('span', { style: `width: ${percent}%` })]),
            ]),
          ]);
        }),
    );
  };
  filter.addEventListener('input', draw);
  draw();
  page.replaceChildren(
    el('div', { className: 'toolbar' }, [
      filter,
      el('span', { className: 'muted', textContent: `${series.length} series` }),
      el('button', { type: 'button', textContent: 'Refresh all', onclick: () => refresh() }),
    ]),
    series.length
      ? grid
      : el('div', { className: 'empty' }, [
          el('img', { src: 'catasonarr-logo.svg', alt: '' }),
          el('p', {}, [
            'No series yet: ',
            el('a', { href: '#/add', textContent: 'add one' }),
            ' or ',
            el('a', { href: '#/import', textContent: 'import the ones on the NAS' }),
            '.',
          ]),
        ]),
  );
}

/** Searches TVmaze and adds a series. */
async function addPage() {
  const [folders, profiles] = await Promise.all([
    api('GET', '/api/v3/rootfolder'),
    api('GET', '/api/v3/qualityprofile'),
  ]);
  const results = el('div', { className: 'results' });
  const input = el('input', { type: 'search', placeholder: 'Series name, tvdb:<id> or imdb:<id>', size: 40 });
  const search = async () => {
    if (!input.value.trim()) {
      return;
    }
    results.replaceChildren(el('p', { className: 'muted', textContent: 'Searching TVmaze…' }));
    try {
      const found = await api('GET', `/api/v3/series/lookup?term=${encodeURIComponent(input.value.trim())}`);
      results.replaceChildren(
        ...(found.length
          ? found.map((s) => result(s, folders, profiles))
          : [el('p', { className: 'empty', textContent: 'Nothing found.' })]),
      );
    } catch (e) {
      results.replaceChildren();
      toast(e.message, true);
    }
  };
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      search();
    }
  });
  page.replaceChildren(
    el('div', { className: 'toolbar' }, [
      input,
      el('button', { type: 'button', className: 'primary', textContent: 'Search', onclick: search }),
    ]),
    ...(folders.length ? [] : [el('p', { className: 'muted', textContent: 'Add a root folder in Settings first.' })]),
    results,
  );
  input.focus();
}

/**
 * One series found, with its add form.
 *
 * @param {object} s Sonarr's series
 * @param {Array<object>} folders root folders
 * @param {Array<object>} profiles quality profiles
 * @returns {HTMLElement} the result
 */
function result(s, folders, profiles) {
  const body = el('div', { className: 'result-body' }, [
    el('h3', { textContent: `${s.title} ${s.year ? `(${s.year})` : ''}` }),
    el('div', {
      className: 'muted',
      textContent: [s.network, s.status, `TheTVDB ${s.tvdbId}`].filter(Boolean).join(' · '),
    }),
    el('p', { textContent: s.overview || '' }),
  ]);
  if (s.id) {
    body.append(el('a', { href: `#/series/${s.id}`, textContent: 'Already in the library' }));
  } else {
    body.append(
      el('button', {
        type: 'button',
        className: 'primary',
        textContent: 'Add…',
        onclick: (e) => e.target.replaceWith(addForm(s, folders, profiles)),
      }),
    );
  }
  return el('div', { className: 'result' }, [poster(s.remotePoster, s.title), body]);
}

/**
 * The form that adds a series, as Sonarr's: root folder, monitor, quality profile, series type, season folders.
 *
 * @param {object} s Sonarr's series
 * @param {Array<object>} folders root folders
 * @param {Array<object>} profiles quality profiles
 * @returns {HTMLElement} the form
 */
function addForm(s, folders, profiles) {
  const folder = el(
    'select',
    {},
    folders.map((f) => el('option', { value: f.path, textContent: `${f.path} (${size(f.freeSpace)} free)` })),
  );
  const monitor = el(
    'select',
    {},
    MONITOR_OPTIONS.map(([v, t]) => el('option', { value: v, textContent: t })),
  );
  const profile = el(
    'select',
    {},
    profiles.map((p) => el('option', { value: p.id, textContent: p.name })),
  );
  const type = el(
    'select',
    {},
    ['standard', 'daily', 'anime'].map((t) => el('option', { value: t, textContent: t })),
  );
  const seasonFolder = el('input', { type: 'checkbox', checked: true });
  const add = async () => {
    try {
      const added = await api('POST', '/api/v3/series', {
        tvdbId: s.tvdbId,
        title: s.title,
        qualityProfileId: Number(profile.value),
        rootFolderPath: folder.value,
        monitored: monitor.value !== 'none',
        seasonFolder: seasonFolder.checked,
        seriesType: type.value,
        monitorNewItems: 'all',
        addOptions: { monitor: monitor.value, searchForMissingEpisodes: false, searchForCutoffUnmetEpisodes: false },
      });
      toast(`${added.title} added`);
      window.location.hash = `#/series/${added.id}`;
    } catch (e) {
      toast(e.message, true);
    }
  };
  return el('div', { className: 'form' }, [
    el('label', { textContent: 'Root folder' }),
    folder,
    el('label', { textContent: 'Folder' }),
    el('span', { className: 'muted', textContent: s.folder || '' }),
    el('label', { textContent: 'Monitor' }),
    monitor,
    el('label', { textContent: 'Quality profile' }),
    profile,
    el('label', { textContent: 'Series type' }),
    type,
    el('label', { textContent: 'Season folders' }),
    seasonFolder,
    el('span'),
    el('button', { type: 'button', className: 'primary', textContent: `Add ${s.title}`, onclick: add }),
  ]);
}

/**
 * A select with options.
 *
 * @param {Array<[string|number, string]>} options value and text of each option
 * @param {string|number} [value] the one chosen
 * @param {object} [props] more properties, e.g. aria-label
 * @returns {HTMLSelectElement} the select
 */
function select(options, value, props = {}) {
  const node = el(
    'select',
    props,
    options.map(([v, t]) => el('option', { value: v, textContent: t })),
  );
  if (value !== undefined) {
    node.value = String(value);
  }
  return node;
}

/**
 * Imports the series already on the NAS, as Sonarr's Library Import (orch.md decision 115): a root folder's folders
 * that are no series yet, each matched in TVmaze by its name and year (or the id in its name), one after another;
 * you check the matches, change what is wrong and import the ones ticked.
 */
async function importPage() {
  const mine = generation;
  const [folders, profiles] = await Promise.all([
    api('GET', '/api/v3/rootfolder'),
    api('GET', '/api/v3/qualityprofile'),
  ]);
  if (!folders.length) {
    page.replaceChildren(
      el('div', { className: 'empty' }, [
        el('p', { textContent: 'Add a root folder first, in ' }, [
          el('a', { href: '#/settings', textContent: 'Settings' }),
        ]),
      ]),
    );
    return;
  }
  const profileOptions = profiles.map((p) => [p.id, p.name]);
  const rootPick = select(
    folders.map((f) => [f.id, `${f.path} · ${(f.unmappedFolders || []).length} to import`]),
    folders[0].id,
    { 'aria-label': 'Root folder' },
  );
  const allMonitor = select(MONITOR_OPTIONS, 'all', { 'aria-label': 'Monitor, for every row' });
  const allProfile = select(profileOptions, profiles[0] && profiles[0].id, {
    'aria-label': 'Quality profile, for every row',
  });
  const importButton = el('button', { type: 'button', className: 'primary', textContent: 'Import' });
  const summary = el('span', { className: 'muted' });
  const body = el('tbody');
  let rows = [];

  const count = () => {
    const ready = rows.filter((r) => r.tick.checked && r.match() && !r.done);
    importButton.textContent = `Import ${ready.length}`;
    importButton.disabled = ready.length === 0;
    const found = rows.filter((r) => r.searched).length;
    summary.textContent = `${rows.length} folders · ${found} searched`;
  };

  /**
   * One folder: its match, monitor and profile.
   *
   * @param {{name: string, path: string}} folder a folder that is no series yet
   * @returns {object} the row, with its search
   */
  const row = (folder) => {
    const tick = el('input', { type: 'checkbox', checked: true, 'aria-label': `Import ${folder.name}` });
    const choice = el('select', { 'aria-label': `Series for ${folder.name}`, hidden: true });
    const again = el('input', {
      type: 'search',
      placeholder: 'Search again',
      size: 18,
      'aria-label': `Search again for ${folder.name}`,
    });
    const thumb = el('div', { className: 'thumb' });
    const state = el('span', { className: 'muted', textContent: 'Waiting…' });
    const monitor = select(MONITOR_OPTIONS, allMonitor.value, { 'aria-label': `Monitor ${folder.name}` });
    const profile = select(profileOptions, allProfile.value, { 'aria-label': `Quality profile of ${folder.name}` });
    const item = {
      folder,
      tick,
      monitor,
      profile,
      results: [],
      searched: false,
      done: false,
      match: () => item.results[Number(choice.value)] || null,
    };
    const show = () => {
      const m = item.match();
      thumb.replaceChildren(m && m.remotePoster ? el('img', { src: m.remotePoster, alt: '', loading: 'lazy' }) : '');
      count();
    };
    item.search = async (term) => {
      item.searching = true;
      state.textContent = 'Searching TVmaze…';
      try {
        item.results = (await api('GET', `/api/v3/series/lookup?term=${encodeURIComponent(term)}`)).filter(
          (s) => !s.id,
        );
        choice.replaceChildren(
          ...item.results.map((s, i) =>
            el('option', { value: i, textContent: `${s.title}${s.year ? ` (${s.year})` : ''} · ${s.network || ''}` }),
          ),
        );
        choice.hidden = item.results.length === 0;
        state.textContent = item.results.length ? '' : 'No match: search again';
        tick.checked = tick.checked && item.results.length > 0;
      } catch (e) {
        state.textContent = e.message;
      }
      item.searched = true;
      item.searching = false;
      show();
    };
    choice.addEventListener('change', show);
    tick.addEventListener('change', count);
    again.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && again.value.trim()) {
        tick.checked = true;
        item.search(again.value.trim());
      }
    });
    item.state = state;
    item.tr = el('tr', {}, [
      el('td', {}, [tick]),
      el('td', {}, [el('div', { className: 'folder-name', textContent: folder.name })]),
      el('td', {}, [
        el('div', { className: 'match' }, [thumb, el('div', { className: 'match-pick' }, [choice, state, again])]),
      ]),
      el('td', {}, [monitor]),
      el('td', {}, [profile]),
    ]);
    return item;
  };

  let pass = 0;
  /**
   * Reads the root folder's folders that are no series yet, from the NAS. Rows already listed keep their match and
   * choices; folders imported since (or gone) leave; new ones are searched, one after another.
   *
   * @param {boolean} [refresh] whether it is the Refresh button, which says what changed
   */
  const load = async (refresh = false) => {
    pass += 1;
    const mineToo = pass;
    const folder = await api('GET', `/api/v3/rootfolder/${rootPick.value}`);
    const known = new Map(rows.filter((r) => !r.done).map((r) => [r.folder.path, r]));
    const before = rows.length;
    rows = (folder.unmappedFolders || []).map((f) => known.get(f.path) || row(f));
    const added = rows.filter((r) => !known.has(r.folder.path)).length;
    body.replaceChildren(...rows.map((r) => r.tr));
    count();
    if (refresh) {
      const left = before - (rows.length - added);
      toast(`${added} new folder(s), ${left} no longer to import, ${rows.length} to import`);
    }
    for (const r of rows) {
      if (generation !== mine || pass !== mineToo) {
        return; // another page, another root folder or a refresh: that one searches now
      }
      if (!r.searched && !r.searching) {
        await r.search(r.folder.name);
      }
    }
  };

  importButton.addEventListener('click', async () => {
    const ready = rows.filter((r) => r.tick.checked && r.match() && !r.done);
    importButton.disabled = true;
    let imported = 0;
    for (const r of ready) {
      const s = r.match();
      r.state.textContent = 'Importing…';
      try {
        await api('POST', '/api/v3/series', {
          tvdbId: s.tvdbId,
          title: s.title,
          qualityProfileId: Number(r.profile.value),
          path: r.folder.path,
          monitored: r.monitor.value !== 'none',
          seasonFolder: true,
          seriesType: 'standard',
          monitorNewItems: 'all',
          addOptions: {
            monitor: r.monitor.value,
            searchForMissingEpisodes: false,
            searchForCutoffUnmetEpisodes: false,
          },
        });
        r.done = true;
        r.tick.checked = false;
        r.tick.disabled = true;
        r.tr.classList.add('imported');
        r.state.textContent = 'Imported';
        imported += 1;
      } catch (e) {
        r.state.textContent = e.message;
      }
      count();
    }
    toast(`${imported} of ${ready.length} series imported`, imported < ready.length);
  });
  allMonitor.addEventListener('change', () => rows.forEach((r) => (r.monitor.value = allMonitor.value)));
  allProfile.addEventListener('change', () => rows.forEach((r) => (r.profile.value = allProfile.value)));
  rootPick.addEventListener('change', () => {
    rows = [];
    load();
  });

  page.replaceChildren(
    el('div', { className: 'page-head' }, [
      el('h2', { textContent: 'Import library' }),
      el('p', {
        className: 'muted',
        textContent:
          'The folders of a root folder that are no series yet, matched in TVmaze by name and year. Check the matches, then import.',
      }),
    ]),
    el('div', { className: 'toolbar panel' }, [
      rootPick,
      el('label', { className: 'inline' }, ['Monitor ', allMonitor]),
      el('label', { className: 'inline' }, ['Profile ', allProfile]),
      summary,
      el('button', {
        type: 'button',
        textContent: 'Refresh',
        title: 'Read the folders on the NAS again: what was imported leaves, new folders come in',
        onclick: () => load(true),
      }),
      importButton,
    ]),
    el('table', { className: 'import' }, [
      el('thead', {}, [
        el('tr', {}, [
          el('th', { textContent: '' }),
          el('th', { textContent: 'Folder' }),
          el('th', { textContent: 'Series' }),
          el('th', { textContent: 'Monitor' }),
          el('th', { textContent: 'Quality profile' }),
        ]),
      ]),
      body,
    ]),
  );
  await load();
}

/**
 * A series with its seasons and episodes.
 *
 * @param {number} id the series
 */
async function detailPage(id) {
  const [s, episodes, profiles] = await Promise.all([
    api('GET', `/api/v3/series/${id}`),
    api('GET', `/api/v3/episode?seriesId=${id}`),
    api('GET', '/api/v3/qualityprofile'),
  ]);
  const profile = profiles.find((p) => p.id === s.qualityProfileId);
  const monitored = el('input', {
    type: 'checkbox',
    checked: s.monitored,
    onchange: (e) => saveSeries(s, { monitored: e.target.checked }),
  });
  const deleteFiles = el('input', { type: 'checkbox' });
  const head = el('div', { className: 'detail' }, [
    poster(s.remotePoster, s.title),
    el('div', {}, [
      el('h2', { className: 'detail-title', textContent: `${s.title} ${s.year ? `(${s.year})` : ''}` }),
      el('div', {
        className: 'muted',
        textContent: [s.network, s.status, s.runtime ? `${s.runtime} min` : '', s.genres.join(', ')]
          .filter(Boolean)
          .join(' · '),
      }),
      el('p', { textContent: s.overview || '' }),
      el('div', { textContent: `Path: ${s.path}` }),
      el('div', { textContent: `Quality profile: ${profile ? profile.name : s.qualityProfileId}` }),
      el('div', { className: 'toolbar' }, [
        el('label', {}, [monitored, ' Monitored']),
        el('button', { type: 'button', textContent: 'Refresh', onclick: () => refresh(s.id) }),
        el('label', {}, [deleteFiles, ' and its folder on the NAS']),
        el('button', {
          type: 'button',
          className: 'danger',
          textContent: 'Delete',
          onclick: () => remove(s, deleteFiles.checked),
        }),
      ]),
    ]),
  ]);
  const seasons = [...s.seasons]
    .sort((a, b) => b.seasonNumber - a.seasonNumber)
    .map((season) => seasonBlock(s, season, episodes));
  page.replaceChildren(head, ...seasons);
}

/**
 * A season with its episodes.
 *
 * @param {object} s the series
 * @param {object} season the season
 * @param {Array<object>} episodes every episode of the series
 * @returns {HTMLElement} the block
 */
function seasonBlock(s, season, episodes) {
  const mine = episodes
    .filter((e) => e.seasonNumber === season.seasonNumber)
    .sort((a, b) => b.episodeNumber - a.episodeNumber);
  const toggle = el('input', {
    type: 'checkbox',
    checked: season.monitored,
    onchange: (e) =>
      saveSeries(
        s,
        {
          seasons: s.seasons.map((x) =>
            x.seasonNumber === season.seasonNumber ? { ...x, monitored: e.target.checked } : x,
          ),
        },
        true,
      ),
  });
  const stats = season.statistics || {};
  const rows = mine.map((e) =>
    el('tr', {}, [
      el('td', {}, [
        el('input', {
          type: 'checkbox',
          checked: e.monitored,
          'aria-label': `Monitor episode ${e.episodeNumber}`,
          onchange: (ev) => monitorEpisodes([e.id], ev.target.checked),
        }),
      ]),
      el('td', { textContent: e.episodeNumber }),
      el('td', { textContent: e.title || 'TBA' }),
      el('td', { textContent: date(e.airDateUtc) }),
    ]),
  );
  return el('div', { className: 'season' }, [
    el('div', { className: 'season-head' }, [
      toggle,
      el('strong', { textContent: season.seasonNumber === 0 ? 'Specials' : `Season ${season.seasonNumber}` }),
      el('span', {
        className: 'muted',
        textContent: `${stats.episodeFileCount || 0}/${stats.episodeCount || 0} · ${stats.totalEpisodeCount || 0} episodes`,
      }),
    ]),
    el('table', {}, [
      el('thead', {}, [
        el(
          'tr',
          {},
          ['', '#', 'Title', 'Air date'].map((t) => el('th', { textContent: t })),
        ),
      ]),
      el('tbody', {}, rows),
    ]),
  ]);
}

/**
 * Saves a change to a series.
 *
 * @param {object} s the series
 * @param {object} change the fields that change
 * @param {boolean} [reload] draw the page again afterwards
 */
async function saveSeries(s, change, reload = false) {
  try {
    Object.assign(s, await api('PUT', `/api/v3/series/${s.id}`, { ...s, ...change }));
    toast('Saved');
    if (reload) {
      route();
    }
  } catch (e) {
    toast(e.message, true);
  }
}

/**
 * Monitors or unmonitors episodes.
 *
 * @param {Array<number>} ids the episodes
 * @param {boolean} monitored the flag
 */
async function monitorEpisodes(ids, monitored) {
  try {
    await api('PUT', '/api/v3/episode/monitor', { episodeIds: ids, monitored });
  } catch (e) {
    toast(e.message, true);
  }
}

/**
 * Refreshes one series, or all.
 *
 * @param {number} [seriesId] the series, or none for all
 */
async function refresh(seriesId) {
  try {
    await api('POST', '/api/v3/command', seriesId ? { name: 'RefreshSeries', seriesId } : { name: 'RefreshSeries' });
    toast('Refresh started; it runs in the background');
  } catch (e) {
    toast(e.message, true);
  }
}

/**
 * Deletes a series, after asking.
 *
 * @param {object} s the series
 * @param {boolean} deleteFiles delete its folder too
 */
async function remove(s, deleteFiles) {
  const what = deleteFiles
    ? `${s.title} AND its folder ${s.path} on the NAS (cannot be undone)`
    : `${s.title} from Catasonarr (files stay)`;
  if (!window.confirm(`Delete ${what}?`)) {
    return;
  }
  try {
    await api('DELETE', `/api/v3/series/${s.id}?deleteFiles=${deleteFiles}`);
    toast(`${s.title} deleted`);
    window.location.hash = '#/';
  } catch (e) {
    toast(e.message, true);
  }
}

/**
 * What a folder holds, from Catasonarr's folder browser (Sonarr's /api/v3/filesystem, orch.md decision 114).
 *
 * @param {string} path the folder, ending with /; or what is typed, whose folder is the text up to its last /
 * @returns {Promise<{parent: string, directories: Array<{name: string, path: string}>}>} the folder's contents
 */
function folderContents(path) {
  return api('GET', `/api/v3/filesystem?${new URLSearchParams({ path })}`);
}

/**
 * Suggests, as it is typed, the folders below what a path field holds.
 *
 * @param {HTMLInputElement} input the field, with an id
 * @returns {HTMLDataListElement} the suggestions, to put in the page next to the field
 */
function suggestFolders(input) {
  const list = el('datalist', { id: `${input.id}-suggestions` });
  input.setAttribute('list', list.id);
  let timer;
  input.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      try {
        const contents = await folderContents(input.value);
        list.replaceChildren(...contents.directories.map((d) => el('option', { value: d.path })));
      } catch {
        list.replaceChildren();
      }
    }, 250);
  });
  return list;
}

/**
 * Opens the folder browser at what the field holds (or /); Select puts the chosen folder in the field.
 *
 * @param {HTMLInputElement} input the path field
 */
function browseFolders(input) {
  const where = el('p', { className: 'browser-path' });
  const list = el('ul', { className: 'browser-list' });
  const dialog = el('dialog', { className: 'browser', 'aria-label': 'Choose a folder' });
  let folder = '/';
  const show = async (path) => {
    try {
      const contents = await folderContents(path);
      folder = path;
      where.textContent = folder;
      const up = contents.parent
        ? [el('li', {}, [el('button', { type: 'button', textContent: '..', onclick: () => show(contents.parent) })])]
        : [];
      const down = contents.directories.map((d) =>
        el('li', {}, [el('button', { type: 'button', textContent: d.name, onclick: () => show(d.path) })]),
      );
      const none = down.length ? [] : [el('li', { className: 'muted', textContent: 'No folders here.' })];
      list.replaceChildren(...up, ...down, ...none);
    } catch (e) {
      toast(e.message, true);
    }
  };
  const select = () => {
    input.value = folder.length > 1 ? folder.replace(/\/$/, '') : folder;
    dialog.close();
  };
  dialog.append(
    el('h2', { textContent: 'Choose a folder' }),
    where,
    list,
    el('div', { className: 'browser-actions' }, [
      el('button', { type: 'button', textContent: 'Cancel', onclick: () => dialog.close() }),
      el('button', { type: 'button', className: 'primary', textContent: 'Select', onclick: select }),
    ]),
  );
  dialog.addEventListener('close', () => dialog.remove());
  document.body.append(dialog);
  dialog.showModal();
  const typed = input.value.trim();
  show(typed ? `${typed.replace(/\/+$/, '')}/` : '/');
}

/** Root folders, quality profiles and tags. */
async function settingsPage() {
  const [folders, profiles, tags] = await Promise.all([
    api('GET', '/api/v3/rootfolder'),
    api('GET', '/api/v3/qualityprofile'),
    api('GET', '/api/v3/tag'),
  ]);
  const path = el('input', {
    id: 'root-folder-path',
    placeholder: '/data/Series',
    size: 30,
    autocomplete: 'off',
    'aria-label': 'Root folder path',
  });
  const addFolder = async () => {
    try {
      await api('POST', '/api/v3/rootfolder', { path: path.value.trim() });
      route();
    } catch (e) {
      toast(e.message, true);
    }
  };
  const deleteFolder = async (f) => {
    if (!window.confirm(`Remove the root folder ${f.path}? Its series and files stay.`)) {
      return;
    }
    try {
      await api('DELETE', `/api/v3/rootfolder/${f.id}`);
      route();
    } catch (e) {
      toast(e.message, true);
    }
  };
  page.replaceChildren(
    el('section', {}, [
      el('h2', { textContent: 'Root folders' }),
      el('p', {
        className: 'muted',
        textContent: 'Paths inside Catasonarr: the NAS is mounted as Sonarr mounts it, /data (Media) and /downloads.',
      }),
      el('table', {}, [
        el(
          'tbody',
          {},
          folders.map((f) =>
            el('tr', {}, [
              el('td', { textContent: f.path }),
              el('td', {
                className: f.accessible ? '' : 'muted',
                textContent: f.accessible ? `${size(f.freeSpace)} free` : 'not accessible',
              }),
              el('td', { textContent: `${(f.unmappedFolders || []).length} unmapped folders` }),
              el('td', {}, [
                el('button', {
                  type: 'button',
                  className: 'danger',
                  textContent: 'Remove',
                  onclick: () => deleteFolder(f),
                }),
              ]),
            ]),
          ),
        ),
      ]),
      el('div', { className: 'toolbar' }, [
        path,
        suggestFolders(path),
        el('button', { type: 'button', textContent: 'Browse…', onclick: () => browseFolders(path) }),
        el('button', { type: 'button', textContent: 'Add root folder', onclick: addFolder }),
      ]),
    ]),
    el('section', {}, [
      el('h2', { textContent: 'Quality profiles' }),
      el('p', {
        className: 'muted',
        textContent:
          "Imported from Sonarr's database with microshift/apps/catacumbas69-apps/catasonarr/catasonarr-import-sonarr.sh.",
      }),
      el(
        'ul',
        {},
        profiles.map((p) =>
          el('li', { textContent: `${p.name} (${p.items.filter((i) => i.allowed).length} allowed)` }),
        ),
      ),
    ]),
    el('section', {}, [
      el('h2', { textContent: 'Tags' }),
      tags.length
        ? el(
            'ul',
            {},
            tags.map((t) => el('li', { textContent: t.label })),
          )
        : el('p', { className: 'muted', textContent: 'No tags.' }),
    ]),
  );
}

// ── Navigation ───────────────────────────────────────────────────────────────

/** Draws the page the address asks for. */
async function route() {
  generation += 1;
  const hash = window.location.hash || '#/';
  const detail = hash.match(/^#\/series\/(\d+)$/);
  const name = detail ? 'series' : { '#/add': 'add', '#/import': 'import', '#/settings': 'settings' }[hash] || 'series';
  document
    .querySelectorAll('.topbar-nav a')
    .forEach((a) => a.classList.toggle('active', a.dataset.page === name && !detail));
  page.replaceChildren(el('p', { className: 'muted', textContent: 'Loading…' }));
  try {
    if (detail) {
      await detailPage(Number(detail[1]));
    } else if (name === 'add') {
      await addPage();
    } else if (name === 'import') {
      await importPage();
    } else if (name === 'settings') {
      await settingsPage();
    } else {
      await seriesPage();
    }
  } catch (e) {
    page.replaceChildren(el('p', { className: 'empty', textContent: `Not loaded: ${e.message}` }));
  }
}

/** Posts the logout form, which ends Catasonarr's session and Dex's. */
function signOut() {
  const form = document.createElement('form');
  form.method = 'post';
  form.action = '/logout';
  const token = document.createElement('input');
  token.type = 'hidden';
  token.name = '_csrf';
  token.value = csrfToken();
  form.append(token);
  document.body.append(form);
  form.submit();
}

/** Shows the signed-in user and Sign out, when Catasonarr runs with login. */
async function showUser() {
  const response = await fetch('/api/v1/session');
  if (response.status === 401) {
    window.location.reload(); // the session expired: the reload goes through Dex
    return;
  }
  const session = await response.json();
  if (!session.login) {
    return;
  }
  const user = document.querySelector('[data-testid="user"]');
  user.querySelector('[data-testid="user-name"]').textContent = session.user;
  user.querySelector('[data-testid="sign-out"]').addEventListener('click', signOut);
  user.hidden = false;
}

window.addEventListener('hashchange', route);
showUser();
route();
