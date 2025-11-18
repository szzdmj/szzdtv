// id-shim.js
// Purpose: intercept JSONP callbacks and iframe creation to log data to Android (CrashLogger).
(function() {
    function safeStringify(obj) {
        try { return JSON.stringify(obj); } catch (e) {
            try { return String(obj); } catch (ee) { return "[unserializable]"; }
        }
    }

    // Intercept a specific JSONP callback name if it exists or will be called later.
    var cbName = 'jsonpCallback_NPWou'; // adjust if callback differs
    try {
        var originalCb = window[cbName];
    } catch (err) {
        var originalCb = null;
    }

    window[cbName] = function(data) {
        try {
            // Log to Android via Android.log (the app adds this bridge)
            if (window.Android && typeof window.Android.log === 'function') {
                var s = safeStringify(data);
                // limit size to avoid huge logs, but keep enough for inspection
                window.Android.log("JSONP " + cbName + " called, data(len)=" + (s ? s.length : 0) + ": " + (s.length > 2000 ? s.substr(0,2000) + '...': s));
            } else {
                console.log("JSONP " + cbName + " called, data:", data);
            }
        } catch (e) {
            try { console.warn("id-shim.jsonp log failed", e); } catch(ignore){}
        }
        // Call the original callback so page logic still runs
        try { if (typeof originalCb === 'function') originalCb.apply(this, arguments); } catch(e){ console.warn("originalCb failed", e); }
    };

    // Intercept iframe creation to log src attribute when appended
    (function() {
        var origCreateElement = document.createElement;
        document.createElement = function(tagName) {
            var el = origCreateElement.apply(this, arguments);
            try {
                if (String(tagName).toLowerCase() === 'iframe') {
                    // When src is set or appended, we log later in connectedCallback-like manner
                    var origSetAttribute = el.setAttribute;
                    el.setAttribute = function(name, value) {
                        try {
                            if (name === 'src' && window.Android && typeof window.Android.log === 'function') {
                                window.Android.log("iframe src set: " + String(value).substr(0,200));
                            }
                        } catch (e) {}
                        return origSetAttribute.apply(this, arguments);
                    };
                    // also observe later src changes
                    var observer = new MutationObserver(function(muts) {
                        muts.forEach(function(m) {
                            if (m.attributeName === 'src') {
                                try {
                                    var v = el.getAttribute('src');
                                    if (window.Android && typeof window.Android.log === 'function') {
                                        window.Android.log("iframe src mutated: " + String(v).substr(0,200));
                                    }
                                } catch (e){}
                            }
                        });
                    });
                    el._id_shim_observe = function() { observer.observe(el, { attributes: true }); };
                    el._id_shim_disconnect = function() { observer.disconnect(); };
                    // initiate observation when element is inserted
                    var origAppend = Node.prototype.appendChild;
                    if (!Node.prototype._id_shim_append_wrapped) {
                        Node.prototype.appendChild = function(child) {
                            try { if (child && child._id_shim_observe) child._id_shim_observe(); } catch(e){}
                            return origAppend.apply(this, arguments);
                        };
                        Node.prototype._id_shim_append_wrapped = true;
                    }
                }
            } catch (ee){}
            return el;
        };
    })();

    // Also override window.fetch or XMLHttpRequest open/send to log data.json fetches if needed
    (function() {
        var origOpen = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function() {
            try {
                this._id_shim_url = arguments[1];
            } catch (e){}
            return origOpen.apply(this, arguments);
        };
        var origSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.send = function() {
            try {
                if (this._id_shim_url && this._id_shim_url.indexOf('data.json') !== -1) {
                    if (window.Android && typeof window.Android.log === 'function') {
                        window.Android.log("XHR requesting: " + this._id_shim_url);
                    } else {
                        console.log("XHR requesting: " + this._id_shim_url);
                    }
                }
            } catch (e){}
            return origSend.apply(this, arguments);
        };
    })();

    // Log that shim loaded
    try { if (window.Android && typeof window.Android.log === 'function') window.Android.log("id-shim.js loaded"); else console.log("id-shim loaded"); } catch(e){}
})();
