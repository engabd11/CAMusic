/**
 * webOSTV.js — Minimal stub for development/testing.
 *
 * On a real webOS TV, the platform provides this library. This stub
 * provides the methods used by CAMusic so the app can run in a
 * standard browser during development.
 */

(function (window) {
    var webOS = {};

    webOS.platform = { tv: true };

    webOS.libVersion = '1.2.0';

    webOS.deviceInfo = function (cb) {
        var info = {
            modelName: 'WEBOS-SIMULATOR',
            screenWidth: 1920,
            screenHeight: 1080,
            sdkVersion: '26',
        };
        if (cb) cb(info);
        return info;
    };

    webOS.fetchAppId = function () {
        return 'com.abdullah.camusic';
    };

    webOS.fetchAppInfo = function (cb) {
        var info = {
            id: 'com.abdullah.camusic',
            title: 'CAMusic',
            version: '0.0.1',
        };
        if (cb) cb(info);
        return info;
    };

    webOS.fetchAppRootPath = function () {
        return window.location.origin + window.location.pathname.replace(/\/[^/]*$/, '');
    };

    webOS.platformBack = function () {
        if (window.close) window.close();
    };

    webOS.keyboard = {
        isShowing: function () { return false; },
    };

    webOS.service = {
        request: function (uri, options) {
            console.log('[webOSTV.js stub] service.request:', uri, options.method);
            // Simulate failure in non-webOS environment
            if (options.onFailure) {
                options.onFailure({ errorCode: 'NOT_WEBOS', errorText: 'Running in browser' });
            }
        },
    };

    window.webOS = webOS;
})(window);