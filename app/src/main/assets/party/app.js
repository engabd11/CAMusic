/* CAMusic listening party: the guest page. No libraries, no network beyond this phone.
 * Everything from the server is put on the page with textContent, never as markup. */
(function () {
  'use strict';

  var base = location.pathname.replace(/[^/]*$/, '');
  var api = base + 'api/';
  var NAME_KEY = 'camusic-party-name';
  var ID_KEY = 'camusic-party-guest:' + base;
  var POLL_MS = 2500;

  var guestId = null;
  var pollTimer = null;
  var searchTimer = null;
  var lastQuery = '';
  var noticeTimer = null;
  var busy = {};

  function $(id) { return document.getElementById(id); }

  function store(key, value) {
    try { if (value === undefined) return localStorage.getItem(key); localStorage.setItem(key, value); } catch (e) { /* private mode */ }
    return null;
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }

  function notice(text) {
    var n = $('notice');
    n.textContent = text;
    n.hidden = false;
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(function () { n.hidden = true; }, 3500);
  }

  function request(method, path, body) {
    var headers = {};
    if (guestId) headers['X-Guest-Id'] = guestId;
    var init = { method: method, headers: headers, cache: 'no-store', credentials: 'omit' };
    if (body) { headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(body); }
    return fetch(api + path, init).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (data) {
        return { ok: res.ok, status: res.status, data: data };
      });
    });
  }

  function mmss(sec) {
    sec = Math.max(0, Math.floor(sec || 0));
    return Math.floor(sec / 60) + ':' + ('0' + (sec % 60)).slice(-2);
  }

  // --- joining -------------------------------------------------------------------

  function showJoin() {
    $('join').hidden = false;
    $('party').hidden = true;
    clearTimeout(pollTimer);
    $('name').focus();
  }

  function join(name) {
    return request('POST', 'join', { name: name, guestId: guestId || undefined }).then(function (r) {
      if (!r.ok) { notice(r.data.error || 'Could not join just now.'); return false; }
      guestId = r.data.guestId;
      store(ID_KEY, guestId);
      store(NAME_KEY, r.data.name);
      $('who').textContent = 'Joined as ' + r.data.name;
      $('who').hidden = false;
      $('join').hidden = true;
      $('party').hidden = false;
      poll();
      return true;
    });
  }

  $('join-form').addEventListener('submit', function (ev) {
    ev.preventDefault();
    var name = $('name').value.trim();
    if (name) join(name);
  });

  // --- state ---------------------------------------------------------------------

  function art(id, cls) {
    if (!id) return el('div', 'art placeholder', '♫');
    var img = el('img', cls || 'art');
    img.alt = '';
    img.width = 56; img.height = 56;
    img.loading = 'lazy';
    img.src = api + 'art/' + encodeURIComponent(id);
    return img;
  }

  function render(s) {
    var np = s.nowPlaying;
    $('now-title-text').textContent = np ? np.title : 'Nothing playing yet';
    $('now-artist').textContent = np ? np.artist : '';
    var nowArt = $('now-art');
    if (np && np.art) {
      nowArt.src = api + 'art/' + encodeURIComponent(np.art);
      nowArt.hidden = false; $('now-art-empty').hidden = true;
    } else {
      nowArt.hidden = true; $('now-art-empty').hidden = false;
    }
    var prog = $('now-progress');
    if (np && np.durationSec > 0) {
      prog.max = np.durationSec; prog.value = Math.min(np.positionSec, np.durationSec); prog.hidden = false;
      prog.setAttribute('aria-valuetext', mmss(np.positionSec) + ' of ' + mmss(np.durationSec));
    } else { prog.hidden = true; }

    var list = $('next');
    list.textContent = '';
    (s.upNext || []).forEach(function (u) {
      var li = el('li');
      li.appendChild(art(u.art));
      var box = el('div', 'grow');
      box.appendChild(el('p', 'title', u.title));
      box.appendChild(el('p', 'sub', u.artist));
      if (u.addedBy) box.appendChild(el('p', 'by', 'Added by ' + u.addedBy));
      li.appendChild(box);
      list.appendChild(li);
    });
    $('next-empty').hidden = (s.upNext || []).length > 0;

    var skip = $('skip');
    skip.hidden = !(s.skip && s.skip.enabled);
    if (!skip.hidden) {
      skip.setAttribute('aria-pressed', s.skip.voted ? 'true' : 'false');
      skip.disabled = !np;
      skip.textContent = s.skip.voted ? 'You voted to skip' : 'Vote to skip';
      $('skip-note').textContent = s.skip.votes + ' of ' + s.skip.needed + ' votes';
    } else { $('skip-note').textContent = ''; }

    if (s.pendingMine > 0) {
      $('results-note').textContent = s.pendingMine + (s.pendingMine === 1 ? ' song is' : ' songs are') + ' waiting for the host to approve.';
    }
  }

  function poll() {
    clearTimeout(pollTimer);
    if (!guestId) return;
    request('GET', 'state').then(function (r) {
      if (r.status === 401) {
        // The host restarted the party or we were dropped; join again under the saved name.
        var name = store(NAME_KEY);
        guestId = null;
        if (name) join(name); else showJoin();
        return;
      }
      if (r.ok) render(r.data);
    }).catch(function () { /* host unreachable: keep trying quietly */ }).then(function () {
      pollTimer = setTimeout(poll, document.hidden ? POLL_MS * 3 : POLL_MS);
    });
  }

  document.addEventListener('visibilitychange', function () { if (!document.hidden && guestId) poll(); });

  // --- skip ----------------------------------------------------------------------

  $('skip').addEventListener('click', function () {
    if (busy.skip) return;
    busy.skip = true;
    request('POST', 'skip', {}).then(function (r) {
      if (!r.ok) notice(r.data.error || 'Could not vote just now.');
      else if (r.data.skipped) notice('Skipping to the next song!');
      else notice('Vote counted.');
      poll();
    }).catch(function () { notice('Could not reach the host.'); }).then(function () { busy.skip = false; });
  });

  // --- search --------------------------------------------------------------------

  function renderResults(items) {
    var list = $('results');
    list.textContent = '';
    items.forEach(function (it) {
      var li = el('li');
      li.appendChild(art(it.art));
      var box = el('div', 'grow');
      box.appendChild(el('p', 'title', it.title));
      box.appendChild(el('p', 'sub', it.artist + (it.album ? ' · ' + it.album : '')));
      li.appendChild(box);
      var btn = el('button', 'add', 'Add');
      btn.type = 'button';
      btn.setAttribute('aria-label', 'Add ' + it.title + ' by ' + it.artist);
      btn.addEventListener('click', function () { add(it, btn); });
      li.appendChild(btn);
      list.appendChild(li);
    });
  }

  function search(q) {
    if (q.length < 2) { renderResults([]); $('results-note').textContent = ''; return; }
    $('results-note').textContent = 'Searching...';
    request('GET', 'search?q=' + encodeURIComponent(q)).then(function (r) {
      if (q !== lastQuery) return;
      if (!r.ok) { $('results-note').textContent = r.data.error || 'Search did not work just now.'; return; }
      var items = r.data.results || [];
      renderResults(items);
      $('results-note').textContent = items.length ? '' : 'Nothing found. Try another spelling?';
    }).catch(function () { $('results-note').textContent = 'Could not reach the host.'; });
  }

  $('q').addEventListener('input', function () {
    lastQuery = $('q').value.trim();
    clearTimeout(searchTimer);
    searchTimer = setTimeout(function () { search(lastQuery); }, 350);
  });
  $('search-form').addEventListener('submit', function (ev) {
    ev.preventDefault();
    clearTimeout(searchTimer);
    lastQuery = $('q').value.trim();
    search(lastQuery);
  });

  function add(item, btn) {
    if (btn.disabled) return;
    btn.disabled = true;
    request('POST', 'add', { id: item.id }).then(function (r) {
      if (r.ok) {
        btn.textContent = r.data.status === 'pending' ? 'Sent' : 'Added';
        notice(r.data.status === 'pending' ? 'Sent to the host for approval.' : 'Added to the queue!');
        poll();
      } else {
        btn.disabled = false;
        notice(r.data.error || 'Could not add that one.');
      }
    }).catch(function () { btn.disabled = false; notice('Could not reach the host.'); });
  }

  // --- start ---------------------------------------------------------------------

  var savedName = store(NAME_KEY);
  guestId = store(ID_KEY);
  if (savedName) join(savedName); else showJoin();
})();
