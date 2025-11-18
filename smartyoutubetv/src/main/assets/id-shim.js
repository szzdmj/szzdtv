// id-shim.js (enhanced)
// Purpose: intercept any jsonpCallback_* callbacks, script/iframe insertions and XHRs to log to Android (CrashLogger).
(function() {
    function safeStringify(obj, limit) {
        try { 
            var s = JSON.stringify(obj);
            if (limit && s.length > limit) return s.substr(0, limit) + '...[' + s.length + ']';
            return s;
        } catch (e) {
            try { return String(obj); } catch (ee) { return "[unserializable]"; }
        }
    }

    function androidLog(msg) {
        try {
            if (window.Android && typeof window.Android.log === 'function') {
                window.Android.log(String(msg));
            } else {
                console.log(msg);
            }
        } catch (e) {
            try { console.log("id-shim log failed", e); } catch(ignore){}
        }
    }

    androidLog("id-shim.js loaded");

    // Wrap any existing jsonpCallback_* functions and watch for new ones.
    var jsonpPrefix = 'jsonpCallback_';
    var wrapped = {};

    function wrapCallback(name) {
        try {
            if (!name || wrapped[name]) return;
            var orig = window[name];
            if (typeof orig !== 'function') return;
            wrapped[name] = true;
            window[name] = function() {
                try {
                    var args = Array.prototype.slice.call(arguments);
                    var preview = safeStringify(args[0], 2000);
                    androidLog("JSONP " + name + " called, data(len)=" + (preview ? preview.length : 0) + ": " + preview);
                } catch (e) {
                    androidLog("JSONP " + name + " called (logging failed): " + e);
                }
                try { return orig.apply(this, arguments); } catch (e) { 
                    androidLog("Original JSONP " + name + " threw: " + e); 
                }
            };
            androidLog("Wrapped existing callback: " + name);
        } catch (e) {
            androidLog("wrapCallback error for " + name + ": " + e);
        }
    }

    // Initial scan: wrap functions already on window
    try {
        Object.getOwnPropertyNames(window).forEach(function(k) {
            if (k.indexOf(jsonpPrefix) === 0 && typeof window[k] === 'function') wrapCallback(k);
        });
    } catch (e) {
        // some environments restrict enumerate; ignore
    }

    // Periodically scan for new callbacks (covers ones defined later)
    var scanInterval = setInterval(function() {
        try {
            Object.getOwnPropertyNames(window).forEach(function(k) {
                if (k.indexOf(jsonpPrefix) === 0 && typeof window[k] === 'function') wrapCallback(k);
            });
        } catch (e) {}
    }, 500);

    // Intercept document.createElement to detect iframe/script creation and log src
    (function() {
        var origCreate = Document.prototype.createElement;
        Document.prototype.createElement = function(tagName) {
            var el = origCreate.call(this, tagName);
            try {
                if (String(tagName).toLowerCase() === 'iframe') {
                    var origSet = el.setAttribute;
                    el.setAttribute = function(name, value) {
                        try {
                            if (name === 'src') androidLog("iframe src set: " + String(value).substr(0,300));
                        } catch (e){}
                        return origSet.apply(this, arguments);
                    };
                }
                if (String(tagName).toLowerCase() === 'script') {
                    var origSet = el.setAttribute;
                    el.setAttribute = function(name, value) {
                        try {
                            if (name === 'src') androidLog("script src set: " + String(value).substr(0,300));
                        } catch (e){}
                        return origSet.apply(this, arguments);
                    };
                    // hook onload/onerror to log execution
                    el.addEventListener('load', function() { androidLog("script loaded: " + (el.src || "(inline)").toString().substr(0,300)); });
                    el.addEventListener('error', function() { androidLog("script load error: " + (el.src || "(inline)").toString().substr(0,300)); });
                }
            } catch (e){}
            return el;
        };
    })();

    // Intercept appendChild/insertBefore to start observing attributes (for dynamically set src)
    (function() {
        var origAppend = Node.prototype.appendChild;
        Node.prototype.appendChild = function(child) {
            try { 
                if (child && child.tagName && child.tagName.toLowerCase() === 'iframe') {
                    try { androidLog("iframe appended with src: " + (child.src || child.getAttribute('src') || "(none)").toString().substr(0,300)); } catch(e){}
                }
                if (child && child.tagName && child.tagName.toLowerCase() === 'script') {
                    try { androidLog("script appended with src: " + (child.src || child.getAttribute('src') || "(inline)").toString().substr(0,300)); } catch(e){}
                }
            } catch(e){}
            return origAppend.apply(this, arguments);
        };
        var origInsert = Node.prototype.insertBefore;
        Node.prototype.insertBefore = function(node, ref) {
            try {
                if (node && node.tagName && node.tagName.toLowerCase() === 'iframe') {
                    try { androidLog("iframe inserted with src: " + (node.src || node.getAttribute('src') || "(none)").toString().substr(0,300)); } catch(e){}
                }
            } catch(e){}
            return origInsert.apply(this, arguments);
        };
    })();

    // Wrap JSON.parse to log if page parses data.json (optional)
    (function() {
        var origParse = JSON.parse;
        JSON.parse = function(text) {
            try {
                if (typeof text === 'string' && text.indexOf('"someIdentifierInData"') !== -1) {
                    androidLog("JSON.parse called for data snippet: " + text.substr(0,300));
                }
            } catch(e){}
            return origParse.apply(this, arguments);
        };
    })();

    // Intercept XHR open/send to log data.json or jsonp script requests
    (function() {
        var origOpen = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(method, url) {
            try { this._id_shim_url = url; } catch(e){}
            return origOpen.apply(this, arguments);
        };
        var origSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.send = function() {
            try {
                if (this._id_shim_url && (this._id_shim_url.indexOf('data.json') !== -1 || this._id_shim_url.indexOf('jsonp') !== -1)) {
                    androidLog("XHR requesting: " + String(this._id_shim_url).substr(0,400));
                }
            } catch(e){}
            return origSend.apply(this, arguments);
        };
    })();

    // Also observe dynamic script tags inserted via document.write by wrapping document.write
    (function() {
        var origWrite = document.write;
        document.write = function() {
            try {
                var args = Array.prototype.slice.call(arguments).join('');
                if (args && args.indexOf('jsonpCallback_') !== -1) {
                    androidLog("document.write invoked with jsonp content: " + args.substr(0,400));
                }
            } catch(e){}
            return origWrite.apply(this, arguments);
        };
    })();

    // Final safety: after some time stop scanning to reduce overhead
    setTimeout(function() { clearInterval(scanInterval); androidLog("id-shim scanning stopped"); }, 30 * 1000);
})();
