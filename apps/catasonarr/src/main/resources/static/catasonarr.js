// Catasonarr's pages (orch.md decisions 109 and 112): the series, adding a series, a series with its episodes, and
// the settings. They use Sonarr's API v3 with the Dex session and the CSRF token, as Sonarr's own page uses it.

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
    series.length ? grid : el('p', { className: 'empty', textContent: 'No series yet: add one.' }),
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
    folders.length ? null : el('p', { className: 'muted', textContent: 'Add a root folder in Settings first.' }),
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

/** Root folders, quality profiles and tags. */
async function settingsPage() {
  const [folders, profiles, tags] = await Promise.all([
    api('GET', '/api/v3/rootfolder'),
    api('GET', '/api/v3/qualityprofile'),
    api('GET', '/api/v3/tag'),
  ]);
  const path = el('input', { placeholder: '/data/TV', size: 30, 'aria-label': 'Root folder path' });
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
  const hash = window.location.hash || '#/';
  const detail = hash.match(/^#\/series\/(\d+)$/);
  const name = detail ? 'series' : { '#/add': 'add', '#/settings': 'settings' }[hash] || 'series';
  document
    .querySelectorAll('.topbar-nav a')
    .forEach((a) => a.classList.toggle('active', a.dataset.page === name && !detail));
  page.replaceChildren(el('p', { className: 'muted', textContent: 'Loading…' }));
  try {
    if (detail) {
      await detailPage(Number(detail[1]));
    } else if (name === 'add') {
      await addPage();
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
