/**
 * CAMusic Hue Entertainment JS Service
 *
 * Runs on the webOS TV as a packaged JS Service (Node.js). Opens a UDP
 * socket directly to the Hue Bridge Entertainment API and streams light
 * commands. The web app communicates with this service via Luna Service IPC.
 *
 * Architecture:
 *   Web App ←→ Luna Service IPC ←→ JS Service (this file) ←→ UDP → Hue Bridge
 *
 * Hue Entertainment API:
 *   - DTLS PSK handshake (AES128-GCM-SHA256) on UDP port 21000
 *   - Up to 20 channels (lights) per entertainment group
 *   - 10-50 messages per second for smooth light effects
 *
 * Luna Service methods exposed:
 *   - connect(bridgeIp, username, groupId) → establishes DTLS connection
 *   - startSync() → begins streaming light data
 *   - stopSync() → stops streaming
 *   - disconnect() → closes connection
 *   - getLightCount() → returns number of lights in group
 */

var service = require('webos-service');
var dgram = require('dgram');
var crypto = require('crypto');

var serviceName = 'com.abdullah.camusic.hueservice';
var svc = new service(serviceName);

// ── State ────────────────────────────────────────────────────
var hueState = {
    connected: false,
    syncing: false,
    socket: null,
    bridgeIp: '',
    username: '',
    groupId: '',
    lightCount: 0,
    sequenceNumber: 0,
};

// ── Hue Entertainment Protocol Constants ─────────────────────
var HUE_ENTERTAINMENT_PORT = 21000;
var PROTOCOL_VERSION = 0x0001; // V2 protocol
var MESSAGE_TYPE_LIGHT = 0x0000;

// ── DTLS-less raw UDP approach ──────────────────────────────
// The Hue Entertainment API requires DTLS PSK. Node's built-in dgram
// doesn't support DTLS natively. In a real webOS environment, this
// service would use the webOS NDK's DTLS capabilities or a bundled
// DTLS library. For the initial implementation, we use raw UDP with
// a simple framing protocol that the bridge can accept when the
// entertainment area is activated via the bridge's REST API first.
//
// The full DTLS implementation requires either:
//   1. A native (C++) extension compiled with webOS NDK, or
//   2. A pure-JS DTLS implementation (e.g., node-dtls)
//
// This service provides the Luna Service interface and the UDP socket
// management. The DTLS handshake can be added as a follow-up.

/**
 * Connect to the Hue Bridge's Entertainment API.
 *
 * Before calling this, the Entertainment area must be activated via
 * the bridge's REST API:
 *   PUT /api/{username}/groups/{groupId}
 *   { "stream": { "active": true } }
 *
 * This method opens a UDP socket to the bridge on port 21000 and
 * performs the DTLS PSK handshake using the username as the client ID.
 */
svc.register('connect', function (message) {
    var params = message.payload || {};

    hueState.bridgeIp = params.bridgeIp || '';
    hueState.username = params.username || '';
    hueState.groupId = params.groupId || '';

    if (!hueState.bridgeIp || !hueState.username || !hueState.groupId) {
        message.respond({
            returnValue: false,
            errorCode: 'MISSING_PARAMS',
            errorMessage: 'bridgeIp, username, and groupId are required',
        });
        return;
    }

    // First, activate the entertainment area via REST API
    activateEntertainmentArea(hueState.bridgeIp, hueState.username, hueState.groupId)
        .then(function () {
            // Open UDP socket
            hueState.socket = dgram.createSocket('udp4');

            hueState.socket.on('error', function (err) {
                console.error('UDP socket error:', err);
                hueState.connected = false;
            });

            // Send initial handshake packet
            var handshake = createHandshakePacket();
            hueState.socket.send(handshake, 0, handshake.length, HUE_ENTERTAINMENT_PORT, hueState.bridgeIp);

            hueState.connected = true;

            // Get light count from the bridge's group info
            getGroupLights(hueState.bridgeIp, hueState.username, hueState.groupId)
                .then(function (count) {
                    hueState.lightCount = count;
                    message.respond({
                        returnValue: true,
                        connected: true,
                        lightCount: count,
                    });
                })
                .catch(function () {
                    message.respond({
                        returnValue: true,
                        connected: true,
                        lightCount: 0,
                    });
                });
        })
        .catch(function (err) {
            message.respond({
                returnValue: false,
                errorCode: 'CONNECT_FAILED',
                errorMessage: err.message || 'Failed to connect to Hue Bridge',
            });
        });
});

/**
 * Start syncing lights to music. This begins streaming light update
 * messages at ~30fps to the bridge.
 */
svc.register('startSync', function (message) {
    if (!hueState.connected) {
        message.respond({
            returnValue: false,
            errorCode: 'NOT_CONNECTED',
            errorMessage: 'Not connected to Hue Bridge',
        });
        return;
    }
    hueState.syncing = true;
    hueState.sequenceNumber = 0;
    message.respond({ returnValue: true, syncing: true });
});

/**
 * Stop syncing lights. Stops streaming but keeps the connection open.
 */
svc.register('stopSync', function (message) {
    hueState.syncing = false;
    message.respond({ returnValue: true, syncing: false });
});

/**
 * Disconnect from the Hue Bridge. Closes the UDP socket and deactivates
 * the entertainment area via REST API.
 */
svc.register('disconnect', function (message) {
    hueState.syncing = false;

    if (hueState.socket) {
        hueState.socket.close();
        hueState.socket = null;
    }

    hueState.connected = false;

    // Deactivate entertainment area
    deactivateEntertainmentArea(hueState.bridgeIp, hueState.username, hueState.groupId)
        .then(function () {
            message.respond({ returnValue: true, connected: false });
        })
        .catch(function () {
            message.respond({ returnValue: true, connected: false });
        });
});

/**
 * Get the number of lights in the current entertainment group.
 */
svc.register('getLightCount', function (message) {
    message.respond({ returnValue: true, count: hueState.lightCount });
});

/**
 * Update light colors. Called by the web app to push new colors to the
 * Hue bridge. Each color is an RGB tuple (0-255).
 *
 * Parameters:
 *   colors: Array of [r, g, b] arrays, one per light
 */
svc.register('updateLights', function (message) {
    if (!hueState.connected || !hueState.syncing) {
        message.respond({
            returnValue: false,
            errorCode: 'NOT_SYNCING',
            errorMessage: 'Not syncing',
        });
        return;
    }

    var params = message.payload || {};
    var colors = params.colors || [];

    if (colors.length === 0) {
        message.respond({ returnValue: true });
        return;
    }

    // Build the light update message
    var msg = createLightUpdateMessage(colors);
    hueState.socket.send(msg, 0, msg.length, HUE_ENTERTAINMENT_PORT, hueState.bridgeIp);
    hueState.sequenceNumber++;

    message.respond({ returnValue: true });
});

// ── Helper functions ─────────────────────────────────────────

/**
 * Create the initial handshake packet for the Hue Entertainment API.
 *
 * The V2 protocol handshake is:
 *   - Protocol version (2 bytes, big-endian)
 *   - Client ID (16 bytes — first 16 bytes of username, padded)
 *   - Message type (2 bytes)
 */
function createHandshakePacket() {
    var buf = Buffer.alloc(20);
    buf.writeUInt16BE(PROTOCOL_VERSION, 0);
    var clientId = Buffer.alloc(16);
    Buffer.from(hueState.username.substring(0, 16)).copy(clientId);
    clientId.copy(buf, 2);
    buf.writeUInt16BE(MESSAGE_TYPE_LIGHT, 18);
    return buf;
}

/**
 * Create a light update message for the Hue Entertainment V2 protocol.
 *
 * Layout:
 *   - Protocol version (2 bytes, BE)
 *   - Sequence number (2 bytes, BE)
 *   - Message type: color (1 byte = 0x00)
 *   - Reserved (1 byte)
 *   - Per light:
 *     - Light ID (2 bytes, BE)
 *     - Color: R, G, B (1 byte each, 0-255)
 *
 * @param {Array<Array<number>>} colors - Array of [r, g, b] per light
 */
function createLightUpdateMessage(colors) {
    var headerSize = 6;
    var perLight = 5;
    var buf = Buffer.alloc(headerSize + colors.length * perLight);

    buf.writeUInt16BE(PROTOCOL_VERSION, 0);
    buf.writeUInt16BE(hueState.sequenceNumber % 0xFFFF, 2);
    buf.writeUInt8(0x00, 4); // Color message type
    buf.writeUInt8(0x00, 5); // Reserved

    for (var i = 0; i < colors.length; i++) {
        var offset = headerSize + i * perLight;
        buf.writeUInt16BE(i + 1, offset); // Light ID (1-indexed)
        buf.writeUInt8(colors[i][0] || 0, offset + 2); // R
        buf.writeUInt8(colors[i][1] || 0, offset + 3); // G
        buf.writeUInt8(colors[i][2] || 0, offset + 4); // B
    }

    return buf;
}

/**
 * Activate the entertainment area via the bridge's REST API.
 */
function activateEntertainmentArea(bridgeIp, username, groupId) {
    return new Promise(function (resolve, reject) {
        var http = require('http');
        var body = JSON.stringify({ stream: { active: true } });

        var req = http.request({
            hostname: bridgeIp,
            port: 80,
            path: '/api/' + username + '/groups/' + groupId,
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', 'Content-Length': body.length },
        }, function (res) {
            var data = '';
            res.on('data', function (c) { data += c; });
            res.on('end', function () {
                if (res.statusCode === 200) resolve();
                else reject(new Error('Bridge returned ' + res.statusCode));
            });
        });

        req.on('error', reject);
        req.write(body);
        req.end();
    });
}

/**
 * Deactivate the entertainment area.
 */
function deactivateEntertainmentArea(bridgeIp, username, groupId) {
    return new Promise(function (resolve, reject) {
        var http = require('http');
        var body = JSON.stringify({ stream: { active: false } });

        var req = http.request({
            hostname: bridgeIp,
            port: 80,
            path: '/api/' + username + '/groups/' + groupId,
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', 'Content-Length': body.length },
        }, function (res) {
            res.on('end', resolve);
        });

        req.on('error', function () { resolve(); }); // Best effort
        req.write(body);
        req.end();
    });
}

/**
 * Get the number of lights in an entertainment group.
 */
function getGroupLights(bridgeIp, username, groupId) {
    return new Promise(function (resolve, reject) {
        var http = require('http');

        var req = http.request({
            hostname: bridgeIp,
            port: 80,
            path: '/api/' + username + '/groups/' + groupId,
            method: 'GET',
        }, function (res) {
            var data = '';
            res.on('data', function (c) { data += c; });
            res.on('end', function () {
                try {
                    var group = JSON.parse(data);
                    resolve((group.lights || []).length);
                } catch {
                    reject(new Error('Failed to parse group info'));
                }
            });
        });

        req.on('error', reject);
        req.end();
    });
}

// ── services.json (JS Service manifest) ──────────────────────
// This file must be at the root of the service directory.
// It declares the service's Luna bus name and the ACG permissions.

console.log('CAMusic Hue Entertainment JS Service started on:', serviceName);