// Catasonarr's pages (orch.md decisions 109, 112, 114, 115 and 122): the series, adding a series, importing the series
// already on the NAS, a series with its episodes, files and searches, the activity (queue, history, blocklist) and
// the settings. They use Sonarr's API v3 with the Dex session and the CSRF token, as Sonarr's own page uses it.

/** Grows on every page change, so work a page left behind (the import's searches) stops. */
let generation = 0;

/**
 * What the page shown does when Catasonarr says something changed (orch.md decision 118): set by each page as it is
 * drawn, cleared on every page change.
 *
 * @type {((change: object) => void) | null}
 */
let onChange = null;

/** The series page's filter, kept when the page is drawn again. */
let seriesFilter = '';

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
 * @param {object} [props] properties, e.g. className, textContent, href; tip is the tooltip (orch.md decision 121)
 * @param {Array<Node|string>} [children] what goes inside
 * @returns {HTMLElement} the element
 */
function el(tag, props = {}, children = []) {
  const node = document.createElement(tag);
  Object.entries(props).forEach(([key, value]) => {
    if (key === 'dataset') {
      Object.assign(node.dataset, value);
    } else if (key === 'tip') {
      node.dataset.tip = value;
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
  const filter = el('input', {
    type: 'search',
    placeholder: 'Filter',
    value: seriesFilter,
    'aria-label': 'Filter the series',
  });
  const draw = () => {
    const words = filter.value.trim().toLowerCase();
    grid.replaceChildren(
      ...series
        .filter((s) => !words || s.title.toLowerCase().includes(words))
        .map((s) => {
          const stats = s.statistics || {};
          const percent = stats.episodeCount ? (stats.episodeFileCount * 100) / stats.episodeCount : 0;
          return el('a', { className: 'card', href: `#/series/${s.id}`, tip: `${s.title}: its seasons and episodes` }, [
            poster(s.remotePoster, s.title),
            el('div', { className: 'card-body' }, [
              el('div', { className: 'card-title', textContent: s.title }),
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
  filter.addEventListener('input', () => {
    seriesFilter = filter.value;
    draw();
  });
  draw();
  onChange = (change) => {
    if (change.entity === 'series') {
      redraw(seriesPage);
    }
  };
  page.replaceChildren(
    el('div', { className: 'toolbar' }, [
      filter,
      el('span', { className: 'muted', textContent: `${series.length} series` }),
      el('button', {
        type: 'button',
        textContent: 'Refresh all',
        tip: 'Read every series again from TVmaze (and TMDB): new episodes, dates and pictures. Runs in the background',
        onclick: () => refresh(),
      }),
    ]),
    series.length
      ? grid
      : el('div', { className: 'empty' }, [
          el('img', { src: 'catasonarr-logo.svg', alt: '' }),
          el('p', {}, [
            'No series yet: ',
            el('a', { href: '#/add', textContent: 'add one', tip: 'Search TVmaze for a series and add it' }),
            ' or ',
            el('a', {
              href: '#/import',
              textContent: 'import the ones on the NAS',
              tip: 'Add the series already in a root folder on the NAS',
            }),
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
      el('button', {
        type: 'button',
        className: 'primary',
        textContent: 'Search',
        tip: 'Search TVmaze for this name, or by id with tvdb:<id> or imdb:<id>',
        onclick: search,
      }),
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
    body.append(
      el('a', {
        href: `#/series/${s.id}`,
        textContent: 'Already in the library',
        tip: 'Open this series in Catasonarr',
      }),
    );
  } else {
    body.append(
      el('button', {
        type: 'button',
        className: 'primary',
        textContent: 'Add…',
        tip: 'Choose where and how to add this series',
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
    { tip: "The root folder on the NAS where the series' folder is made" },
    folders.map((f) => el('option', { value: f.path, textContent: `${f.path} (${size(f.freeSpace)} free)` })),
  );
  const monitor = el(
    'select',
    { tip: 'Which episodes Catasonarr looks out for' },
    MONITOR_OPTIONS.map(([v, t]) => el('option', { value: v, textContent: t })),
  );
  const profile = el(
    'select',
    { tip: 'The qualities Catasonarr may download, and the one it aims for' },
    profiles.map((p) => el('option', { value: p.id, textContent: p.name })),
  );
  const type = el(
    'select',
    { tip: 'How episodes are numbered: standard (S01E01), daily (by date) or anime (absolute numbers)' },
    ['standard', 'daily', 'anime'].map((t) => el('option', { value: t, textContent: t })),
  );
  const seasonFolder = el('input', {
    type: 'checkbox',
    checked: true,
    tip: 'Keep each season in its own folder (Season 01, Season 02, …)',
  });
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
    el('button', {
      type: 'button',
      className: 'primary',
      textContent: `Add ${s.title}`,
      tip: `Add ${s.title} to the library; nothing is searched for yet`,
      onclick: add,
    }),
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
          el('a', { href: '#/settings', textContent: 'Settings', tip: 'Add a root folder in Settings' }),
        ]),
      ]),
    );
    return;
  }
  const profileOptions = profiles.map((p) => [p.id, p.name]);
  const rootPick = select(
    folders.map((f) => [f.id, `${f.path} · ${(f.unmappedFolders || []).length} to import`]),
    folders[0].id,
    { 'aria-label': 'Root folder', tip: 'The root folder whose folders are listed below' },
  );
  const allMonitor = select(MONITOR_OPTIONS, 'all', {
    'aria-label': 'Monitor, for every row',
    tip: "Changes every row's Monitor at once",
  });
  const allProfile = select(profileOptions, profiles[0] && profiles[0].id, {
    'aria-label': 'Quality profile, for every row',
    tip: "Changes every row's quality profile at once",
  });
  const importButton = el('button', {
    type: 'button',
    className: 'primary',
    textContent: 'Import',
    tip: 'Add the ticked folders as series, with their match, monitor and profile; nothing is searched for',
  });
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
    const tick = el('input', {
      type: 'checkbox',
      checked: true,
      'aria-label': `Import ${folder.name}`,
      tip: 'Import this folder; untick to leave it out',
    });
    const choice = el('select', {
      'aria-label': `Series for ${folder.name}`,
      hidden: true,
      tip: 'The series in TVmaze this folder is; pick another if it is wrong, or search again below',
    });
    const again = el('input', {
      type: 'search',
      placeholder: 'Search again',
      size: 18,
      'aria-label': `Search again for ${folder.name}`,
    });
    const thumb = el('div', { className: 'thumb' });
    const state = el('span', { className: 'muted', textContent: 'Waiting…' });
    const monitor = select(MONITOR_OPTIONS, allMonitor.value, {
      'aria-label': `Monitor ${folder.name}`,
      tip: 'Which episodes of this series Catasonarr looks out for',
    });
    const profile = select(profileOptions, allProfile.value, {
      'aria-label': `Quality profile of ${folder.name}`,
      tip: "This series' quality profile",
    });
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
  onChange = (change) => {
    if (
      (change.entity === 'series' && ['added', 'deleted'].includes(change.action)) ||
      change.entity === 'rootfolder'
    ) {
      load();
    }
  };

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
        tip: 'Read the folders on the NAS again: what was imported leaves, new folders come in',
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
  const [s, episodes, profiles, files] = await Promise.all([
    api('GET', `/api/v3/series/${id}`),
    api('GET', `/api/v3/episode?seriesId=${id}`),
    api('GET', '/api/v3/qualityprofile'),
    api('GET', `/api/v3/episodefile?seriesId=${id}`),
  ]);
  const fileById = new Map(files.map((f) => [f.id, f]));
  const profile = profiles.find((p) => p.id === s.qualityProfileId);
  const monitored = el('input', {
    type: 'checkbox',
    checked: s.monitored,
    tip: 'Look out for the monitored episodes of this series; off, none is looked out for',
    onchange: (e) => saveSeries(s, { monitored: e.target.checked }),
  });
  const deleteFiles = el('input', {
    type: 'checkbox',
    tip: "With Delete, also delete the series' folder on the NAS (cannot be undone)",
  });
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
        el('button', {
          type: 'button',
          textContent: 'Refresh',
          tip: 'Read this series again from TVmaze (and TMDB): new episodes, dates and pictures',
          onclick: () => refresh(s.id),
        }),
        button('Search monitored', 'Search the indexers for every monitored episode missing or below the cutoff', () =>
          command({ name: 'SeriesSearch', seriesId: s.id }, `Search for ${s.title}`),
        ),
        button('Rescan', "Read the series' folder again: new, changed and missing files", () =>
          command({ name: 'RescanSeries', seriesId: s.id }, `Rescan of ${s.title}`),
        ),
        el('label', {}, [deleteFiles, ' and its folder on the NAS']),
        el('button', {
          type: 'button',
          className: 'danger',
          textContent: 'Delete',
          tip: 'Remove this series from Catasonarr, and its folder too if ticked; asks first',
          onclick: () => remove(s, deleteFiles.checked),
        }),
      ]),
    ]),
  ]);
  const seasons = [...s.seasons]
    .sort((a, b) => b.seasonNumber - a.seasonNumber)
    .map((season) => seasonBlock(s, season, episodes, fileById));
  page.replaceChildren(head, ...seasons);
  onChange = (change) => {
    if (change.entity === 'series' && change.seriesId === id && change.action === 'deleted') {
      window.location.hash = '#/';
    } else if (
      (change.entity === 'series' && change.seriesId === id) ||
      change.entity === 'episode' ||
      (['episodefile', 'release', 'download'].includes(change.entity) && change.seriesId === id)
    ) {
      redraw(() => detailPage(id));
    }
  };
}

/**
 * A season with its episodes.
 *
 * @param {object} s the series
 * @param {object} season the season
 * @param {Array<object>} episodes every episode of the series
 * @param {Map<number, object>} fileById the series' files by id
 * @returns {HTMLElement} the block
 */
function seasonBlock(s, season, episodes, fileById) {
  const mine = episodes
    .filter((e) => e.seasonNumber === season.seasonNumber)
    .sort((a, b) => b.episodeNumber - a.episodeNumber);
  const toggle = el('input', {
    type: 'checkbox',
    checked: season.monitored,
    tip: 'Monitor this season (on) or none of it (off)',
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
          tip: 'Monitor this episode',
          onchange: (ev) => monitorEpisodes([e.id], ev.target.checked),
        }),
      ]),
      el('td', { textContent: e.episodeNumber }),
      el('td', { textContent: e.title || 'TBA' }),
      el('td', { textContent: date(e.airDateUtc) }),
      episodeFileCell(s, e, fileById.get(e.episodeFileId)),
      el('td', { className: 'actions' }, [
        button('Search', 'Search the indexers for this episode and grab the best release', () =>
          command({ name: 'EpisodeSearch', episodeIds: [e.id] }, `Search for ${episodeCode(e)}`),
        ),
        button('Interactive…', 'See every release the indexers have for this episode, and pick one', () =>
          interactiveSearch(`episodeId=${e.id}`, `${s.title} ${episodeCode(e)}`),
        ),
      ]),
    ]),
  );
  return el('div', { className: 'season' }, [
    el('div', { className: 'season-head' }, [
      toggle,
      el('strong', { textContent: season.seasonNumber === 0 ? 'Specials' : `Season ${season.seasonNumber}` }),
      el('span', {
        className: 'muted',
        textContent: `${stats.episodeFileCount || 0}/${stats.episodeCount || 0} · ${stats.totalEpisodeCount || 0} episodes${stats.sizeOnDisk ? ` · ${bytes(stats.sizeOnDisk)}` : ''}`,
      }),
      button('Search season', 'Search the indexers for this season’s monitored episodes', () =>
        command(
          { name: 'SeasonSearch', seriesId: s.id, seasonNumber: season.seasonNumber },
          `Search for season ${season.seasonNumber}`,
        ),
      ),
      button('Interactive…', 'See every release the indexers have for this season, and pick one', () =>
        interactiveSearch(
          `seriesId=${s.id}&seasonNumber=${season.seasonNumber}`,
          `${s.title} season ${season.seasonNumber}`,
        ),
      ),
    ]),
    el('table', {}, [
      el('thead', {}, [
        el(
          'tr',
          {},
          ['', '#', 'Title', 'Air date', 'File', ''].map((t) => el('th', { textContent: t })),
        ),
      ]),
      el('tbody', {}, rows),
    ]),
  ]);
}

/**
 * An episode's file: its quality and size, and a button to delete it; or that it is missing.
 *
 * @param {object} s the series
 * @param {object} e the episode
 * @param {object} [f] its file
 * @returns {HTMLElement} the cell
 */
function episodeFileCell(s, e, f) {
  if (!f) {
    const aired = e.airDateUtc && new Date(e.airDateUtc) < new Date();
    return el('td', { className: 'muted', textContent: aired && e.monitored ? 'Missing' : '—' });
  }
  const cut = f.qualityCutoffNotMet ? ' (below the cutoff)' : '';
  return el('td', {}, [
    el('span', {
      className: `badge ${f.qualityCutoffNotMet ? '' : 'on'}`,
      textContent: qualityName(f.quality),
      title: `${f.relativePath}${cut}`,
    }),
    ` ${bytes(f.size)} `,
    button(
      '✕',
      'Delete this file (to the recycle bin); asks first',
      () => deleteAfterAsking(`/api/v3/episodefile/${f.id}`, `the file of ${episodeCode(e)}, ${f.relativePath}`),
      'danger',
    ),
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
        ? [
            el('li', {}, [
              el('button', {
                type: 'button',
                textContent: '..',
                tip: 'Up to the folder above',
                onclick: () => show(contents.parent),
              }),
            ]),
          ]
        : [];
      const down = contents.directories.map((d) =>
        el('li', {}, [
          el('button', { type: 'button', textContent: d.name, tip: `Open ${d.path}`, onclick: () => show(d.path) }),
        ]),
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
      el('button', {
        type: 'button',
        textContent: 'Cancel',
        tip: 'Close without choosing',
        onclick: () => dialog.close(),
      }),
      el('button', {
        type: 'button',
        className: 'primary',
        textContent: 'Select',
        tip: 'Put the folder shown at the top in the field',
        onclick: select,
      }),
    ]),
  );
  dialog.addEventListener('close', () => dialog.remove());
  document.body.append(dialog);
  dialog.showModal();
  const typed = input.value.trim();
  show(typed ? `${typed.replace(/\/+$/, '')}/` : '/');
}

/**
 * One connection with its health check (orch.md decision 119): what it is, and UP or DOWN as the server answers.
 *
 * @param {string} title e.g. MongoDB
 * @param {Array<[string, string|undefined]>} rows label and value; empty values are left out
 * @param {string} health the health check's address
 * @returns {HTMLElement} the card, checked once drawn
 */
function connectionCard(title, rows, health) {
  const status = el('span', { className: 'status', textContent: 'Checking…' });
  const check = async () => {
    status.className = 'status';
    status.textContent = 'Checking…';
    try {
      const h = await api('GET', health);
      const up = h.status === 'UP';
      status.className = `status ${up ? 'up' : 'down'}`;
      status.textContent = up
        ? `UP · ${h.latencyMs} ms${h.version ? ` · v${h.version}` : ''}`
        : `DOWN · ${h.error || 'no answer'}`;
    } catch (e) {
      status.className = 'status down';
      status.textContent = `DOWN · ${e.message}`;
    }
  };
  const card = el('div', { className: 'connection' }, [
    el('div', { className: 'connection-head' }, [
      el('h3', { textContent: title }),
      status,
      el('button', {
        type: 'button',
        textContent: 'Check',
        tip: `Check the connection to ${title} again`,
        onclick: check,
      }),
    ]),
    el(
      'dl',
      {},
      rows
        .filter(([, v]) => v !== undefined && v !== null && v !== '')
        .flatMap(([k, v]) => [el('dt', { textContent: k }), el('dd', { textContent: String(v) })]),
    ),
  ]);
  check();
  return card;
}

/**
 * Pulsar's addresses and MongoDB's connection (without its password), each with a health check.
 *
 * @param {object} c the connections, from /api/v1/connections
 * @returns {HTMLElement} the section
 */
function connectionsSection(c) {
  const m = c.mongodb || {};
  const yes = (b) => (b ? 'yes' : 'no');
  return el('section', {}, [
    el('h2', { textContent: 'Connections' }),
    el('p', {
      className: 'muted',
      textContent:
        'Where Catasonarr connects, inside the cluster, and the same from outside it (IntelliJ, Compass) through ' +
        "meloes' NodePorts. They change in its ConfigMap and in the Secret catacumbas69-apps-mongodb.",
    }),
    el('div', { className: 'connections' }, [
      connectionCard(
        'Pulsar',
        [
          ['Admin API', c.pulsar.serviceUrl],
          ['Broker', c.pulsar.brokerUrl],
          ['Listener', c.pulsar.listenerName],
          ['Admin API, from outside', c.pulsar.outsideServiceUrl],
          ['Broker, from outside', c.pulsar.outsideBrokerUrl && `${c.pulsar.outsideBrokerUrl} (listener external)`],
        ],
        '/api/v1/connections/pulsar/health',
      ),
      connectionCard(
        'MongoDB',
        [
          ['Connection', m.uri],
          ['Hosts', (m.hosts || []).join(', ')],
          ['Database', m.database],
          ['User', m.username],
          ['Auth database', m.authSource],
          ['Replica set', m.replicaSet],
          ['TLS', yes(m.tls)],
          ['Direct connection', m.directConnection === undefined ? undefined : yes(m.directConnection)],
          ['From outside', m.outsideUri],
        ],
        '/api/v1/connections/mongodb/health',
      ),
    ]),
  ]);
}

/**
 * The settings: General (connections, root folders, quality profiles and tags), Indexers, Download clients,
 * Profiles, Media management and Plex.
 *
 * @param {string} [section] which one: general, indexers, clients, profiles, media or plex
 */
async function settingsPage(section = 'general') {
  const tabs = tabBar(SETTINGS_TABS, section);
  if (section === 'indexers') {
    await indexersSettings(tabs);
    return;
  }
  if (section === 'clients') {
    await clientsSettings(tabs);
    return;
  }
  if (section === 'profiles') {
    await profilesSettings(tabs);
    return;
  }
  if (section === 'media') {
    await mediaSettings(tabs);
    return;
  }
  if (section === 'plex') {
    await plexSettings(tabs);
    return;
  }
  const connections = await api('GET', '/api/v1/connections');
  let folders = [];
  let profiles = [];
  let tags = [];
  let notLoaded = null;
  try {
    [folders, profiles, tags] = await Promise.all([
      api('GET', '/api/v3/rootfolder'),
      api('GET', '/api/v3/qualityprofile'),
      api('GET', '/api/v3/tag'),
    ]);
  } catch (e) {
    notLoaded = e.message; // MongoDB down, most likely: the connections still show, with their checks
  }
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
    tabs,
    connectionsSection(connections),
    ...(notLoaded ? [el('p', { className: 'empty', textContent: `The rest is not loaded: ${notLoaded}` })] : []),
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
                  tip: 'Stop using this root folder; its series and files stay. Asks first',
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
        el('button', {
          type: 'button',
          textContent: 'Browse…',
          tip: "Choose the folder in Catasonarr's file system",
          onclick: () => browseFolders(path),
        }),
        el('button', {
          type: 'button',
          textContent: 'Add root folder',
          tip: 'Add the path in the field as a root folder',
          onclick: addFolder,
        }),
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
  onChange = (change) => {
    const settings = ['rootfolder', 'qualityprofile', 'qualitydefinition', 'customformat', 'tag', 'import'];
    const editing =
      document.activeElement === path || path.value.trim() !== '' || document.querySelector('dialog[open]');
    if (!editing && (settings.includes(change.entity) || change.entity === 'series')) {
      redraw(() => settingsPage());
    }
  };
}

// ── Downloads: helpers (orch.md decision 122) ────────────────────────────────

/**
 * A button with its tooltip.
 *
 * @param {string} text what it says
 * @param {string} tip what it does
 * @param {() => void} onclick what happens on a click
 * @param {string} [className] e.g. primary or danger
 * @returns {HTMLElement} the button
 */
function button(text, tip, onclick, className = '') {
  return el('button', { type: 'button', textContent: text, tip, onclick, className });
}

/**
 * A label and its field, for a form's grid.
 *
 * @param {string} label the label
 * @param {HTMLElement} control the field
 * @returns {Array<HTMLElement>} the label and the field
 */
function formRow(label, control) {
  if (!control.id) {
    control.id = `f-${Math.random().toString(36).slice(2, 9)}`;
  }
  return [el('label', { htmlFor: control.id, textContent: label }), control];
}

/**
 * A text field.
 *
 * @param {string|number} value its value
 * @param {string} tip what it is for
 * @param {object} [props] more properties, e.g. type or placeholder
 * @returns {HTMLInputElement} the field
 */
function textField(value, tip, props = {}) {
  return el('input', { value: value == null ? '' : value, tip, size: 40, autocomplete: 'off', ...props });
}

/**
 * A tick box.
 *
 * @param {boolean} checked whether it is ticked
 * @param {string} tip what it does
 * @returns {HTMLInputElement} the box
 */
function tickBox(checked, tip) {
  return el('input', { type: 'checkbox', checked: Boolean(checked), tip });
}

/**
 * A quality's name.
 *
 * @param {object} [q] Sonarr's quality ({quality, revision})
 * @returns {string} e.g. WEBDL-1080p, or WEBDL-1080p v2
 */
function qualityName(q) {
  if (!q || !q.quality) {
    return '—';
  }
  const v = q.revision && q.revision.version > 1 ? ` v${q.revision.version}` : '';
  return `${q.quality.name}${v}`;
}

/**
 * An episode's number.
 *
 * @param {object} [e] the episode
 * @returns {string} e.g. S01E02
 */
function episodeCode(e) {
  if (!e) {
    return '';
  }
  const two = (n) => String(n).padStart(2, '0');
  return `S${two(e.seasonNumber)}E${two(e.episodeNumber)}`;
}

/**
 * Bytes as MB, GB or TB.
 *
 * @param {number} bytes the bytes
 * @returns {string} e.g. 850 MB
 */
function bytes(bytes) {
  if (bytes == null) {
    return '?';
  }
  return bytes < 1024 ** 3 ? `${Math.round(bytes / 1024 ** 2)} MB` : size(bytes);
}

/**
 * A date and time in the user's format.
 *
 * @param {string} [iso] the time
 * @returns {string} the time, or a dash
 */
function dateTime(iso) {
  return iso ? new Date(iso).toLocaleString() : '—';
}

/**
 * Tabs that are links to pages.
 *
 * @param {Array<[string, string, string, string]>} tabs key, label, address and tooltip of each
 * @param {string} current the key shown
 * @returns {HTMLElement} the tabs
 */
function tabBar(tabs, current) {
  return el(
    'nav',
    { className: 'tabs', 'aria-label': 'Sections' },
    tabs.map(([key, label, href, tip]) =>
      el('a', { href, textContent: label, tip, className: key === current ? 'tab active' : 'tab' }),
    ),
  );
}

/**
 * Runs a command and tells the user.
 *
 * @param {object} body the command, e.g. {name: 'EpisodeSearch', episodeIds: [1]}
 * @param {string} what what it does, for the message
 */
async function command(body, what) {
  try {
    await api('POST', '/api/v3/command', body);
    toast(`${what} started; it runs in the background`);
  } catch (e) {
    toast(e.message, true);
  }
}

/**
 * A modal dialog, removed when closed.
 *
 * @param {string} title its title
 * @param {Array<Node>} content what is inside
 * @param {string} [className] more classes, e.g. wide
 * @returns {HTMLDialogElement} the dialog, shown
 */
function modal(title, content, className = '') {
  const dialog = el('dialog', { className: `browser ${className}`, 'aria-label': title }, [
    el('h2', { textContent: title }),
    ...content,
  ]);
  dialog.addEventListener('close', () => dialog.remove());
  document.body.append(dialog);
  dialog.showModal();
  return dialog;
}

// ── Activity: queue, history, blocklist (orch.md decision 122) ──────────────

/** The history page shown, kept when the page is drawn again. */
let historyPageNumber = 1;

/** The history's event filter. */
let historyEvent = '';

/** Sonarr's history events, by its numbers. */
const HISTORY_EVENTS = [
  ['', 'All events'],
  ['1', 'Grabbed'],
  ['3', 'Imported'],
  ['4', 'Download failed'],
  ['5', 'File deleted'],
];

/**
 * The activity: what is downloading, what happened, what is blocked.
 *
 * @param {string} tab queue, history or blocklist
 */
async function activityPage(tab) {
  const tabs = tabBar(
    [
      ['queue', 'Queue', '#/activity', 'What is downloading now, and the releases held back'],
      ['history', 'History', '#/activity/history', 'Grabs, imports, failures and deleted files'],
      ['blocklist', 'Blocklist', '#/activity/blocklist', 'Releases that failed: never grabbed again'],
    ],
    tab,
  );
  if (tab === 'history') {
    await historyView(tabs);
  } else if (tab === 'blocklist') {
    await blocklistView(tabs);
  } else {
    await queueView(tabs);
  }
}

/**
 * The queue: each download's episodes, with its progress, state and messages; held-back releases too.
 *
 * @param {HTMLElement} tabs the tabs
 */
async function queueView(tabs) {
  const mine = generation;
  const data = await api(
    'GET',
    '/api/v3/queue?page=1&pageSize=500&includeSeries=true&includeEpisode=true&includeUnknownSeriesItems=true',
  );
  const rows = data.records.map((q) => {
    const done = q.size ? Math.round(((q.size - q.sizeleft) / q.size) * 100) : 0;
    const messages = (q.statusMessages || []).flatMap((m) => m.messages.map((text) => `${m.title}: ${text}`));
    const state = q.status === 'delay' ? 'held back (delay profile)' : q.trackedDownloadState || q.status;
    return el('tr', { className: q.trackedDownloadStatus === 'ok' ? '' : q.trackedDownloadStatus }, [
      el('td', {}, [
        q.series
          ? el('a', { href: `#/series/${q.seriesId}`, textContent: q.series.title, tip: 'Open the series' })
          : '?',
      ]),
      el('td', { textContent: q.episode ? `${episodeCode(q.episode)} ${q.episode.title || ''}` : '' }),
      el('td', { className: 'release-title', textContent: q.title }),
      el('td', { textContent: qualityName(q.quality) }),
      el('td', {}, [
        el('div', { textContent: state }),
        ...messages.map((m) => el('div', { className: 'muted small', textContent: m })),
      ]),
      el('td', {}, [
        el('div', { className: 'progress', title: `${done}%` }, [el('span', { style: `width:${done}%` })]),
        el('div', { className: 'muted small', textContent: `${done}% of ${bytes(q.size)}` }),
      ]),
      el('td', { textContent: q.timeleft || '—' }),
      el('td', { textContent: [q.downloadClient, q.indexer].filter(Boolean).join(' · ') }),
      el('td', { className: 'actions' }, [
        q.downloadId
          ? null
          : button('Grab now', 'Grab this held-back release now, without waiting', async () => {
              try {
                await api('POST', `/api/v3/queue/grab/${q.id}`);
                toast('Grabbed');
                route();
              } catch (e) {
                toast(e.message, true);
              }
            }),
        button(
          'Remove…',
          'Remove this download (all its episodes) from the queue; asks what else to do',
          () => removeFromQueue(q),
          'danger',
        ),
      ]),
    ]);
  });
  page.replaceChildren(
    tabs,
    el('div', { className: 'toolbar' }, [
      button('Refresh', 'Read the download clients now (done every minute anyway)', async () => {
        await command({ name: 'RefreshMonitoredDownloads' }, 'Reading the clients');
        setTimeout(route, 2000);
      }),
      button('RSS sync', 'Read every indexer’s newest releases now and grab what is wanted', () =>
        command({ name: 'RssSync' }, 'RSS sync'),
      ),
      el('span', { className: 'muted', textContent: `${data.totalRecords} items` }),
    ]),
    rows.length
      ? el('table', { className: 'activity' }, [
          el('thead', {}, [
            el(
              'tr',
              {},
              ['Series', 'Episode', 'Release', 'Quality', 'State', 'Progress', 'Time left', 'Client', ''].map((t) =>
                el('th', { textContent: t }),
              ),
            ),
          ]),
          el('tbody', {}, rows),
        ])
      : el('p', { className: 'empty', textContent: 'Nothing is downloading.' }),
  );
  onChange = (change) => {
    if (['download', 'queue', 'release', 'episodefile', 'blocklist'].includes(change.entity)) {
      redraw(() => queueView(tabs));
    }
  };
  const tick = () => {
    if (mine !== generation) {
      return; // another page by now
    }
    if (document.querySelector('dialog[open]')) {
      setTimeout(tick, 20000);
    } else {
      redraw(() => queueView(tabs));
    }
  };
  setTimeout(tick, 20000);
}

/**
 * Asks how to remove a queue item, and removes it.
 *
 * @param {object} q the queue item
 */
function removeFromQueue(q) {
  const fromClient = tickBox(true, 'Also remove the torrent and its data from the download client');
  const block = tickBox(false, 'Blocklist the release, so it is never grabbed again');
  const skip = tickBox(false, 'With the blocklist, do not search for another release');
  const go = async () => {
    try {
      const query = `removeFromClient=${fromClient.checked}&blocklist=${block.checked}&skipRedownload=${skip.checked}`;
      await api('DELETE', `/api/v3/queue/${q.id}?${query}`);
      dialog.close();
      toast('Removed');
      route();
    } catch (e) {
      toast(e.message, true);
    }
  };
  const dialog = modal(`Remove ${q.title}?`, [
    el('div', { className: 'form' }, [
      ...formRow('Remove from the client', fromClient),
      ...formRow('Blocklist the release', block),
      ...formRow('Do not search again', skip),
    ]),
    el('div', { className: 'browser-actions' }, [
      button('Cancel', 'Close without removing', () => dialog.close()),
      button('Remove', 'Remove it from the queue as ticked', go, 'danger'),
    ]),
  ]);
}

/**
 * What a history record says beyond its title.
 *
 * @param {object} h the record
 * @returns {string} e.g. the message, or where it was imported
 */
function historyDetail(h) {
  const d = h.data || {};
  if (h.eventType === 'downloadFolderImported') {
    return d.importedPath || '';
  }
  if (h.eventType === 'episodeFileDeleted') {
    return d.reason || '';
  }
  if (h.eventType === 'grabbed') {
    return [d.indexer, d.downloadClientName].filter(Boolean).join(' · ');
  }
  return d.message || '';
}

/**
 * The history, a page at a time.
 *
 * @param {HTMLElement} tabs the tabs
 */
async function historyView(tabs) {
  const pageSize = 50;
  const query = `page=${historyPageNumber}&pageSize=${pageSize}&includeSeries=true&includeEpisode=true`;
  const data = await api('GET', `/api/v3/history?${query}${historyEvent ? `&eventType=${historyEvent}` : ''}`);
  const pages = Math.max(1, Math.ceil(data.totalRecords / pageSize));
  const filter = select(HISTORY_EVENTS, historyEvent, { tip: 'Show only one kind of event' });
  filter.addEventListener('change', () => {
    historyEvent = filter.value;
    historyPageNumber = 1;
    route();
  });
  const rows = data.records.map((h) =>
    el('tr', {}, [
      el('td', { textContent: dateTime(h.date) }),
      el('td', { textContent: h.eventType }),
      el('td', {}, [
        h.series
          ? el('a', { href: `#/series/${h.seriesId}`, textContent: h.series.title, tip: 'Open the series' })
          : '',
      ]),
      el('td', { textContent: h.episode ? episodeCode(h.episode) : '' }),
      el('td', { className: 'release-title', textContent: h.sourceTitle }),
      el('td', { textContent: qualityName(h.quality) }),
      el('td', { className: 'muted small', textContent: historyDetail(h) }),
      el('td', {}, [
        h.eventType === 'grabbed'
          ? button(
              'Mark as failed',
              'Blocklist this release and search for another one; asks first',
              async () => {
                if (!window.confirm(`Mark ${h.sourceTitle} as failed?`)) {
                  return;
                }
                try {
                  await api('POST', `/api/v3/history/failed/${h.id}`);
                  toast('Marked as failed');
                  route();
                } catch (e) {
                  toast(e.message, true);
                }
              },
              'danger',
            )
          : null,
      ]),
    ]),
  );
  const go = (n) => {
    historyPageNumber = n;
    route();
  };
  page.replaceChildren(
    tabs,
    el('div', { className: 'toolbar' }, [
      filter,
      button('‹ Newer', 'The previous page', () => go(historyPageNumber - 1)),
      el('span', { className: 'muted', textContent: `Page ${historyPageNumber} of ${pages} (${data.totalRecords})` }),
      button('Older ›', 'The next page', () => go(historyPageNumber + 1)),
    ]),
    rows.length
      ? el('table', { className: 'activity' }, [
          el('thead', {}, [
            el(
              'tr',
              {},
              ['Date', 'Event', 'Series', 'Episode', 'Source', 'Quality', 'Details', ''].map((t) =>
                el('th', { textContent: t }),
              ),
            ),
          ]),
          el('tbody', {}, rows),
        ])
      : el('p', { className: 'empty', textContent: 'No history yet.' }),
  );
  page.querySelectorAll('.toolbar button')[0].disabled = historyPageNumber <= 1;
  page.querySelectorAll('.toolbar button')[1].disabled = historyPageNumber >= pages;
  onChange = (change) => {
    if (historyPageNumber === 1 && ['download', 'release', 'episodefile', 'blocklist'].includes(change.entity)) {
      redraw(() => historyView(tabs));
    }
  };
}

/**
 * The blocklist.
 *
 * @param {HTMLElement} tabs the tabs
 */
async function blocklistView(tabs) {
  const data = await api('GET', '/api/v3/blocklist?page=1&pageSize=500&includeSeries=true');
  const unblock = async (ids) => {
    try {
      await api('DELETE', '/api/v3/blocklist/bulk', { ids });
      toast('Removed from the blocklist');
      route();
    } catch (e) {
      toast(e.message, true);
    }
  };
  const rows = data.records.map((b) =>
    el('tr', {}, [
      el('td', { textContent: dateTime(b.date) }),
      el('td', {}, [
        b.series
          ? el('a', { href: `#/series/${b.seriesId}`, textContent: b.series.title, tip: 'Open the series' })
          : '',
      ]),
      el('td', { className: 'release-title', textContent: b.sourceTitle }),
      el('td', { textContent: qualityName(b.quality) }),
      el('td', { textContent: b.indexer || '' }),
      el('td', { className: 'muted small', textContent: b.message || '' }),
      el('td', {}, [button('Remove', 'Let this release be grabbed again', () => unblock([b.id]), 'danger')]),
    ]),
  );
  page.replaceChildren(
    tabs,
    el('div', { className: 'toolbar' }, [
      el('span', { className: 'muted', textContent: `${data.totalRecords} releases blocked` }),
      rows.length
        ? button(
            'Clear the blocklist',
            'Let every release on the blocklist be grabbed again; asks first',
            () => window.confirm('Clear the whole blocklist?') && unblock(data.records.map((b) => b.id)),
            'danger',
          )
        : null,
    ]),
    rows.length
      ? el('table', { className: 'activity' }, [
          el('thead', {}, [
            el(
              'tr',
              {},
              ['Date', 'Series', 'Source', 'Quality', 'Indexer', 'Why', ''].map((t) => el('th', { textContent: t })),
            ),
          ]),
          el('tbody', {}, rows),
        ])
      : el('p', { className: 'empty', textContent: 'The blocklist is empty.' }),
  );
  onChange = (change) => {
    if (change.entity === 'blocklist') {
      redraw(() => blocklistView(tabs));
    }
  };
}

// ── Interactive search (orch.md decision 122) ───────────────────────────────

/**
 * Searches every indexer with interactive search on and shows every release found, best first, with why a release
 * was rejected; any can be grabbed.
 *
 * @param {string} query episodeId=… or seriesId=…&seasonNumber=…
 * @param {string} label what is searched, for the title
 */
async function interactiveSearch(query, label) {
  const body = el('div', { className: 'search-results' }, [
    el('p', { className: 'muted', textContent: 'Searching the indexers…' }),
  ]);
  const dialog = modal(
    `Interactive search: ${label}`,
    [body, el('div', { className: 'browser-actions' }, [button('Close', 'Close the search', () => dialog.close())])],
    'wide',
  );
  let releases;
  try {
    releases = await api('GET', `/api/v3/release?${query}`);
  } catch (e) {
    body.replaceChildren(el('p', { className: 'empty', textContent: `Search failed: ${e.message}` }));
    return;
  }
  if (!releases.length) {
    body.replaceChildren(el('p', { className: 'empty', textContent: 'No releases found.' }));
    return;
  }
  const grab = async (r, row) => {
    if (r.rejections.length && !window.confirm(`Grab it anyway?\n\n${r.rejections.join('\n')}`)) {
      return;
    }
    try {
      await api('POST', '/api/v3/release', { guid: r.guid, indexerId: r.indexerId });
      row.classList.add('grabbed');
      toast(`Grabbed ${r.title}`);
    } catch (e) {
      toast(e.message, true);
    }
  };
  const rows = releases.map((r) => {
    const row = el('tr', { className: r.approved ? '' : 'rejected' });
    row.append(
      el('td', { textContent: r.age < 1 ? `${Math.round(r.ageHours)} h` : `${r.age} d` }),
      el('td', { className: 'release-title' }, [
        el('div', { textContent: r.title }),
        ...r.rejections.map((x) => el('div', { className: 'muted small', textContent: `✘ ${x}` })),
      ]),
      el('td', { textContent: r.indexer }),
      el('td', { textContent: bytes(r.size) }),
      el('td', { textContent: r.seeders == null ? '?' : `${r.seeders}/${r.leechers == null ? '?' : r.leechers}` }),
      el('td', { textContent: qualityName(r.quality) }),
      el('td', { textContent: (r.languages || []).map((l) => l.name).join(', ') }),
      el('td', {
        textContent: `${r.customFormatScore >= 0 ? '+' : ''}${r.customFormatScore}`,
        title: r.customFormats.map((c) => c.name).join(', '),
      }),
      el('td', {}, [
        button(
          'Grab',
          r.approved ? 'Send this release to the download client' : 'Send it anyway; it was rejected (asks first)',
          () => grab(r, row),
          r.approved ? 'primary' : '',
        ),
      ]),
    );
    return row;
  });
  body.replaceChildren(
    el('table', { className: 'activity' }, [
      el('thead', {}, [
        el(
          'tr',
          {},
          ['Age', 'Release', 'Indexer', 'Size', 'Peers', 'Quality', 'Languages', 'Score', ''].map((t) =>
            el('th', { textContent: t }),
          ),
        ),
      ]),
      el('tbody', {}, rows),
    ]),
  );
}

// ── Settings: indexers, clients, profiles, media management, Plex (orch.md decision 122) ──

/** The settings' sections. */
const SETTINGS_TABS = [
  ['general', 'General', '#/settings', 'Connections, root folders, quality profiles and tags'],
  ['indexers', 'Indexers', '#/settings/indexers', 'Torznab indexers (Prowlarr adds them) and the RSS sync'],
  [
    'clients',
    'Download clients',
    '#/settings/clients',
    'qBittorrent and Transmission, and finished or failed downloads',
  ],
  ['profiles', 'Profiles', '#/settings/profiles', 'Release and delay profiles, remote path mappings'],
  ['media', 'Media management', '#/settings/media', 'Episode file names, the recycle bin, subtitles and extras'],
  ['plex', 'Plex', '#/settings/plex', 'Tell Plex to scan a series after each import'],
];

/**
 * A form over one of the settings' addresses: what is shown is read from it, and Save sends it back.
 *
 * @param {string} title the section's title
 * @param {string} intro what it is about
 * @param {string} path e.g. /api/v3/config/indexer
 * @param {Array<object>} spec each field: key, label, type (bool, number, text or select), tip and options
 * @returns {Promise<HTMLElement>} the section
 */
async function settingsForm(title, intro, path, spec) {
  const values = await api('GET', path);
  const controls = new Map();
  const rows = spec.flatMap((f) => {
    let control;
    if (f.type === 'bool') {
      control = tickBox(values[f.key], f.tip);
    } else if (f.type === 'select') {
      control = select(f.options, values[f.key], { tip: f.tip });
    } else {
      control = textField(values[f.key], f.tip, f.type === 'number' ? { type: 'number', size: 8 } : {});
    }
    controls.set(f.key, control);
    return formRow(f.label, control);
  });
  const save = async () => {
    const body = { ...values };
    spec.forEach((f) => {
      const c = controls.get(f.key);
      body[f.key] = f.type === 'bool' ? c.checked : f.type === 'number' ? Number(c.value) : c.value;
      if (f.type === 'select' && typeof values[f.key] === 'number') {
        body[f.key] = Number(c.value);
      }
    });
    try {
      await api('PUT', path, body);
      toast(`${title} saved`);
    } catch (e) {
      toast(e.message, true);
    }
  };
  return el('section', {}, [
    el('h2', { textContent: title }),
    el('p', { className: 'muted', textContent: intro }),
    el('div', { className: 'form' }, rows),
    el('div', { className: 'toolbar' }, [button('Save', `Save the ${title.toLowerCase()}`, save, 'primary')]),
  ]);
}

/**
 * Tick boxes for the tags.
 *
 * @param {Array<object>} tags every tag
 * @param {Array<number>} chosen the ones ticked
 * @returns {{node: HTMLElement, value: () => Array<number>}} the boxes, and what is ticked
 */
function tagPicker(tags, chosen) {
  const boxes = tags.map((t) => [
    t.id,
    tickBox((chosen || []).includes(t.id), `Only for series with the tag ${t.label}`),
  ]);
  const node = el(
    'div',
    { className: 'inline' },
    boxes.length
      ? boxes.map(([id, box]) => el('label', {}, [box, ` ${tags.find((t) => t.id === id).label}`]))
      : [el('span', { className: 'muted', textContent: 'No tags: for every series' })],
  );
  return { node, value: () => boxes.filter(([, box]) => box.checked).map(([id]) => id) };
}

/**
 * The field of a provider's setting (Sonarr's fields), by its type.
 *
 * @param {object} f the field
 * @returns {HTMLElement} the control
 */
function providerField(f) {
  const tip = `${f.label}${f.advanced ? ' (advanced)' : ''}`;
  if (f.type === 'checkbox') {
    return tickBox(f.value, tip);
  }
  if (f.type === 'password' || f.privacy === 'apiKey' || f.privacy === 'password') {
    return textField(f.value, `${tip}; ******** keeps the one saved`, { type: 'password' });
  }
  if (Array.isArray(f.value)) {
    return textField(f.value.join(','), `${tip}, separated by commas`);
  }
  return textField(f.value, tip, f.type === 'number' ? { type: 'number', size: 10 } : {});
}

/**
 * The value a provider's field holds.
 *
 * @param {object} f the field as it was
 * @param {HTMLInputElement} control its control
 * @returns {any} the value to send
 */
function providerValue(f, control) {
  if (f.type === 'checkbox') {
    return control.checked;
  }
  if (Array.isArray(f.value)) {
    return control.value
      .split(',')
      .map((v) => v.trim())
      .filter(Boolean)
      .map((v) => (Number.isNaN(Number(v)) ? v : Number(v)));
  }
  if (f.type === 'number' || typeof f.value === 'number') {
    return control.value === '' ? null : Number(control.value);
  }
  return control.value;
}

/**
 * Adds or edits an indexer or a download client: it is tested when saved, and can be saved anyway.
 *
 * @param {string} kind indexer or downloadclient
 * @param {object} resource the resource (from the schema, for a new one)
 * @param {Array<object>} tags every tag
 * @param {Array<object>} clients the download clients, for an indexer's own client
 */
function providerDialog(kind, resource, tags, clients) {
  const isIndexer = kind === 'indexer';
  const name = textField(resource.name, 'The name shown in Catasonarr');
  const priority = textField(resource.priority, 'Lower comes first, 1 to 50', { type: 'number', size: 6 });
  const flags = isIndexer
    ? [
        ['enableRss', 'RSS', 'Read its newest releases at each RSS sync'],
        ['enableAutomaticSearch', 'Automatic search', 'Use it when Catasonarr searches by itself'],
        ['enableInteractiveSearch', 'Interactive search', 'Use it in the interactive search'],
      ]
    : [
        ['enable', 'Enabled', 'Send downloads to this client'],
        ['removeCompletedDownloads', 'Remove completed', 'Remove the torrent once imported (it stops seeding)'],
        ['removeFailedDownloads', 'Remove failed', 'Remove a failed torrent and its data'],
      ];
  const boxes = flags.map(([key, label, tip]) => [key, label, tickBox(resource[key], tip)]);
  const ownClient = isIndexer
    ? select([[0, 'Any'], ...clients.map((c) => [c.id, c.name])], resource.downloadClientId || 0, {
        tip: 'The client its releases go to; Any picks by tags and priority',
      })
    : null;
  const controls = resource.fields.map((f) => [f, providerField(f)]);
  const picker = tagPicker(tags, resource.tags);
  const body = () => ({
    ...resource,
    name: name.value.trim(),
    priority: Number(priority.value),
    ...Object.fromEntries(boxes.map(([key, , box]) => [key, box.checked])),
    ...(ownClient ? { downloadClientId: Number(ownClient.value) } : {}),
    fields: controls.map(([f, c]) => ({ ...f, value: providerValue(f, c) })),
    tags: picker.value(),
  });
  const save = async () => {
    const path = resource.id ? `/api/v3/${kind}/${resource.id}` : `/api/v3/${kind}`;
    const method = resource.id ? 'PUT' : 'POST';
    try {
      await api(method, path, body());
    } catch (e) {
      if (!window.confirm(`The test failed: ${e.message}\n\nSave anyway?`)) {
        return;
      }
      try {
        await api(method, `${path}?forceSave=true`, body());
      } catch (again) {
        toast(again.message, true);
        return;
      }
    }
    dialog.close();
    toast(`${body().name} saved`);
    route();
  };
  const test = async () => {
    try {
      await api('POST', `/api/v3/${kind}/test`, body());
      toast('The test passed');
    } catch (e) {
      toast(`The test failed: ${e.message}`, true);
    }
  };
  const dialog = modal(
    `${resource.id ? 'Edit' : 'Add'} ${resource.implementationName || resource.implementation}`,
    [
      el('div', { className: 'form' }, [
        ...formRow('Name', name),
        ...boxes.flatMap(([, label, box]) => formRow(label, box)),
        ...formRow('Priority', priority),
        ...(ownClient ? formRow('Download client', ownClient) : []),
        ...controls.flatMap(([f, c]) => formRow(f.label, c)),
        el('span', { textContent: 'Tags' }),
        picker.node,
      ]),
      el('div', { className: 'browser-actions' }, [
        button('Cancel', 'Close without saving', () => dialog.close()),
        button('Test', 'Check that it answers, without saving', test),
        button('Save', 'Test it and save it', save, 'primary'),
      ]),
    ],
    'wide',
  );
}

/**
 * Deletes something after asking.
 *
 * @param {string} path its address
 * @param {string} what its name, for the question
 */
async function deleteAfterAsking(path, what) {
  if (!window.confirm(`Delete ${what}?`)) {
    return;
  }
  try {
    await api('DELETE', path);
    toast(`${what} deleted`);
    route();
  } catch (e) {
    toast(e.message, true);
  }
}

/**
 * The indexers, and the RSS sync's settings.
 *
 * @param {HTMLElement} tabs the tabs
 */
async function indexersSettings(tabs) {
  const [indexers, schema, tags, clients] = await Promise.all([
    api('GET', '/api/v3/indexer'),
    api('GET', '/api/v3/indexer/schema'),
    api('GET', '/api/v3/tag'),
    api('GET', '/api/v3/downloadclient'),
  ]);
  const rows = indexers.map((i) =>
    el('tr', {}, [
      el('td', { textContent: i.name }),
      el(
        'td',
        {},
        [
          ['enableRss', 'RSS'],
          ['enableAutomaticSearch', 'Automatic'],
          ['enableInteractiveSearch', 'Interactive'],
        ].map(([k, t]) => el('span', { className: `badge ${i[k] ? 'on' : ''}`, textContent: t })),
      ),
      el('td', { textContent: `Priority ${i.priority}` }),
      el('td', { className: 'actions' }, [
        button('Edit', 'Change this indexer', () => providerDialog('indexer', i, tags, clients)),
        button(
          'Delete',
          'Delete this indexer; asks first',
          () => deleteAfterAsking(`/api/v3/indexer/${i.id}`, i.name),
          'danger',
        ),
      ]),
    ]),
  );
  const testAll = async () => {
    try {
      const results = await api('POST', '/api/v3/indexer/testall');
      const bad = results.filter((r) => !r.isValid);
      toast(
        bad.length
          ? `${bad.length} failed: ${bad.map((r) => r.validationFailures[0].errorMessage).join('; ')}`
          : 'Every indexer works',
        bad.length > 0,
      );
    } catch (e) {
      toast(e.message, true);
    }
  };
  page.replaceChildren(
    tabs,
    el('section', {}, [
      el('h2', { textContent: 'Indexers' }),
      el('p', {
        className: 'muted',
        textContent:
          'Torznab only. Prowlarr adds and updates them here as it does in Sonarr (Prowlarr → Apps → Sonarr, with Catasonarr’s address and API key).',
      }),
      rows.length
        ? el('table', {}, [el('tbody', {}, rows)])
        : el('p', { className: 'empty', textContent: 'No indexers yet.' }),
      el('div', { className: 'toolbar' }, [
        button(
          'Add Torznab',
          'Add a Torznab indexer by hand',
          () => providerDialog('indexer', { ...schema[0], name: '' }, tags, clients),
          'primary',
        ),
        indexers.length ? button('Test all', 'Check that every indexer answers', testAll) : null,
      ]),
    ]),
    await settingsForm(
      'RSS and releases',
      'How often the indexers’ newest releases are read, and which are too new or too big.',
      '/api/v3/config/indexer',
      [
        {
          key: 'rssSyncInterval',
          label: 'RSS sync interval (minutes)',
          type: 'number',
          tip: 'Every how many minutes; 0 turns it off, else at least 10',
        },
        {
          key: 'minimumAge',
          label: 'Minimum age (minutes)',
          type: 'number',
          tip: 'Wait until a release is this old before grabbing it',
        },
        {
          key: 'maximumSize',
          label: 'Maximum size (MB)',
          type: 'number',
          tip: 'Never grab a release bigger than this; 0 for no limit',
        },
      ],
    ),
  );
  onChange = (change) => {
    if (['indexer', 'settings'].includes(change.entity) && !document.querySelector('dialog[open]')) {
      redraw(() => indexersSettings(tabs));
    }
  };
}

/**
 * The download clients, and what happens to finished and failed downloads.
 *
 * @param {HTMLElement} tabs the tabs
 */
async function clientsSettings(tabs) {
  const [clients, schema, tags] = await Promise.all([
    api('GET', '/api/v3/downloadclient'),
    api('GET', '/api/v3/downloadclient/schema'),
    api('GET', '/api/v3/tag'),
  ]);
  const value = (c, name) => (c.fields.find((f) => f.name === name) || {}).value;
  const rows = clients.map((c) =>
    el('tr', {}, [
      el('td', { textContent: c.name }),
      el('td', { textContent: c.implementationName }),
      el('td', { textContent: `${value(c, 'useSsl') ? 'https' : 'http'}://${value(c, 'host')}:${value(c, 'port')}` }),
      el('td', { textContent: `Category ${value(c, 'tvCategory')}` }),
      el('td', {}, [
        el('span', { className: `badge ${c.enable ? 'on' : ''}`, textContent: c.enable ? 'Enabled' : 'Off' }),
      ]),
      el('td', { className: 'actions' }, [
        button('Edit', 'Change this client', () => providerDialog('downloadclient', c, tags, [])),
        button(
          'Delete',
          'Delete this client; asks first',
          () => deleteAfterAsking(`/api/v3/downloadclient/${c.id}`, c.name),
          'danger',
        ),
      ]),
    ]),
  );
  page.replaceChildren(
    tabs,
    el('section', {}, [
      el('h2', { textContent: 'Download clients' }),
      el('p', {
        className: 'muted',
        textContent:
          'Catasonarr only touches the torrents of its own category (catasonarr), so it lives next to Sonarr in the same client.',
      }),
      rows.length
        ? el('table', {}, [el('tbody', {}, rows)])
        : el('p', { className: 'empty', textContent: 'No download clients yet.' }),
      el(
        'div',
        { className: 'toolbar' },
        schema.map((s) =>
          button(`Add ${s.implementationName}`, `Add a ${s.implementationName} client`, () =>
            providerDialog('downloadclient', { ...s, name: s.implementationName }, tags, []),
          ),
        ),
      ),
    ]),
    await settingsForm(
      'Finished and failed downloads',
      'What Catasonarr does when a download ends.',
      '/api/v3/config/downloadclient',
      [
        {
          key: 'enableCompletedDownloadHandling',
          label: 'Import finished downloads',
          type: 'bool',
          tip: 'Move finished downloads into the series’ folders',
        },
        {
          key: 'autoRedownloadFailed',
          label: 'Search again after a failure',
          type: 'bool',
          tip: 'Blocklist a failed release and search for another',
        },
        {
          key: 'autoRedownloadFailedFromInteractiveSearch',
          label: '… also when grabbed by hand',
          type: 'bool',
          tip: 'Search again even for a release grabbed in the interactive search',
        },
      ],
    ),
  );
  onChange = (change) => {
    if (['downloadclient', 'settings'].includes(change.entity) && !document.querySelector('dialog[open]')) {
      redraw(() => clientsSettings(tabs));
    }
  };
}

/**
 * Edits a release profile, a delay profile or a remote path mapping in a dialog.
 *
 * @param {string} path e.g. /api/v3/releaseprofile
 * @param {object} item the item (id 0 for a new one)
 * @param {string} title the dialog's title
 * @param {Array<[string, string, HTMLElement, (control: HTMLElement) => any]>} fields key, label, control, reader
 */
function itemDialog(path, item, title, fields) {
  const save = async () => {
    const body = { ...item };
    fields.forEach(([key, , control, read]) => {
      body[key] = read(control);
    });
    try {
      await api(item.id ? 'PUT' : 'POST', item.id ? `${path}/${item.id}` : path, body);
      dialog.close();
      toast('Saved');
      route();
    } catch (e) {
      toast(e.message, true);
    }
  };
  const dialog = modal(title, [
    el(
      'div',
      { className: 'form' },
      fields.flatMap(([, label, control]) => formRow(label, control)),
    ),
    el('div', { className: 'browser-actions' }, [
      button('Cancel', 'Close without saving', () => dialog.close()),
      button('Save', 'Save it', save, 'primary'),
    ]),
  ]);
}

/**
 * Release and delay profiles, and remote path mappings.
 *
 * @param {HTMLElement} tabs the tabs
 */
async function profilesSettings(tabs) {
  const [release, delay, mappings, tags] = await Promise.all([
    api('GET', '/api/v3/releaseprofile'),
    api('GET', '/api/v3/delayprofile'),
    api('GET', '/api/v3/remotepathmapping'),
    api('GET', '/api/v3/tag'),
  ]);
  const tagNames = (ids) =>
    (ids || [])
      .map((id) => (tags.find((t) => t.id === id) || {}).label)
      .filter(Boolean)
      .join(', ') || 'every series';
  const lines = (c) =>
    c.value
      .split('\n')
      .map((t) => t.trim())
      .filter(Boolean);
  const tagsField = (chosen) => {
    const picker = tagPicker(tags, chosen);
    picker.node.value = picker.value;
    return picker.node;
  };
  const editRelease = (p) =>
    itemDialog('/api/v3/releaseprofile', p, p.id ? `Edit ${p.name || 'release profile'}` : 'Add a release profile', [
      ['name', 'Name', textField(p.name, 'A name, for you'), (c) => c.value],
      ['enabled', 'Enabled', tickBox(p.enabled !== false, 'Use this profile'), (c) => c.checked],
      [
        'required',
        'Must contain',
        el('textarea', {
          value: (p.required || []).join('\n'),
          rows: 3,
          tip: 'One term or /regex/ per line: a release needs one of them',
        }),
        lines,
      ],
      [
        'ignored',
        'Must not contain',
        el('textarea', {
          value: (p.ignored || []).join('\n'),
          rows: 3,
          tip: 'One term or /regex/ per line: a release with any is rejected',
        }),
        lines,
      ],
      ['tags', 'Tags', tagsField(p.tags), (c) => c.value()],
    ]);
  const editDelay = (p) =>
    itemDialog('/api/v3/delayprofile', p, p.id ? 'Edit the delay profile' : 'Add a delay profile', [
      [
        'torrentDelay',
        'Torrent delay (minutes)',
        textField(p.torrentDelay || 0, 'Wait this long after a release appears, for a better one', {
          type: 'number',
          size: 8,
        }),
        (c) => Number(c.value),
      ],
      [
        'bypassIfHighestQuality',
        'Skip the wait for the best quality',
        tickBox(p.bypassIfHighestQuality, 'Grab at once a release of the profile’s best quality'),
        (c) => c.checked,
      ],
      [
        'bypassIfAboveCustomFormatScore',
        'Skip the wait above a score',
        tickBox(p.bypassIfAboveCustomFormatScore, 'Grab at once a release with a custom format score high enough'),
        (c) => c.checked,
      ],
      [
        'minimumCustomFormatScore',
        'Minimum score',
        textField(p.minimumCustomFormatScore || 0, 'The score that skips the wait', { type: 'number', size: 8 }),
        (c) => Number(c.value),
      ],
      ...(p.id === 1 ? [] : [['tags', 'Tags', tagsField(p.tags), (c) => c.value()]]),
    ]);
  const editMapping = (m) =>
    itemDialog('/api/v3/remotepathmapping', m, m.id ? 'Edit the mapping' : 'Add a remote path mapping', [
      ['host', 'Host', textField(m.host, 'The download client’s host, as in its settings'), (c) => c.value.trim()],
      [
        'remotePath',
        'Remote path',
        textField(m.remotePath, 'The folder as the client sees it, e.g. /downloads/'),
        (c) => c.value.trim(),
      ],
      [
        'localPath',
        'Local path',
        textField(m.localPath, 'The same folder as Catasonarr sees it'),
        (c) => c.value.trim(),
      ],
    ]);
  const table = (rows, empty) =>
    rows.length ? el('table', {}, [el('tbody', {}, rows)]) : el('p', { className: 'empty', textContent: empty });
  page.replaceChildren(
    tabs,
    el('section', {}, [
      el('h2', { textContent: 'Release profiles' }),
      el('p', { className: 'muted', textContent: 'Terms a release must or must not have, per tag.' }),
      table(
        release.map((p) =>
          el('tr', {}, [
            el('td', { textContent: p.name || '(no name)' }),
            el('td', {
              textContent: `Must: ${(p.required || []).join(', ') || '—'} · Not: ${(p.ignored || []).join(', ') || '—'}`,
            }),
            el('td', { textContent: tagNames(p.tags) }),
            el('td', {}, [
              el('span', { className: `badge ${p.enabled ? 'on' : ''}`, textContent: p.enabled ? 'On' : 'Off' }),
            ]),
            el('td', { className: 'actions' }, [
              button('Edit', 'Change this profile', () => editRelease(p)),
              button(
                'Delete',
                'Delete this profile; asks first',
                () => deleteAfterAsking(`/api/v3/releaseprofile/${p.id}`, p.name || 'the profile'),
                'danger',
              ),
            ]),
          ]),
        ),
        'No release profiles.',
      ),
      el('div', { className: 'toolbar' }, [
        button(
          'Add',
          'Add a release profile',
          () => editRelease({ id: 0, enabled: true, required: [], ignored: [], indexerId: 0, tags: [] }),
          'primary',
        ),
      ]),
    ]),
    el('section', {}, [
      el('h2', { textContent: 'Delay profiles' }),
      el('p', {
        className: 'muted',
        textContent: 'How long to wait for a better release; the first one whose tags match wins, the default last.',
      }),
      table(
        delay.map((p, i) =>
          el('tr', {}, [
            el('td', { textContent: `${p.torrentDelay} min` }),
            el('td', {
              textContent: [
                p.bypassIfHighestQuality ? 'not for the best quality' : '',
                p.bypassIfAboveCustomFormatScore ? `not above ${p.minimumCustomFormatScore}` : '',
              ]
                .filter(Boolean)
                .join(', '),
            }),
            el('td', { textContent: p.id === 1 ? 'default' : tagNames(p.tags) }),
            el('td', { className: 'actions' }, [
              p.id !== 1 && i > 0
                ? button('↑', 'Check this profile before the one above', async () => {
                    try {
                      const above = delay[i - 2];
                      await api('PUT', `/api/v3/delayprofile/reorder/${p.id}${above ? `?after=${above.id}` : ''}`);
                      route();
                    } catch (e) {
                      toast(e.message, true);
                    }
                  })
                : null,
              button('Edit', 'Change this profile', () => editDelay(p)),
              p.id === 1
                ? null
                : button(
                    'Delete',
                    'Delete this profile; asks first',
                    () => deleteAfterAsking(`/api/v3/delayprofile/${p.id}`, 'the delay profile'),
                    'danger',
                  ),
            ]),
          ]),
        ),
        'No delay profiles.',
      ),
      el('div', { className: 'toolbar' }, [
        button(
          'Add',
          'Add a delay profile for some tags',
          () =>
            editDelay({
              id: 0,
              enableUsenet: false,
              enableTorrent: true,
              preferredProtocol: 'torrent',
              usenetDelay: 0,
              torrentDelay: 0,
              tags: [],
            }),
          'primary',
        ),
      ]),
    ]),
    el('section', {}, [
      el('h2', { textContent: 'Remote path mappings' }),
      el('p', {
        className: 'muted',
        textContent: 'When a client sees its downloads under another path than Catasonarr does.',
      }),
      table(
        mappings.map((m) =>
          el('tr', {}, [
            el('td', { textContent: m.host }),
            el('td', { textContent: `${m.remotePath} → ${m.localPath}` }),
            el('td', { className: 'actions' }, [
              button('Edit', 'Change this mapping', () => editMapping(m)),
              button(
                'Delete',
                'Delete this mapping; asks first',
                () => deleteAfterAsking(`/api/v3/remotepathmapping/${m.id}`, 'the mapping'),
                'danger',
              ),
            ]),
          ]),
        ),
        'No mappings: the clients see /downloads as Catasonarr does.',
      ),
      el('div', { className: 'toolbar' }, [
        button(
          'Add',
          'Add a remote path mapping',
          () => editMapping({ id: 0, host: '', remotePath: '', localPath: '' }),
          'primary',
        ),
      ]),
    ]),
  );
  onChange = (change) => {
    if (
      ['releaseprofile', 'delayprofile', 'remotepathmapping'].includes(change.entity) &&
      !document.querySelector('dialog[open]')
    ) {
      redraw(() => profilesSettings(tabs));
    }
  };
}

/**
 * The naming of episode files and folders, and the media management settings.
 *
 * @param {HTMLElement} tabs the tabs
 */
async function mediaSettings(tabs) {
  page.replaceChildren(
    tabs,
    await settingsForm(
      'Episode naming',
      'Sonarr’s tokens, e.g. {Series Title} - S{season:00}E{episode:00} - {Episode Title} {Quality Full}.',
      '/api/v3/config/naming',
      [
        {
          key: 'renameEpisodes',
          label: 'Rename episodes',
          type: 'bool',
          tip: 'Name imported files with the formats below; off keeps the release’s name',
        },
        {
          key: 'standardEpisodeFormat',
          label: 'Standard episode format',
          type: 'text',
          tip: 'The name of a standard series’ episode file',
        },
        {
          key: 'dailyEpisodeFormat',
          label: 'Daily episode format',
          type: 'text',
          tip: 'The name of a daily series’ episode file',
        },
        {
          key: 'animeEpisodeFormat',
          label: 'Anime episode format',
          type: 'text',
          tip: 'The name of an anime episode file',
        },
        {
          key: 'multiEpisodeStyle',
          label: 'Multi-episode style',
          type: 'select',
          tip: 'How a file of several episodes is numbered',
          options: [
            [0, 'Extend: S01E01-02-03'],
            [1, 'Duplicate: S01E01.S01E02'],
            [2, 'Repeat: S01E01E02E03'],
            [3, 'Scene: S01E01-E02-E03'],
            [4, 'Range: S01E01-03'],
            [5, 'Prefixed range: S01E01-E03'],
          ],
        },
        {
          key: 'seriesFolderFormat',
          label: 'Series folder format',
          type: 'text',
          tip: 'The name of a new series’ folder',
        },
        {
          key: 'seasonFolderFormat',
          label: 'Season folder format',
          type: 'text',
          tip: 'The name of a season’s folder',
        },
        {
          key: 'specialsFolderFormat',
          label: 'Specials folder format',
          type: 'text',
          tip: 'The name of the specials’ folder',
        },
        {
          key: 'replaceIllegalCharacters',
          label: 'Replace illegal characters',
          type: 'bool',
          tip: 'Replace characters a file name cannot hold; off deletes them',
        },
        {
          key: 'colonReplacementFormat',
          label: 'Colon replacement',
          type: 'select',
          tip: 'What a colon in a title becomes',
          options: [
            [0, 'Delete'],
            [1, 'Dash'],
            [2, 'Space dash'],
            [3, 'Space dash space'],
            [4, 'Smart'],
            [5, 'Custom'],
          ],
        },
        {
          key: 'customColonReplacementFormat',
          label: 'Custom colon replacement',
          type: 'text',
          tip: 'With Custom, what a colon becomes',
        },
      ],
    ),
    await settingsForm(
      'Importing',
      'What happens when files are imported, replaced or go missing.',
      '/api/v3/config/mediamanagement',
      [
        {
          key: 'recycleBin',
          label: 'Recycle bin',
          type: 'text',
          tip: 'Where replaced and deleted files go (/data/.recycle); empty deletes them at once',
        },
        {
          key: 'recycleBinCleanupDays',
          label: 'Recycle bin days',
          type: 'number',
          tip: 'Days a file stays in the recycle bin; 0 keeps it for ever',
        },
        {
          key: 'downloadPropersAndRepacks',
          label: 'Propers and repacks',
          type: 'select',
          tip: 'Whether a proper or repack replaces the file',
          options: [
            ['preferAndUpgrade', 'Prefer and upgrade'],
            ['doNotUpgrade', 'Do not upgrade automatically'],
            ['doNotPrefer', 'Do not prefer'],
          ],
        },
        {
          key: 'episodeTitleRequired',
          label: 'Episode title required',
          type: 'select',
          tip: 'Wait for the episode’s title before importing (for 48 h after it airs)',
          options: [
            ['always', 'Always'],
            ['bulkSeasonReleases', 'Only for bulk season releases'],
            ['never', 'Never'],
          ],
        },
        {
          key: 'importExtraFiles',
          label: 'Import extra files',
          type: 'bool',
          tip: 'Import the subtitles and other files next to the episode',
        },
        {
          key: 'extraFileExtensions',
          label: 'Extra file extensions',
          type: 'text',
          tip: 'Which extras, separated by commas, e.g. srt,ass,nfo',
        },
        {
          key: 'skipFreeSpaceCheckWhenImporting',
          label: 'Skip the free space check',
          type: 'bool',
          tip: 'Import even when the disk looks full',
        },
        {
          key: 'minimumFreeSpaceWhenImporting',
          label: 'Minimum free space (MB)',
          type: 'number',
          tip: 'Do not import when less than this would be left',
        },
        {
          key: 'autoUnmonitorPreviouslyDownloadedEpisodes',
          label: 'Unmonitor deleted episodes',
          type: 'bool',
          tip: 'Stop looking for an episode whose file was deleted outside Catasonarr',
        },
        {
          key: 'createEmptySeriesFolders',
          label: 'Create empty series folders',
          type: 'bool',
          tip: 'Create a series’ folder at the rescan, even with no files',
        },
        {
          key: 'deleteEmptyFolders',
          label: 'Delete empty folders',
          type: 'bool',
          tip: 'Delete empty season folders at the rescan',
        },
      ],
    ),
  );
  onChange = null;
}

/**
 * Plex: told to scan a series' folder after each import; the token is in a Secret, never here.
 *
 * @param {HTMLElement} tabs the tabs
 */
async function plexSettings(tabs) {
  const s = await api('GET', '/api/v3/catasonarr/settings');
  const form = await settingsForm(
    'Plex',
    'After each import Plex scans the series’ folder.',
    '/api/v3/catasonarr/settings',
    [
      { key: 'plexEnabled', label: 'Tell Plex', type: 'bool', tip: 'Ask Plex to scan the series after each import' },
      { key: 'plexUrl', label: 'Plex’s address', type: 'text', tip: 'e.g. http://plex.example:32400' },
      {
        key: 'plexMapFrom',
        label: 'Path in Catasonarr',
        type: 'text',
        tip: 'When Plex sees the library under another path: Catasonarr’s, e.g. /data',
      },
      { key: 'plexMapTo', label: 'Same path in Plex', type: 'text', tip: 'And Plex’s, e.g. /media' },
    ],
  );
  const url = form.querySelector('input:not([type=checkbox])');
  const test = async () => {
    try {
      const answer = await api('POST', '/api/v3/catasonarr/plex/test', { plexUrl: url.value });
      toast(`Plex answered; TV libraries: ${answer.libraries.join(', ') || 'none'}`);
    } catch (e) {
      toast(e.message, true);
    }
  };
  form.querySelector('.toolbar').append(button('Test', 'Check that Plex answers with the token', test));
  form.insertBefore(
    el('p', {
      className: s.plexTokenSet ? 'muted' : 'empty',
      textContent: s.plexTokenSet
        ? 'The token is set (Secret catasonarr-plex).'
        : 'No token yet: run microshift/apps/catacumbas69-apps/catasonarr/catasonarr-secret.sh plex on the Mac.',
    }),
    form.querySelector('.form'),
  );
  page.replaceChildren(tabs, form);
  onChange = null;
}

// ── Tooltips (orch.md decision 121) ──────────────────────────────────────────

/** The one tooltip of the page, in the top layer (a popover), so it also shows over the folder browser. */
const tooltip = document.getElementById('tooltip');

/** What the tooltip describes now, and the timer that shows it. */
let tipped = null;
let tipTimer = null;

/**
 * The control a tooltip describes, for aria-describedby: the element itself, or the field inside its label.
 *
 * @param {HTMLElement} node the element with data-tip
 * @returns {HTMLElement} the control
 */
function tipControl(node) {
  return node.matches('label') ? node.querySelector('input, select') || node : node;
}

/** Hides the tooltip, and forgets a pending one. */
function hideTip() {
  clearTimeout(tipTimer);
  if (tipped) {
    tipControl(tipped).removeAttribute('aria-describedby');
    tipped = null;
  }
  if (tooltip.matches(':popover-open')) {
    tooltip.hidePopover();
  }
}

/**
 * Shows the tooltip of an element after half a second: above it, or below when there is no room above.
 *
 * @param {HTMLElement} node the element with data-tip
 */
function showTip(node) {
  if (node === tipped) {
    return;
  }
  hideTip();
  tipped = node;
  tipTimer = setTimeout(() => {
    if (!node.isConnected || !node.dataset.tip) {
      return;
    }
    tooltip.textContent = node.dataset.tip;
    tooltip.showPopover();
    const box = node.getBoundingClientRect();
    const tip = tooltip.getBoundingClientRect();
    const gap = 8;
    const below = box.top - tip.height - gap < 4;
    const left = Math.min(Math.max(4, box.left + box.width / 2 - tip.width / 2), window.innerWidth - tip.width - 4);
    tooltip.style.left = `${left}px`;
    tooltip.style.top = `${below ? box.bottom + gap : box.top - tip.height - gap}px`;
    tooltip.style.setProperty('--arrow-x', `${box.left + box.width / 2 - left}px`);
    tooltip.dataset.side = below ? 'below' : 'above';
    tipControl(node).setAttribute('aria-describedby', 'tooltip');
  }, 500);
}

document.addEventListener('pointerover', (e) => {
  const node = e.target.closest('[data-tip]');
  if (node) {
    showTip(node);
  } else if (tipped && !tipped.contains(e.target)) {
    hideTip();
  }
});
document.addEventListener('pointerout', (e) => {
  if (tipped && !tipped.contains(e.relatedTarget)) {
    hideTip();
  }
});
document.addEventListener('focusin', (e) => {
  const node = e.target.closest('[data-tip]');
  if (node && e.target.matches(':focus-visible')) {
    showTip(node);
  }
});
document.addEventListener('focusout', hideTip);
document.addEventListener('pointerdown', hideTip);
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    hideTip();
  }
});
window.addEventListener('scroll', hideTip, true);

// ── Navigation ───────────────────────────────────────────────────────────────

/** Draws the page the address asks for. */
async function route() {
  generation += 1;
  onChange = null;
  hideTip();
  const hash = window.location.hash || '#/';
  const detail = hash.match(/^#\/series\/(\d+)$/);
  const sub = hash.match(/^#\/(activity|settings)(?:\/([a-z]+))?$/);
  const name = detail ? 'series' : sub ? sub[1] : { '#/add': 'add', '#/import': 'import' }[hash] || 'series';
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
      await settingsPage(sub[2] || 'general');
    } else if (name === 'activity') {
      await activityPage(sub[2] || 'queue');
    } else {
      await seriesPage();
    }
  } catch (e) {
    page.replaceChildren(el('p', { className: 'empty', textContent: `Not loaded: ${e.message}` }));
  }
}

// ── Live updates (orch.md decision 118) ─────────────────────────────────────────

/** Changes waiting to be told in one notice; told every 2 s. */
const pendingNotices = [];

/** When the page is drawn again after a change: once the burst is over, at most every 2 s. */
let redrawTimer = null;

/**
 * Draws the page shown again after a change, keeping where it was scrolled; a burst of changes (the 12-hourly
 * refresh) draws it once.
 *
 * @param {() => Promise<void>} draw the page's own drawing
 */
function redraw(draw) {
  clearTimeout(redrawTimer);
  const mine = generation;
  redrawTimer = setTimeout(async () => {
    if (mine !== generation) {
      return; // another page by now
    }
    const y = window.scrollY;
    try {
      await draw();
      window.scrollTo(0, y);
    } catch (e) {
      toast(`Not updated: ${e.message}`, true);
    }
  }, 500);
}

/**
 * Who made a change, as a person reads it.
 *
 * @param {string} [actor] a user, api-key@<address>, an address, or catasonarr
 * @returns {string} e.g. "API key (10.0.0.5)"
 */
function actorName(actor) {
  if (!actor) {
    return 'someone';
  }
  if (actor === 'catasonarr') {
    return 'Catasonarr';
  }
  const key = actor.match(/^api-key@(.+)$/);
  return key ? `API key (${key[1]})` : actor;
}

/** Tells the changes of the last 2 s in one notice: one by one when few, counted when many. */
function tellChanges() {
  if (!pendingNotices.length) {
    return;
  }
  const changes = pendingNotices
    .splice(0)
    .filter((c) => !(c.actor === 'catasonarr' && ['rss', 'recyclebin', 'command'].includes(c.entity)));
  if (!changes.length) {
    return;
  }
  if (changes.length <= 3) {
    toast(changes.map((c) => `${c.subject || c.entity} ${c.action} by ${actorName(c.actor)}`).join(' · '));
    return;
  }
  const counts = new Map();
  changes.forEach((c) => {
    const key = `${c.entity} ${c.action}`;
    counts.set(key, (counts.get(key) || 0) + 1);
  });
  toast(`${changes.length} changes: ${[...counts].map(([k, n]) => `${n} ${k}`).join(', ')}`);
}

/**
 * Listens to Catasonarr's changes through the WebSocket /api/v1/updates, and connects again when it closes,
 * waiting a little longer each time; after a reconnection the page is drawn again, for what it missed.
 *
 * @param {number} [wait] seconds before the next attempt
 * @param {boolean} [missed] whether changes may have been missed
 */
function listen(wait = 1, missed = false) {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
  const socket = new WebSocket(`${scheme}://${window.location.host}/api/v1/updates`);
  socket.addEventListener('open', () => {
    if (missed) {
      route();
    }
  });
  socket.addEventListener('message', (message) => {
    let change;
    try {
      change = JSON.parse(message.data);
    } catch {
      return;
    }
    pendingNotices.push(change);
    if (onChange) {
      onChange(change);
    }
  });
  socket.addEventListener('close', () => {
    setTimeout(() => listen(Math.min(wait * 2, 30), true), wait * 1000);
  });
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
listen();
setInterval(tellChanges, 2000);
