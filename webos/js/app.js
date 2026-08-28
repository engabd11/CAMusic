/**
 * CAMusic webOS TV App
 *
 * Music streaming for LG webOS TVs — multi-library playback (Navidrome,
 * Subsonic, Jellyfin, Music Assistant) with direct Hue Entertainment
 * light sync via a packaged JS Service.
 *
 * Architecture:
 *   - HTML5 <audio> element for playback (single element — webOS constraint)
 *   - Subsonic API (fetch) for Navidrome/Subsonic browsing & streaming
 *   - Music Assistant WebSocket (JSON-RPC) for MA playback control
 *   - JS Service (Node.js) for Hue Entertainment UDP streaming
 *   - D-pad navigation via focus management (arrow keys + OK + Back)
 *
 * Constraints (from official LG webOS TV developer documentation):
 *   - Only ONE <audio> element per app (hardware decoder conflict)
 *   - Back button keyCode is 461 (NOT 8)
 *   - Must pause audio on visibilityChange (app suspend)
 *   - Every focusable element needs a visible selection effect
 */

(function () {
    'use strict';

    // ── Key codes (webOS-specific) ─────────────────────────────
    const KEYS = {
        ENTER: 13,
        BACK: 461,
        EXIT: 412,
        LEFT: 37,
        RIGHT: 39,
        UP: 38,
        DOWN: 40,
        PLAY: 415,
        PAUSE: 19,
        STOP: 413,
        RED: 403,
        GREEN: 404,
        YELLOW: 405,
        BLUE: 406,
    };

    // ── App state ──────────────────────────────────────────────
    const state = {
        servers: [],
        activeServer: null,
        subsonicClient: null,
        maClient: null,
        hueService: null,

        // Playback
        audio: null,
        queue: [],
        queueIndex: 0,
        isPlaying: false,
        currentTrack: null,
        positionMs: 0,
        durationMs: 0,

        // Hue
        hueConnected: false,
        hueSyncing: false,
        hueConfig: { bridgeIp: '', username: '', groupId: '' },

        // UI
        activeTab: 'library',
        focusMap: [],
        focusIndex: 0,
        currentView: 'library',
        albumDetail: null,
    };

    // ── Storage (localStorage persists across app launches) ────
    const Storage = {
        get(key, def) {
            try {
                const v = localStorage.getItem('camusic:' + key);
                return v ? JSON.parse(v) : def;
            } catch { return def; }
        },
        set(key, val) {
            try { localStorage.setItem('camusic:' + key, JSON.stringify(val)); } catch {}
        },
        remove(key) {
            try { localStorage.removeItem('camusic:' + key); } catch {}
        },
    };

    // ════════════════════════════════════════════════════════════
    // Subsonic API Client (Navidrome / Subsonic / OpenSubsonic)
    // ════════════════════════════════════════════════════════════

    class SubsonicClient {
        constructor(url, username, password) {
            this.baseUrl = url.replace(/\/$/, '').replace(/\/rest$/, '');
            this.username = username;
            this.password = password;
            this.salt = null;
            this.token = null;
            this.apiVersion = '1.16.1';
            this.clientName = 'CAMusic';
        }

        _authParams() {
            // Subsonic auth: salt + MD5(password+salt) token
            if (!this.salt) {
                const arr = new Uint8Array(16);
                crypto.getRandomValues(arr);
                this.salt = Array.from(arr).map(b => b.toString(16).padStart(2, '0')).join('');
                // MD5 not available via Web Crypto (only SHA), use hex of password+salt
                // Navidrome supports both plain (p=) and token (t=+s=) auth
            }
            return `u=${encodeURIComponent(this.username)}&p=${encodeURIComponent(this.password)}&v=${this.apiVersion}&c=${this.clientName}&f=json`;
        }

        async _api(endpoint, params = '') {
            const url = `${this.baseUrl}/rest/${endpoint}?${this._authParams()}&${params}`;
            try {
                const res = await fetch(url);
                const data = await res.json();
                if (data['subsonic-response']) {
                    const r = data['subsonic-response'];
                    if (r.status === 'ok') return r;
                    throw new Error(r.error?.message || 'API error');
                }
                throw new Error('Invalid response');
            } catch (e) {
                console.error(`Subsonic ${endpoint} failed:`, e);
                throw e;
            }
        }

        async ping() {
            try {
                const r = await this._api('ping.view');
                return r.status === 'ok';
            } catch { return false; }
        }

        async getArtists() {
            const r = await this._api('getArtists.view');
            const artists = [];
            for (const idx of r.artists?.index || []) {
                for (const a of idx.artist || []) {
                    artists.push({
                        id: a.id,
                        name: a.name,
                        albumCount: a.albumCount || 0,
                        coverArt: a.coverArt,
                        type: 'artist',
                    });
                }
            }
            return artists;
        }

        async getAlbumList(type = 'newest', size = 50) {
            const r = await this._api('getAlbumList2.view', `type=${type}&size=${size}`);
            return (r.albumList2?.album || []).map(a => ({
                id: a.id,
                name: a.name,
                artist: a.artist,
                artistId: a.artistId,
                coverArt: a.coverArt,
                songCount: a.songCount || 0,
                year: a.year,
                type: 'album',
            }));
        }

        async getAlbum(id) {
            const r = await this._api('getAlbum.view', `id=${id}`);
            const album = r.album;
            return {
                id: album.id,
                name: album.name,
                artist: album.artist,
                artistId: album.artistId,
                coverArt: album.coverArt,
                tracks: (album.song || []).map(s => this._mapSong(s)),
            };
        }

        async getArtist(id) {
            const r = await this._api('getArtist.view', `id=${id}`);
            const artist = r.artist;
            const albums = await this._api('getArtist.view', `id=${id}`);
            return {
                id: artist.id,
                name: artist.name,
                albums: (albums.artist?.album || []).map(a => ({
                    id: a.id,
                    name: a.name,
                    coverArt: a.coverArt,
                    year: a.year,
                    songCount: a.songCount || 0,
                    type: 'album',
                })),
            };
        }

        async search(query, count = 30) {
            const r = await this._api('search3.view', `query=${encodeURIComponent(query)}&albumCount=${count}&songCount=${count}&artistCount=${count}`);
            return {
                artists: (r.searchResult3?.artist || []).map(a => ({...a, type: 'artist'})),
                albums: (r.searchResult3?.album || []).map(a => ({
                    id: a.id, name: a.name, artist: a.artist, coverArt: a.coverArt, type: 'album',
                })),
                songs: (r.searchResult3?.song || []).map(s => this._mapSong(s)),
            };
        }

        async getPlaylists() {
            const r = await this._api('getPlaylists.view');
            return (r.playlists?.playlist || []).map(p => ({
                id: p.id,
                name: p.name,
                songCount: p.songCount || 0,
                type: 'playlist',
            }));
        }

        async getPlaylist(id) {
            const r = await this._api('getPlaylist.view', `id=${id}`);
            return (r.playlist?.entry || []).map(s => this._mapSong(s));
        }

        async getRandomSongs(size = 100) {
            const r = await this._api('getRandomSongs.view', `size=${size}`);
            return (r.randomSongs?.song || []).map(s => this._mapSong(s));
        }

        streamUrl(id, format = 'raw') {
            const fmt = format !== 'raw' ? `&maxBitRate=0&format=${format}` : '';
            return `${this.baseUrl}/rest/stream?id=${id}&${this._authParams()}${fmt}`;
        }

        coverUrl(id, size = 300) {
            if (!id) return '';
            return `${this.baseUrl}/rest/getCoverArt?id=${id}&size=${size}&${this._authParams()}`;
        }

        _mapSong(s) {
            return {
                id: s.id,
                title: s.title,
                artist: s.artist,
                album: s.album,
                albumId: s.albumId,
                coverArt: s.coverArt,
                duration: s.duration || 0,
                track: s.track || 0,
                discNumber: s.discNumber || 1,
                suffix: s.suffix || 'mp3',
                type: 'song',
            };
        }
    }

    // ════════════════════════════════════════════════════════════
    // Music Assistant Client (WebSocket JSON-RPC)
    // ════════════════════════════════════════════════════════════

    class MaClient {
        constructor(url, username, password) {
            this.wsUrl = url.replace(/^http/, 'ws') + '/websocket';
            this.baseUrl = url;
            this.username = username;
            this.password = password;
            this.ws = null;
            this.reqId = 1;
            this.pending = new Map();
            this.listeners = [];
        }

        connect() {
            return new Promise((resolve, reject) => {
                try {
                    this.ws = new WebSocket(this.wsUrl);
                } catch (e) { return reject(e); }

                this.ws.onopen = () => {
                    console.log('MA WebSocket connected');
                    resolve();
                };
                this.ws.onerror = (e) => {
                    console.error('MA WebSocket error:', e);
                    reject(new Error('WebSocket connection failed'));
                };
                this.ws.onclose = () => {
                    console.log('MA WebSocket closed');
                };
                this.ws.onmessage = (e) => {
                    try {
                        const msg = JSON.parse(e.data);
                        this._handleMessage(msg);
                    } catch (err) {
                        console.error('MA parse error:', err);
                    }
                };
            });
        }

        _handleMessage(msg) {
            // Resolve pending requests
            if (msg.id && this.pending.has(msg.id)) {
                const { resolve, reject } = this.pending.get(msg.id);
                this.pending.delete(msg.id);
                if (msg.error) reject(new Error(msg.error.message || 'MA error'));
                else resolve(msg.result);
                return;
            }
            // Notify listeners of events
            for (const cb of this.listeners) {
                try { cb(msg); } catch {}
            }
        }

        async call(method, params = {}) {
            const id = this.reqId++;
            return new Promise((resolve, reject) => {
                this.pending.set(id, { resolve, reject });
                this.ws.send(JSON.stringify({
                    jsonrpc: '2.0',
                    method,
                    params,
                    id,
                }));
                // Timeout after 15s
                setTimeout(() => {
                    if (this.pending.has(id)) {
                        this.pending.delete(id);
                        reject(new Error('MA request timeout'));
                    }
                }, 15000);
            });
        }

        onEvent(callback) {
            this.listeners.push(callback);
        }

        async getPlayers() {
            return this.call('player/get_players');
        }

        async playMedia(playerId, uri) {
            return this.call('player/play_media', { player_id: playerId, uri });
        }

        async play(playerId) {
            return this.call('player/play', { player_id: playerId });
        }

        async pause(playerId) {
            return this.call('player/pause', { player_id: playerId });
        }

        async next(playerId) {
            return this.call('player/next', { player_id: playerId });
        }

        async previous(playerId) {
            return this.call('player/previous', { player_id: playerId });
        }

        async seek(playerId, position) {
            return this.call('player/seek', { player_id: playerId, position });
        }

        async getQueue(playerId) {
            return this.call('player/get_queue', { player_id: playerId });
        }

        async getLibraryItems() {
            return this.call('library/get_artists');
        }

        disconnect() {
            if (this.ws) {
                this.ws.close();
                this.ws = null;
            }
            this.pending.clear();
            this.listeners = [];
        }
    }

    // ════════════════════════════════════════════════════════════
    // Hue Entertainment JS Service bridge
    // ════════════════════════════════════════════════════════════

    class HueService {
        constructor() {
            this.connected = false;
            this.syncing = false;
            this.config = Storage.get('hueConfig', { bridgeIp: '', username: '', groupId: '' });
        }

        // Communicate with the JS Service via Luna Service API
        _callService(method, params = {}) {
            return new Promise((resolve, reject) => {
                if (typeof webOS === 'undefined' || !webOS.service) {
                    // Fallback: no webOS environment (testing in browser)
                    console.warn('webOS service not available — Hue feature requires real TV or simulator');
                    reject(new Error('webOS service API not available'));
                    return;
                }
                webOS.service.request('luna://com.abdullah.camusic.hueservice', {
                    method,
                    parameters: params,
                    onSuccess: resolve,
                    onFailure: reject,
                });
            });
        }

        async connect(bridgeIp, username, groupId) {
            this.config = { bridgeIp, username, groupId };
            Storage.set('hueConfig', this.config);
            try {
                const result = await this._callService('connect', { bridgeIp, username, groupId });
                this.connected = true;
                return result;
            } catch (e) {
                console.error('Hue connect failed:', e);
                throw e;
            }
        }

        async startSync() {
            await this._callService('startSync', this.config);
            this.syncing = true;
        }

        async stopSync() {
            await this._callService('stopSync', {});
            this.syncing = false;
        }

        async disconnect() {
            await this._callService('disconnect', {});
            this.connected = false;
            this.syncing = false;
        }

        async getLightCount() {
            const result = await this._callService('getLightCount', {});
            return result.count || 0;
        }
    }

    // ════════════════════════════════════════════════════════════
    // Audio Player (single <audio> element — webOS constraint)
    // ════════════════════════════════════════════════════════════

    class AudioPlayer {
        constructor() {
            this.audio = new Audio();
            this.audio.preload = 'auto';
            this.audio.crossOrigin = 'anonymous';
            this._setupEvents();
        }

        _setupEvents() {
            this.audio.addEventListener('timeupdate', () => {
                state.positionMs = (this.audio.currentTime || 0) * 1000;
                state.durationMs = (this.audio.duration || 0) * 1000;
                UI.updateNowPlaying();
            });

            this.audio.addEventListener('ended', () => {
                App.nextTrack();
            });

            this.audio.addEventListener('play', () => {
                state.isPlaying = true;
                UI.updateNowPlaying();
            });

            this.audio.addEventListener('pause', () => {
                state.isPlaying = false;
                UI.updateNowPlaying();
            });

            this.audio.addEventListener('error', (e) => {
                console.error('Audio error:', e);
                state.isPlaying = false;
                UI.updateNowPlaying();
            });
        }

        play(url) {
            this.audio.src = url;
            this.audio.play().catch(e => console.error('Play failed:', e));
        }

        pause() { this.audio.pause(); }
        resume() { this.audio.play().catch(e => console.error('Resume failed:', e)); }

        seek(ms) {
            if (this.audio.duration) {
                this.audio.currentTime = ms / 1000;
            }
        }

        get isPlaying() { return !this.audio.paused && !this.audio.ended; }
        get position() { return (this.audio.currentTime || 0) * 1000; }
        get duration() { return (this.audio.duration || 0) * 1000; }
    }

    // ════════════════════════════════════════════════════════════
    // UI Controller (D-pad navigation, views, rendering)
    // ════════════════════════════════════════════════════════════

    const UI = {
        init() {
            this._setupTabs();
            this._setupKeyboard();
            this._setupNowPlaying();
            this._setupSearch();
            this._setupHue();
            this._setupSettings();
            this._buildFocusMap();
        },

        _setupTabs() {
            const tabs = document.querySelectorAll('.nav-tab');
            tabs.forEach(tab => {
                tab.addEventListener('click', () => {
                    const target = tab.dataset.tab;
                    this.switchTab(target);
                });
            });
        },

        switchTab(tab) {
            state.activeTab = tab;
            // Update tab focus
            document.querySelectorAll('.nav-tab').forEach(t => {
                t.dataset.focused = (t.dataset.tab === tab) ? 'true' : 'false';
            });
            // Show/hide views
            document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
            document.getElementById('view-' + tab).classList.add('active');
            // Rebuild focus map for new view
            this._buildFocusMap();
        },

        _setupKeyboard() {
            document.addEventListener('keydown', (e) => {
                switch (e.keyCode) {
                    case KEYS.BACK:
                        this._handleBack();
                        break;
                    case KEYS.LEFT:
                        this._moveFocus(-1, 0);
                        break;
                    case KEYS.RIGHT:
                        this._moveFocus(1, 0);
                        break;
                    case KEYS.UP:
                        this._moveFocus(0, -1);
                        break;
                    case KEYS.DOWN:
                        this._moveFocus(0, 1);
                        break;
                    case KEYS.ENTER:
                        this._activateFocused();
                        break;
                    case KEYS.PLAY:
                        if (state.isPlaying) state.audio.pause();
                        else if (state.currentTrack) state.audio.resume();
                        break;
                    case KEYS.STOP:
                        state.audio.pause();
                        break;
                }
            });
        },

        _handleBack() {
            // If in album detail, go back to library
            if (state.albumDetail) {
                state.albumDetail = null;
                this.switchTab('library');
                this.renderLibrary();
                return;
            }
            // If on a non-library tab, go to library
            if (state.activeTab !== 'library') {
                this.switchTab('library');
                return;
            }
            // At root — exit app (webOS platformBack)
            if (typeof webOS !== 'undefined' && webOS.platformBack) {
                webOS.platformBack();
            }
        },

        _buildFocusMap() {
            // Collect all focusable elements in the active view + header + now playing
            const view = document.querySelector('.view.active') || document.body;
            const headerFocusables = document.querySelectorAll('#header .spotlight');
            const viewFocusables = view.querySelectorAll('.spotlight');
            const npFocusables = document.querySelectorAll('#nowPlaying:not(.hidden) .spotlight');

            state.focusMap = [...headerFocusables, ...viewFocusables, ...npFocusables];

            // Find the element with data-focused="true" or default to first
            state.focusIndex = 0;
            for (let i = 0; i < state.focusMap.length; i++) {
                if (state.focusMap[i].dataset.focused === 'true') {
                    state.focusIndex = i;
                    break;
                }
            }
            this._updateFocus();
        },

        _updateFocus() {
            state.focusMap.forEach((el, i) => {
                if (i === state.focusIndex) {
                    el.classList.add('spotlight-focused');
                    el.dataset.focused = 'true';
                } else {
                    el.classList.remove('spotlight-focused');
                    if (el.dataset.focused !== 'true') {
                        // Only clear if it wasn't the default
                    }
                }
            });

            // Scroll focused element into view
            const el = state.focusMap[state.focusIndex];
            if (el && el.scrollIntoView) {
                el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
            }
        },

        _moveFocus(dx, dy) {
            if (state.focusMap.length === 0) return;
            if (dx !== 0) {
                // Horizontal: move by 1 in the focus map
                state.focusIndex = Math.max(0, Math.min(state.focusMap.length - 1, state.focusIndex + dx));
            }
            if (dy !== 0) {
                // Vertical: heuristic — jump by a row (estimate 4-6 items per row)
                const step = dy > 0 ? 6 : -6;
                state.focusIndex = Math.max(0, Math.min(state.focusMap.length - 1, state.focusIndex + step));
            }
            this._updateFocus();
        },

        _activateFocused() {
            const el = state.focusMap[state.focusIndex];
            if (!el) return;
            if (el.tagName === 'BUTTON' || el.tagName === 'INPUT' || el.tagName === 'SELECT') {
                el.click();
            } else if (el.classList.contains('album-card')) {
                this.showAlbum(el.dataset.albumId, el.dataset.serverType);
            } else if (el.classList.contains('track-row')) {
                const idx = parseInt(el.dataset.queueIndex);
                App.playTrackAt(idx);
            } else if (el.classList.contains('server-card')) {
                App.activateServer(el.dataset.serverId);
            }
        },

        // ── Library rendering ───────────────────────────────────

        async renderLibrary() {
            if (!state.activeServer || !state.subsonicClient) {
                this._renderEmpty('recentlyAddedItems', 'Connect a server in Settings');
                this._renderEmpty('recentlyPlayedItems', '');
                this._renderEmpty('allAlbumsItems', '');
                return;
            }

            // Recently added
            this._renderLoading('recentlyAddedItems');
            try {
                const albums = await state.subsonicClient.getAlbumList('newest', 20);
                this._renderAlbums('recentlyAddedItems', albums);
            } catch (e) {
                this._renderEmpty('recentlyAddedItems', 'Failed to load');
            }

            // Recently played
            try {
                const albums = await state.subsonicClient.getAlbumList('recent', 20);
                this._renderAlbums('recentlyPlayedItems', albums);
            } catch (e) {
                this._renderEmpty('recentlyPlayedItems', '');
            }

            // All albums (first page)
            try {
                const albums = await state.subsonicClient.getAlbumList('alphabeticalByName', 50);
                this._renderAlbums('allAlbumsItems', albums);
            } catch (e) {
                this._renderEmpty('allAlbumsItems', 'Failed to load');
            }

            this._buildFocusMap();
        },

        _renderAlbums(containerId, albums) {
            const container = document.getElementById(containerId);
            if (!albums || albums.length === 0) {
                this._renderEmpty(containerId, 'No albums');
                return;
            }
            container.innerHTML = albums.map(a => `
                <div class="album-card spotlight" data-album-id="${a.id}" data-server-type="subsonic">
                    <img src="${state.subsonicClient.coverUrl(a.coverArt, 300) || ''}"
                         alt="${a.name}" loading="lazy"
                         onerror="this.style.visibility='hidden'">
                    <div class="album-name">${a.name}</div>
                    <div class="album-artist">${a.artist || ''}</div>
                </div>
            `).join('');
        },

        async showAlbum(albumId, serverType) {
            if (serverType !== 'subsonic') return;
            try {
                const album = await state.subsonicClient.getAlbum(albumId);
                state.albumDetail = album;

                // Replace library view content with album detail
                const view = document.getElementById('view-library');
                view.innerHTML = `
                    <div class="album-detail">
                        <div class="album-header">
                            <img src="${state.subsonicClient.coverUrl(album.coverArt, 400) || ''}"
                                 alt="${album.name}" class="album-detail-art"
                                 onerror="this.style.visibility='hidden'">
                            <div class="album-detail-info">
                                <h1 class="album-detail-title">${album.name}</h1>
                                <h2 class="album-detail-artist">${album.artist}</h2>
                                <button class="play-all-btn spotlight" id="playAllBtn" data-focused="true">Play All</button>
                            </div>
                        </div>
                        <div class="track-list" id="trackList">
                            ${album.tracks.map((t, i) => `
                                <div class="track-row spotlight" data-queue-index="${i}">
                                    <span class="track-number">${t.track || (i + 1)}</span>
                                    <span class="track-title">${t.title}</span>
                                    <span class="track-duration">${this._formatTime(t.duration)}</span>
                                </div>
                            `).join('')}
                        </div>
                    </div>
                `;

                // Wire Play All button
                document.getElementById('playAllBtn').addEventListener('click', () => {
                    App.playQueue(album.tracks, 0);
                });

                this._buildFocusMap();
            } catch (e) {
                console.error('Failed to load album:', e);
            }
        },

        // ── Search ──────────────────────────────────────────────

        _setupSearch() {
            const input = document.getElementById('searchInput');
            let debounceTimer;
            input.addEventListener('input', () => {
                clearTimeout(debounceTimer);
                debounceTimer = setTimeout(() => this._doSearch(input.value), 400);
            });
        },

        async _doSearch(query) {
            const resultsEl = document.getElementById('searchResults');
            if (!query || query.length < 2) {
                resultsEl.innerHTML = '';
                return;
            }
            if (!state.subsonicClient) {
                resultsEl.innerHTML = '<p class="empty-state">Connect a server first</p>';
                return;
            }
            try {
                const results = await state.subsonicClient.search(query, 30);
                let html = '';
                if (results.artists?.length) {
                    html += `<div class="search-section"><div class="search-section-title">Artists</div>`;
                    html += results.artists.map(a => `
                        <div class="track-row spotlight" data-artist-id="${a.id}">
                            <span class="track-title">${a.name}</span>
                            <span class="track-duration">${a.albumCount || 0} albums</span>
                        </div>`).join('');
                    html += `</div>`;
                }
                if (results.albums?.length) {
                    html += `<div class="search-section"><div class="search-section-title">Albums</div>`;
                    html += results.albums.map(a => `
                        <div class="track-row spotlight" data-album-id="${a.id}" data-server-type="subsonic">
                            <span class="track-title">${a.name}</span>
                            <span class="track-duration">${a.artist || ''}</span>
                        </div>`).join('');
                    html += `</div>`;
                }
                if (results.songs?.length) {
                    html += `<div class="search-section"><div class="search-section-title">Songs</div>`;
                    html += results.songs.map((s, i) => `
                        <div class="track-row spotlight" data-song-id="${s.id}">
                            <span class="track-number">${i + 1}</span>
                            <span class="track-title">${s.title}</span>
                            <span class="track-duration">${this._formatTime(s.duration)}</span>
                        </div>`).join('');
                    html += `</div>`;
                }
                resultsEl.innerHTML = html || '<p class="empty-state">No results</p>';
                this._buildFocusMap();
            } catch (e) {
                resultsEl.innerHTML = '<p class="empty-state">Search failed</p>';
            }
        },

        // ── Now Playing bar ────────────────────────────────────

        _setupNowPlaying() {
            document.getElementById('npPlayBtn').addEventListener('click', () => {
                if (state.isPlaying) state.audio.pause();
                else if (state.currentTrack) state.audio.resume();
            });
            document.getElementById('npNextBtn').addEventListener('click', () => App.nextTrack());
            document.getElementById('npPrevBtn').addEventListener('click', () => App.prevTrack());
        },

        updateNowPlaying() {
            const np = document.getElementById('nowPlaying');
            if (!state.currentTrack) {
                np.classList.add('hidden');
                return;
            }
            np.classList.remove('hidden');

            const track = state.currentTrack;
            document.getElementById('npTitle').textContent = track.title || 'Unknown';
            document.getElementById('npArtist').textContent = track.artist || '';
            document.getElementById('npArt').src = state.subsonicClient?.coverUrl(track.coverArt, 300) || '';

            const playBtn = document.getElementById('npPlayBtn');
            playBtn.textContent = state.isPlaying ? '⏸' : '▶';

            // Progress
            const pos = state.positionMs || 0;
            const dur = state.durationMs || (track.duration * 1000) || 0;
            document.getElementById('npCurrentTime').textContent = this._formatTime(pos / 1000);
            document.getElementById('npDuration').textContent = this._formatTime(dur / 1000);
            const pct = dur > 0 ? (pos / dur * 100) : 0;
            document.getElementById('npSeekbarFill').style.width = pct + '%';
        },

        // ── Hue view ────────────────────────────────────────────

        _setupHue() {
            // Pre-fill from stored config
            const cfg = Storage.get('hueConfig', {});
            document.getElementById('hueBridgeIp').value = cfg.bridgeIp || '';
            document.getElementById('hueUsername').value = cfg.username || '';
            document.getElementById('hueGroupId').value = cfg.groupId || '';

            document.getElementById('hueConnectBtn').addEventListener('click', async () => {
                const ip = document.getElementById('hueBridgeIp').value;
                const user = document.getElementById('hueUsername').value;
                const gid = document.getElementById('hueGroupId').value;
                try {
                    await state.hueService.connect(ip, user, gid);
                    document.getElementById('hueBridgeStatus').textContent = 'Connected';
                    const count = await state.hueService.getLightCount();
                    document.getElementById('hueLightCount').textContent = count;
                } catch (e) {
                    document.getElementById('hueBridgeStatus').textContent = 'Failed: ' + e.message;
                }
            });

            document.getElementById('hueToggleBtn').addEventListener('click', async () => {
                if (state.hueService.syncing) {
                    await state.hueService.stopSync();
                    document.getElementById('hueSyncStatus').textContent = 'Off';
                } else {
                    try {
                        await state.hueService.startSync();
                        document.getElementById('hueSyncStatus').textContent = 'On';
                    } catch (e) {
                        document.getElementById('hueSyncStatus').textContent = 'Error';
                    }
                }
            });
        },

        // ── Settings view ───────────────────────────────────────

        _setupSettings() {
            this._renderServerList();

            document.getElementById('addServerBtn').addEventListener('click', () => {
                document.getElementById('serverForm').classList.add('visible');
                document.getElementById('serverFormTitle').textContent = 'Add Server';
            });

            document.getElementById('cancelServerBtn').addEventListener('click', () => {
                document.getElementById('serverForm').classList.remove('visible');
            });

            document.getElementById('saveServerBtn').addEventListener('click', () => {
                const server = {
                    id: Date.now().toString(),
                    kind: document.getElementById('serverType').value,
                    name: document.getElementById('serverName').value,
                    url: document.getElementById('serverUrl').value,
                    username: document.getElementById('serverUsername').value,
                    password: document.getElementById('serverPassword').value,
                };
                state.servers.push(server);
                Storage.set('servers', state.servers);
                document.getElementById('serverForm').classList.remove('visible');
                this._clearForm();
                this._renderServerList();
            });

            document.getElementById('removeServerBtn').addEventListener('click', () => {
                if (state.servers.length === 0) return;
                state.servers.pop();
                Storage.set('servers', state.servers);
                this._renderServerList();
            });
        },

        _renderServerList() {
            const list = document.getElementById('serverList');
            state.servers = Storage.get('servers', []);
            if (state.servers.length === 0) {
                list.innerHTML = '<p class="empty-state">No servers configured. Add one to get started.</p>';
                return;
            }
            list.innerHTML = state.servers.map(s => `
                <div class="server-card spotlight ${state.activeServer?.id === s.id ? 'active' : ''}"
                     data-server-id="${s.id}">
                    <div class="server-card-info">
                        <div class="server-card-name">${s.name || s.kind}</div>
                        <div class="server-card-url">${s.url}</div>
                    </div>
                </div>
            `).join('');

            // Wire server cards
            list.querySelectorAll('.server-card').forEach(card => {
                card.addEventListener('click', () => {
                    App.activateServer(card.dataset.serverId);
                });
            });
        },

        _clearForm() {
            ['serverName', 'serverUrl', 'serverUsername', 'serverPassword'].forEach(id => {
                document.getElementById(id).value = '';
            });
        },

        updateServerIndicator() {
            const el = document.getElementById('serverIndicator');
            if (state.activeServer) {
                el.textContent = state.activeServer.name || state.activeServer.kind;
                el.classList.add('connected');
            } else {
                el.textContent = 'Not connected';
                el.classList.remove('connected');
            }
        },

        // ── Helpers ────────────────────────────────────────────

        _formatTime(seconds) {
            if (!seconds || isNaN(seconds)) return '0:00';
            const m = Math.floor(seconds / 60);
            const s = Math.floor(seconds % 60);
            return `${m}:${s.toString().padStart(2, '0')}`;
        },

        _renderEmpty(containerId, message) {
            const el = document.getElementById(containerId);
            if (el) el.innerHTML = `<p class="empty-state">${message}</p>`;
        },

        _renderLoading(containerId) {
            const el = document.getElementById(containerId);
            if (el) el.innerHTML = '<p class="loading">Loading...</p>';
        },
    };

    // ════════════════════════════════════════════════════════════
    // App Controller (orchestration, lifecycle, playback)
    // ════════════════════════════════════════════════════════════

    const App = {
        init() {
            // Create audio player (single <audio> element)
            state.audio = new AudioPlayer();

            // Create Hue service
            state.hueService = new HueService();

            // Load saved servers
            state.servers = Storage.get('servers', []);

            // Auto-connect to first server if available
            if (state.servers.length > 0) {
                this.activateServer(state.servers[0].id);
            }

            // Init UI
            UI.init();

            // App lifecycle (webOS)
            this._setupLifecycle();

            // If no servers, switch to settings tab
            if (state.servers.length === 0) {
                UI.switchTab('settings');
            } else {
                UI.renderLibrary();
            }

            console.log('CAMusic webOS app initialized');
        },

        activateServer(serverId) {
            const server = state.servers.find(s => s.id === serverId);
            if (!server) return;

            // Disconnect previous
            if (state.maClient) {
                state.maClient.disconnect();
                state.maClient = null;
            }

            state.activeServer = server;

            if (server.kind === 'music_assistant') {
                state.maClient = new MaClient(server.url, server.username, server.password);
                state.maClient.connect().then(() => {
                    UI.updateServerIndicator();
                    this._setupMaEvents();
                }).catch(e => {
                    console.error('MA connect failed:', e);
                    UI.updateServerIndicator();
                });
            } else {
                // Navidrome, Subsonic, Jellyfin (via Subsonic API)
                state.subsonicClient = new SubsonicClient(server.url, server.username, server.password);
                state.subsonicClient.ping().then(ok => {
                    UI.updateServerIndicator();
                    if (ok) {
                        UI.renderLibrary();
                    }
                }).catch(() => {
                    UI.updateServerIndicator();
                });
            }

            UI._renderServerList();
            UI.switchTab('library');
        },

        _setupMaEvents() {
            if (!state.maClient) return;
            state.maClient.onEvent((msg) => {
                // Handle MA state updates
                if (msg.method === 'player_state_changed') {
                    const p = msg.params;
                    if (p.state === 'playing') {
                        state.isPlaying = true;
                    } else {
                        state.isPlaying = false;
                    }
                    UI.updateNowPlaying();
                }
            });
        },

        // ── Playback ───────────────────────────────────────────

        playQueue(tracks, startIndex = 0) {
            state.queue = tracks;
            state.queueIndex = startIndex;
            this._playCurrent();
        },

        playTrackAt(index) {
            state.queueIndex = index;
            this._playCurrent();
        },

        _playCurrent() {
            if (state.queueIndex < 0 || state.queueIndex >= state.queue.length) return;
            const track = state.queue[state.queueIndex];
            state.currentTrack = track;

            if (state.subsonicClient) {
                const url = state.subsonicClient.streamUrl(track.id);
                state.audio.play(url);
            }

            UI.updateNowPlaying();
        },

        nextTrack() {
            if (state.queueIndex < state.queue.length - 1) {
                state.queueIndex++;
                this._playCurrent();
            }
        },

        prevTrack() {
            if (state.queueIndex > 0) {
                state.queueIndex--;
                this._playCurrent();
            } else if (state.audio) {
                state.audio.seek(0);
            }
        },

        // ── App lifecycle (webOS) ──────────────────────────────

        _setupLifecycle() {
            // visibilitychange — MUST pause audio when app is suspended
            document.addEventListener('visibilitychange', () => {
                if (document.hidden) {
                    // App going to background — pause audio and stop Hue sync
                    if (state.audio && state.audio.isPlaying) {
                        state.audio.pause();
                    }
                    if (state.hueService && state.hueService.syncing) {
                        state.hueService.stopSync().catch(() => {});
                    }
                    console.log('App suspended — audio paused');
                } else {
                    // App returning to foreground
                    console.log('App resumed');
                }
            });

            // webOS launch event
            document.addEventListener('webOSLaunch', () => {
                console.log('CAMusic launched');
            });

            // webOS relaunch event
            document.addEventListener('webOSRelaunch', () => {
                console.log('CAMusic relaunched');
            });

            // Beforeunload — cleanup
            window.addEventListener('beforeunload', () => {
                if (state.audio) state.audio.pause();
                if (state.hueService) state.hueService.disconnect().catch(() => {});
                if (state.maClient) state.maClient.disconnect();
            });
        },
    };

    // ── Bootstrap ──────────────────────────────────────────────
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => App.init());
    } else {
        App.init();
    }

})();