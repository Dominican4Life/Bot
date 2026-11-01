// KMK (r97): The probe API (contract §3.7.3). Loaded once into the probe runtime; every probe script is evaluated
// in its own closure through __probeLoad. The host binds __probeEmit, __probeBody, __probeHeader, __probeReplace;
// they are captured here and removed from the global object, so a probe reaches the host only through `probe`/`log`.
// __probeLoad / __probeUnload / __dispatch stay global (the host calls them) but are frozen in place.
(function (global) {
  'use strict';

  var probes = {};

  // Host bindings: captured, then taken off globalThis
  var __probeEmit = global.__probeEmit;
  var __probeBody = global.__probeBody;
  var __probeHeader = global.__probeHeader;
  var __probeReplace = global.__probeReplace;
  var bindingNames = ['__probeEmit', '__probeBody', '__probeHeader', '__probeReplace'];
  for (var b = 0; b < bindingNames.length; b++) {
    try {
      delete global[bindingNames[b]];
    } catch (e) {
      // not configurable: at least make it useless
      try { global[bindingNames[b]] = undefined; } catch (e2) { /* ignore */ }
    }
  }

  function defineHostEntry(name, fn) {
    try {
      Object.defineProperty(global, name, { value: fn, writable: false, configurable: false, enumerable: false });
    } catch (e) {
      global[name] = fn;
    }
  }

  function stringify(value) {
    try {
      var text = typeof value === 'string' ? value : JSON.stringify(value);
      if (text === undefined) return '';
      return text.length > 2048 ? text.slice(0, 2048) + '…' : text;
    } catch (e) {
      return String(value);
    }
  }

  function makeLog(id) {
    function emit(level) {
      return function (msg, obj) {
        var text = String(msg);
        if (obj !== undefined) text += ' ' + stringify(obj);
        __probeEmit(id, level, text);
      };
    }
    return Object.freeze({ info: emit('I'), warn: emit('W'), error: emit('E'), debug: emit('D') });
  }

  function matches(filter, payload) {
    if (!filter) return true;
    if (filter.source && payload.source !== filter.source) return false;
    if (filter.host) {
      var host = payload.host || hostOf(payload.url);
      if (host !== filter.host && !(host && host.indexOf('.' + filter.host) >= 0)) return false;
    }
    if (filter.fn && payload.fn !== filter.fn) return false;
    if (filter.event && payload.event !== filter.event) return false;
    return true;
  }

  function hostOf(url) {
    if (typeof url !== 'string') return null;
    var m = /^[a-z]+:\/\/([^/:?#]+)/i.exec(url);
    return m ? m[1] : null;
  }

  // The r object of net.request / net.response
  function netWrapper(entry, payload, handle) {
    var r = {
      url: payload.url,
      method: payload.method,
      status: payload.status,
      ms: payload.ms,
      size: payload.bytesIn,
      source: payload.source || null,
      host: hostOf(payload.url),
      redirects: payload.redirects || [],
      header: function (name) {
        if (handle < 0) return null;
        var v = __probeHeader(handle, String(name));
        return v === undefined ? null : v;
      },
      text: function (max) {
        if (handle < 0) return '';
        var n = typeof max === 'number' ? max : 65536;
        var body = __probeBody(handle, n);
        if (body === '' && !entry.bodyWarned) {
          entry.bodyWarned = true;
          __probeEmit(entry.id, 'D', 'body not captured: add r.text to the probe and re-enable');
        }
        return body || '';
      },
      replaceBody: function (text) {
        if (!entry.experimental) {
          __probeEmit(entry.id, 'W', 'replaceBody skipped · Experimental is off');
          return false;
        }
        if (handle < 0) return false;
        // The host checks the descriptor's Experimental flag again (the flag above is only what the script was told)
        return __probeReplace(handle, String(text)) === true;
      }
    };
    return r;
  }

  // Returns a JSON array of the registered handlers' hook and source filter (the host matches sources from it)
  defineHostEntry('__probeLoad', function (id, name, experimental, code) {
    var entry = { id: id, name: name, experimental: !!experimental, handlers: [], bodyWarned: false };
    var probe = Object.freeze({
      name: name,
      experimental: !!experimental,
      secrets: Object.freeze({}),
      on: function (hook, filter, fn) {
        if (typeof fn !== 'function') throw new Error('probe.on(hook, filter, fn): fn must be a function');
        entry.handlers.push({ hook: String(hook), filter: filter || null, fn: fn });
      }
    });
    var log = makeLog(id);
    probes[id] = entry;
    try {
      var factory = new Function('probe', 'log', code);
      factory(probe, log);
    } catch (e) {
      delete probes[id];
      throw e;
    }
    var hooks = [];
    for (var i = 0; i < entry.handlers.length; i++) {
      var f = entry.handlers[i].filter;
      hooks.push({ hook: entry.handlers[i].hook, source: f && typeof f.source === 'string' ? f.source : null });
    }
    return JSON.stringify(hooks);
  });

  defineHostEntry('__probeUnload', function (id) {
    delete probes[id];
  });

  // Runs every handler of probe `id` for `hook`. Returns '' or the first error's message.
  defineHostEntry('__dispatch', function (id, hook, json, handle) {
    var entry = probes[id];
    if (!entry) return '';
    var payload;
    try {
      payload = JSON.parse(json);
    } catch (e) {
      return 'bad event: ' + e.message;
    }
    var arg = (hook === 'net.request' || hook === 'net.response') ? netWrapper(entry, payload, handle) : payload;
    var error = '';
    for (var i = 0; i < entry.handlers.length; i++) {
      var h = entry.handlers[i];
      if (h.hook !== hook) continue;
      if (!matches(h.filter, payload)) continue;
      try {
        h.fn(arg);
      } catch (e) {
        if (!error) error = (e && e.message) ? e.message : String(e);
      }
    }
    return error;
  });
})(globalThis);
