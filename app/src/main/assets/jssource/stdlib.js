(function () {
  'use strict';
  var g = globalThis;


  function utf8Bytes(str) {
    var out = [];
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
        var next = str.charCodeAt(i + 1);
        if (next >= 0xdc00 && next <= 0xdfff) {
          c = 0x10000 + ((c - 0xd800) << 10) + (next - 0xdc00);
          i++;
        }
      }
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
      else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    }
    return out;
  }

  function utf8String(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; ) {
      var b = bytes[i++];
      var cp;
      if (b < 0x80) cp = b;
      else if (b >= 0xc0 && b < 0xe0 && i < bytes.length) cp = ((b & 31) << 6) | (bytes[i++] & 63);
      else if (b >= 0xe0 && b < 0xf0 && i + 1 < bytes.length) {
        cp = ((b & 15) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63);
      } else if (b >= 0xf0 && i + 2 < bytes.length) {
        cp = ((b & 7) << 18) | ((bytes[i++] & 63) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63);
      } else cp = 0xfffd;
      if (cp > 0xffff) {
        cp -= 0x10000;
        out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 1023));
      } else out += String.fromCharCode(cp);
    }
    return out;
  }

  if (typeof g.TextEncoder === 'undefined') {
    g.TextEncoder = function TextEncoder() {
      this.encoding = 'utf-8';
    };
    g.TextEncoder.prototype.encode = function (str) {
      return Uint8Array.from(utf8Bytes(String(str === undefined ? '' : str)));
    };
  }
  if (typeof g.TextDecoder === 'undefined') {
    g.TextDecoder = function TextDecoder() {
      this.encoding = 'utf-8';
    };
    g.TextDecoder.prototype.decode = function (bytes) {
      return utf8String(bytes ? Array.prototype.slice.call(bytes) : []);
    };
  }


  function formEncode(s) {
    return encodeURIComponent(s)
      .replace(/%20/g, '+')
      .replace(/[!'()~]/g, function (c) {
        return '%' + c.charCodeAt(0).toString(16).toUpperCase();
      });
  }
  function formDecode(s) {
    s = s.replace(/\+/g, ' ');
    try {
      return decodeURIComponent(s);
    } catch (e) {
      return s;
    }
  }

  function URLSearchParamsPolyfill(init) {
    var list = [];
    Object.defineProperty(this, '_list', { value: list });
    if (typeof init === 'string') {
      init = init.charAt(0) === '?' ? init.slice(1) : init;
      if (init) {
        init.split('&').forEach(function (pair) {
          if (!pair) return;
          var at = pair.indexOf('=');
          list.push(at < 0 ? [formDecode(pair), ''] : [formDecode(pair.slice(0, at)), formDecode(pair.slice(at + 1))]);
        });
      }
    } else if (init && typeof init === 'object') {
      if (typeof init.forEach === 'function' && !Array.isArray(init) && typeof init.entries === 'function') {
        init.forEach(function (value, key) {
          list.push([String(key), String(value)]);
        });
      } else if (Array.isArray(init)) {
        init.forEach(function (pair) {
          list.push([String(pair[0]), String(pair[1])]);
        });
      } else {
        Object.keys(init).forEach(function (key) {
          list.push([key, String(init[key])]);
        });
      }
    }
  }
  var P = URLSearchParamsPolyfill.prototype;
  P.append = function (k, v) {
    this._list.push([String(k), String(v)]);
  };
  P.delete = function (k) {
    var list = this._list;
    for (var i = list.length - 1; i >= 0; i--) if (list[i][0] === k) list.splice(i, 1);
  };
  P.get = function (k) {
    for (var i = 0; i < this._list.length; i++) if (this._list[i][0] === k) return this._list[i][1];
    return null;
  };
  P.getAll = function (k) {
    return this._list
      .filter(function (p) {
        return p[0] === k;
      })
      .map(function (p) {
        return p[1];
      });
  };
  P.has = function (k) {
    return this.get(k) !== null;
  };
  P.set = function (k, v) {
    var found = false;
    var list = this._list;
    for (var i = 0; i < list.length; i++) {
      if (list[i][0] === k) {
        if (found) {
          list.splice(i--, 1);
        } else {
          list[i][1] = String(v);
          found = true;
        }
      }
    }
    if (!found) list.push([String(k), String(v)]);
  };
  P.sort = function () {
    var indexed = this._list.map(function (p, i) {
      return { p: p, i: i };
    });
    indexed.sort(function (a, b) {
      return a.p[0] < b.p[0] ? -1 : a.p[0] > b.p[0] ? 1 : a.i - b.i;
    });
    this._list.length = 0;
    for (var i = 0; i < indexed.length; i++) this._list.push(indexed[i].p);
  };
  P.forEach = function (fn, thisArg) {
    var self = this;
    this._list.slice().forEach(function (p) {
      fn.call(thisArg, p[1], p[0], self);
    });
  };
  P.keys = function () {
    return this._list
      .map(function (p) {
        return p[0];
      })
      [Symbol.iterator]();
  };
  P.values = function () {
    return this._list
      .map(function (p) {
        return p[1];
      })
      [Symbol.iterator]();
  };
  P.entries = function () {
    return this._list
      .map(function (p) {
        return [p[0], p[1]];
      })
      [Symbol.iterator]();
  };
  P[Symbol.iterator] = P.entries;
  P.toString = function () {
    return this._list
      .map(function (p) {
        return formEncode(p[0]) + '=' + formEncode(p[1]);
      })
      .join('&');
  };
  if (typeof g.URLSearchParams === 'undefined') g.URLSearchParams = URLSearchParamsPolyfill;

  function resolveRef(base, ref) {
    ref = String(ref).trim();
    if (/^[a-z][a-z0-9+.-]*:/i.test(ref)) return ref;
    var m = /^([a-z][a-z0-9+.-]*:)(\/\/[^\/?#]*)?([^?#]*)(\?[^#]*)?(#.*)?$/i.exec(String(base));
    if (!m) throw new Error('Not an absolute URL: "' + base + '"');
    var scheme = m[1];
    var authority = m[2] || '';
    var path = m[3] || '';
    var query = m[4] || '';
    if (ref.indexOf('//') === 0) return scheme + ref;
    if (ref === '') return scheme + authority + path + query;
    if (ref.charAt(0) === '#') return scheme + authority + path + query + ref;
    var refQuery = '';
    var refHash = '';
    var hashAt = ref.indexOf('#');
    if (hashAt >= 0) {
      refHash = ref.slice(hashAt);
      ref = ref.slice(0, hashAt);
    }
    var queryAt = ref.indexOf('?');
    if (queryAt >= 0) {
      refQuery = ref.slice(queryAt);
      ref = ref.slice(0, queryAt);
    }
    if (ref === '') return scheme + authority + path + refQuery + refHash;
    var merged = ref.charAt(0) === '/' ? ref : path.slice(0, path.lastIndexOf('/') + 1) + ref;
    if (!authority && merged.charAt(0) !== '/') merged = '/' + merged;
    var out = [];
    merged.split('/').forEach(function (part, i, all) {
      if (part === '..') {
        if (out.length > 1) out.pop();
        if (i === all.length - 1) out.push('');
      } else if (part === '.') {
        if (i === all.length - 1) out.push('');
      } else out.push(part);
    });
    var joined = out.join('/');
    if (joined.charAt(0) !== '/') joined = '/' + joined;
    return scheme + authority + joined + refQuery + refHash;
  }

  function URLPolyfill(url, base) {
    var full = base === undefined ? String(url) : resolveRef(String(base), String(url));
    var m = /^([a-z][a-z0-9+.-]*:)\/\/(?:([^:@\/?#]*)(?::([^@\/?#]*))?@)?(\[[^\]]*\]|[^:\/?#]*)(?::(\d+))?([^?#]*)(\?[^#]*)?(#.*)?$/i.exec(full);
    if (!m) throw new TypeError('Invalid URL: "' + full + '"');
    this.protocol = m[1].toLowerCase();
    this.username = m[2] || '';
    this.password = m[3] || '';
    this.hostname = m[4].toLowerCase();
    this.port = m[5] || '';
    this.pathname = m[6] || '/';
    this.hash = m[8] && m[8] !== '#' ? m[8] : '';
    Object.defineProperty(this, 'searchParams', { value: new g.URLSearchParams(m[7] || '') });
  }
  var U = URLPolyfill.prototype;
  Object.defineProperty(U, 'search', {
    get: function () {
      var s = this.searchParams.toString();
      return s ? '?' + s : '';
    },
    set: function (v) {
      this.searchParams._list.length = 0;
      var fresh = new g.URLSearchParams(String(v));
      var list = this.searchParams._list;
      fresh._list.forEach(function (p) {
        list.push(p);
      });
    },
  });
  Object.defineProperty(U, 'host', {
    get: function () {
      return this.hostname + (this.port ? ':' + this.port : '');
    },
  });
  Object.defineProperty(U, 'origin', {
    get: function () {
      return this.protocol + '//' + this.host;
    },
  });
  Object.defineProperty(U, 'href', {
    get: function () {
      var auth = this.username ? this.username + (this.password ? ':' + this.password : '') + '@' : '';
      return this.protocol + '//' + auth + this.host + this.pathname + this.search + this.hash;
    },
  });
  U.toString = function () {
    return this.href;
  };
  U.toJSON = U.toString;
  if (typeof g.URL === 'undefined') g.URL = URLPolyfill;


  function ask(name, payload) {
    return sendMessage(name, JSON.stringify(payload)).then(function (result) {
      var parsed = JSON.parse(result);
      if (parsed && parsed.error) throw new Error(parsed.error);
      return parsed;
    });
  }

  function show(value) {
    if (typeof value === 'string') return value;
    if (value instanceof Error) return value.stack || String(value);
    var seen = [];
    try {
      return JSON.stringify(value, function (key, v) {
        if (typeof v === 'object' && v !== null) {
          if (seen.indexOf(v) >= 0) return '[circular]';
          seen.push(v);
        }
        return typeof v === 'undefined' ? '[undefined]' : v;
      });
    } catch (e) {
      return String(value);
    }
  }
  var nativeConsole = g.console;
  var sourceConsole = {};
  ['log', 'info', 'warn', 'error', 'debug'].forEach(function (level) {
    sourceConsole[level] = function () {
      var text = Array.prototype.map.call(arguments, show).join(' ');
      try {
        sendMessage('log', JSON.stringify({ level: level, text: text }));
      } catch (e) {
        // A log line is never worth failing the source over.
      }
      if (nativeConsole && typeof nativeConsole[level] === 'function') {
        try {
          nativeConsole[level].apply(nativeConsole, arguments);
        } catch (e) {
        }
      }
    };
  });
  g.console = sourceConsole;

  g.host.trace = function (kind, data) {
    if (!g.host.isTesting) return;
    try {
      sendMessage('trace', JSON.stringify({ kind: kind, data: data }));
    } catch (e) {
    }
  };

  function cryptoOptions(options) {
    var o = options || {};
    return {
      mode: o.mode,
      key: o.key,
      iv: o.iv,
      passphrase: o.passphrase,
      keyEncoding: o.keyEncoding,
      ivEncoding: o.ivEncoding,
      dataEncoding: o.dataEncoding,
      output: o.output,
      padding: o.padding,
    };
  }

  var cryptoApi = {
    aes: {
      encrypt: function (data, options) {
        var payload = cryptoOptions(options);
        payload.encrypt = true;
        payload.data = String(data);
        return ask('aes', payload).then(function (r) {
          return r.value;
        });
      },
      decrypt: function (data, options) {
        var payload = cryptoOptions(options);
        payload.encrypt = false;
        payload.data = String(data);
        return ask('aes', payload).then(function (r) {
          return r.value;
        });
      },
    },
    hmac: function (algorithm, key, input, options) {
      var o = options || {};
      return ask('hmac', {
        algorithm: algorithm,
        key: key,
        input: input,
        keyEncoding: o.keyEncoding,
        output: o.output,
      }).then(function (r) {
        return r.value;
      });
    },
    hash: function (algorithm, input) {
      return g.host.hash(algorithm, input);
    },
    rsa: {
      // padding: "oaep" (default, needs hash: sha1/sha256/sha384/sha512) or "pkcs1".
      encrypt: function (data, publicKeyPem, options) {
        var o = options || {};
        return ask('rsaEncrypt', {
          data: String(data),
          publicKeyPem: String(publicKeyPem),
          padding: o.padding,
          hash: o.hash,
          dataEncoding: o.dataEncoding,
          output: o.output,
        }).then(function (r) { return r.value; });
      },
      // Verifies a PKCS#1v1.5 signature (the usual RS256/RS384/RS512 shape).
      verify: function (data, signature, publicKeyPem, options) {
        var o = options || {};
        return ask('rsaVerify', {
          data: String(data),
          signature: String(signature),
          publicKeyPem: String(publicKeyPem),
          hash: o.hash,
          dataEncoding: o.dataEncoding,
          signatureEncoding: o.signatureEncoding,
        }).then(function (r) { return r.ok; });
      },
    },
  };
  g.host.crypto = cryptoApi;


  var compressApi = {
    gzip: {
      decode: function (data, options) {
        var o = options || {};
        return ask('gzipDecode', { data: String(data), encoding: o.encoding, output: o.output })
          .then(function (r) { return r.value; });
      },
      encode: function (data, options) {
        var o = options || {};
        return ask('gzipEncode', { data: String(data), encoding: o.encoding, output: o.output })
          .then(function (r) { return r.value; });
      },
    },
    deflate: {
      // raw: true reads/writes plain deflate with no zlib header, which is
      // what some sites mean by "deflate" despite the name.
      decode: function (data, options) {
        var o = options || {};
        return ask('deflateDecode', { data: String(data), encoding: o.encoding, output: o.output, raw: o.raw })
          .then(function (r) { return r.value; });
      },
      encode: function (data, options) {
        var o = options || {};
        return ask('deflateEncode', { data: String(data), encoding: o.encoding, output: o.output })
          .then(function (r) { return r.value; });
      },
    },
  };
  g.host.compress = compressApi;


  function search(page, from, kind, expr, options) {
    var o = options || {};
    var payload = { page: page, html: o.html !== false };
    if (from !== null) payload.from = from;
    if (o.limit) payload.limit = o.limit;
    payload[kind] = expr;
    return ask('selectHtml', payload).then(function (r) {
      if (r.values) return r.values;
      return r.items.map(function (item) {
        return makeElement(page, item);
      });
    });
  }

  function scope(page, from) {
    var api = {
      select: function (css, options) {
        return search(page, from, 'css', css, options);
      },
      xpath: function (expr, options) {
        return search(page, from, 'xpath', expr, options);
      },
      selectOne: function (css) {
        return search(page, from, 'css', css, { limit: 1 }).then(function (found) {
          return found.length ? found[0] : null;
        });
      },
      xpathOne: function (expr) {
        return search(page, from, 'xpath', expr, { limit: 1 }).then(function (found) {
          return found.length ? found[0] : null;
        });
      },
      text: function (css) {
        return api.selectOne(css).then(function (el) {
          return el ? el.text : null;
        });
      },
      attr: function (css, name) {
        return api.selectOne(css).then(function (el) {
          return el && el.attributes[name] !== undefined ? el.attributes[name] : null;
        });
      },
    };
    return api;
  }

  function makeElement(page, item) {
    var el = scope(page, item.id);
    el.id = item.id;
    el.tag = item.tag;
    el.text = item.text;
    el.html = item.html;
    el.attributes = item.attributes;
    var own = el.attr;
    el.attr = function (nameOrSelector, name) {
      if (name === undefined) {
        return item.attributes[nameOrSelector] !== undefined ? item.attributes[nameOrSelector] : null;
      }
      return own(nameOrSelector, name);
    };
    return el;
  }

  g.host.parse = function (html) {
    return ask('parseHtml', { html: String(html) }).then(function (r) {
      return scope(r.id, null);
    });
  };


  function patternText(value) {
    if (value === undefined || value === null) return undefined;
    return value instanceof RegExp ? value.source : String(value);
  }

  g.host.render = function (url, options) {
    var o = options || {};
    return ask('render', {
      url: String(url),
      waitFor: o.waitFor,
      waitForResource: patternText(o.waitForResource),
      capture: patternText(o.capture),
      script: o.script,
      userAgent: o.userAgent,
      timeout: o.timeout,
    });
  };


  // Waits ms milliseconds, up to 10000. Useful for a source's own rate
  // limiting, or between retries (fetch's own retry option does this for you).
  g.host.sleep = function (ms) {
    return ask('sleep', { ms: ms }).then(function () { return undefined; });
  };


  g.host.cookies = {
    get: function (url) {
      return ask('cookiesGet', { url: String(url) }).then(function (r) {
        return r.cookies;
      });
    },
    set: function (url, name, value, options) {
      var o = options || {};
      return ask('cookiesSet', {
        url: String(url),
        name: String(name),
        value: String(value),
        domain: o.domain,
        path: o.path,
        maxAge: o.maxAge,
        secure: o.secure,
        httpOnly: o.httpOnly,
      }).then(function () { return true; });
    },
  };


  g.host.zip = {
    // options.encoding: the whole archive blob's own encoding (base64 default).
    entries: function (data, options) {
      var o = options || {};
      return ask('zipEntries', { data: String(data), encoding: o.encoding }).then(function (r) {
        return r.entries;
      });
    },
    // options.encoding as above; options.output: the read entry's encoding (base64 default).
    read: function (data, name, options) {
      var o = options || {};
      return ask('zipRead', {
        data: String(data),
        name: String(name),
        encoding: o.encoding,
        output: o.output,
      }).then(function (r) {
        return r.value;
      });
    },
  };


  function makeHeaders(raw) {
    var map = {};
    Object.keys(raw || {}).forEach(function (k) {
      map[k.toLowerCase()] = String(raw[k]);
    });
    var headers = {};
    Object.keys(map).forEach(function (k) {
      headers[k] = map[k];
    });
    Object.defineProperty(headers, 'get', {
      value: function (name) {
        var v = map[String(name).toLowerCase()];
        return v === undefined ? null : v;
      },
    });
    Object.defineProperty(headers, 'has', {
      value: function (name) {
        return Object.prototype.hasOwnProperty.call(map, String(name).toLowerCase());
      },
    });
    return headers;
  }

  g.fetch = function (url, options) {
    var o = {};
    if (options) {
      Object.keys(options).forEach(function (k) {
        o[k] = options[k];
      });
    }
    var headers = {};
    Object.keys(o.headers || {}).forEach(function (k) {
      headers[k] = o.headers[k];
    });
    function hasHeader(name) {
      return Object.keys(headers).some(function (k) {
        return k.toLowerCase() === name;
      });
    }
    if (o.json !== undefined) {
      o.body = JSON.stringify(o.json);
      if (!hasHeader('content-type')) headers['Content-Type'] = 'application/json';
      delete o.json;
    } else if (o.form !== undefined) {
      o.body = new g.URLSearchParams(o.form).toString();
      if (!hasHeader('content-type')) headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
      delete o.form;
    } else if (o.body instanceof g.URLSearchParams) {
      o.body = o.body.toString();
      if (!hasHeader('content-type')) headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
    }
    if (o.body !== undefined && o.body !== null && !o.method) o.method = 'POST';
    o.headers = headers;
    var requested = String(url);
    // retry: { attempts, statuses, baseDelayMs }. A 429/503 (or whatever
    // statuses names) waits Retry-After if the response gave one, else a
    // doubling backoff from baseDelayMs, and tries again.
    var retry = o.retry;
    delete o.retry;
    // cache: true reuses host.storage to remember an ETag/Last-Modified
    // for this address, and revalidates instead of re-downloading — a 304
    // (Not Modified) hands back the body kept from last time. Only for a
    // GET, and never alongside binary: true — the cached copy only ever
    // holds the decoded text body, not bodyBase64, so a 304 for a binary
    // request would come back with nothing to read.
    var useCache =
      !!o.cache &&
      g.host.cacheExtensionDataEnabled !== false &&
      !o.binary &&
      (!o.method || o.method === 'GET');
    delete o.cache;

    function toResponse(result) {
      var body = result.body;
      var finalUrl = result.url || requested;
      var responseHeaders = makeHeaders(result.headers);
      return {
        ok: result.statusCode >= 200 && result.statusCode < 300,
        status: result.statusCode,
        url: finalUrl,
        redirected: finalUrl !== requested,
        headers: responseHeaders,
        text: function () {
          return Promise.resolve(body);
        },
        json: function () {
          return Promise.resolve(JSON.parse(body));
        },
        // The base64 of the raw response body — only set when { binary: true
        // } was passed, since otherwise the body was already decoded as text
        // and may no longer be the exact original bytes.
        base64: function () {
          return Promise.resolve(result.bodyBase64);
        },
      };
    }

    function cacheKey() {
      return '__fetchCache:' + requested;
    }

    function headerValue(rawHeaders, name) {
      var lower = name.toLowerCase();
      var found;
      Object.keys(rawHeaders || {}).some(function (k) {
        if (k.toLowerCase() === lower) {
          found = rawHeaders[k];
          return true;
        }
        return false;
      });
      return found;
    }

    function attempt(n, cached) {
      return g.host.fetch(requested, o).then(function (result) {
        var statuses = (retry && retry.statuses) || [429, 503];
        var attempts = (retry && retry.attempts) || 3;
        if (retry && n < attempts && statuses.indexOf(result.statusCode) !== -1) {
          var retryAfterHeader = headerValue(result.headers, 'retry-after');
          var seconds = retryAfterHeader ? parseFloat(retryAfterHeader) : NaN;
          var ms = !isNaN(seconds) ? seconds * 1000 : ((retry.baseDelayMs || 500) * Math.pow(2, n));
          return g.host.sleep(ms).then(function () {
            return attempt(n + 1, cached);
          });
        }
        if (useCache && result.statusCode === 304 && cached) {
          return toResponse({
            statusCode: 200,
            url: result.url || requested,
            body: cached.body,
            headers: cached.headers || {},
          });
        }
        if (useCache && result.statusCode >= 200 && result.statusCode < 300) {
          var etag = headerValue(result.headers, 'etag');
          var lastModified = headerValue(result.headers, 'last-modified');
          if (etag || lastModified) {
            g.host.storage.set(cacheKey(), {
              etag: etag,
              lastModified: lastModified,
              body: result.body,
              headers: result.headers,
            });
          }
        }
        return toResponse(result);
      });
    }

    if (!useCache) return attempt(0, null);
    return g.host.storage.get(cacheKey()).then(function (cached) {
      if (cached) {
        if (cached.etag) headers['If-None-Match'] = cached.etag;
        if (cached.lastModified) headers['If-Modified-Since'] = cached.lastModified;
      }
      return attempt(0, cached);
    });
  };


  var PACKED = /\}\s*\(\s*(['"])((?:\\[\s\S]|(?!\1)[^\\])*)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])((?:\\[\s\S]|(?!\5)[^\\])*)\5\s*\.split\(\s*(['"])\|\7\s*\)/;
  var PACKED_MARK = /eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)/;

  function unescapeJs(s) {
    return s.replace(/\\(u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|[\s\S])/g, function (all, e) {
      if (e.charAt(0) === 'u' && e.length === 5) return String.fromCharCode(parseInt(e.slice(1), 16));
      if (e.charAt(0) === 'x' && e.length === 3) return String.fromCharCode(parseInt(e.slice(1), 16));
      if (e === 'n') return '\n';
      if (e === 't') return '\t';
      if (e === 'r') return '\r';
      return e;
    });
  }

  function baseDigit(n, radix) {
    return (n < radix ? '' : baseDigit(Math.floor(n / radix), radix)) + ((n = n % radix) > 35 ? String.fromCharCode(n + 29) : n.toString(36));
  }

  // The text a packed script would evaluate to. Throws when source is not a packed script.
  function unpack(source) {
    var m = PACKED.exec(source);
    if (!m) throw new Error('unpacker: not a p.a.c.k.e.r script');
    var payload = unescapeJs(m[2]);
    var radix = parseInt(m[3], 10);
    var count = parseInt(m[4], 10);
    var words = unescapeJs(m[6]).split('|');
    if (radix < 2 || radix > 62) throw new Error('unpacker: unsupported radix ' + radix);
    var table = {};
    for (var i = count - 1; i >= 0; i--) {
      var key = baseDigit(i, radix);
      table[key] = words[i] || key;
    }
    return payload.replace(/\b\w+\b/g, function (word) {
      return Object.prototype.hasOwnProperty.call(table, word) ? table[word] : word;
    });
  }

  // Every packed script found in html, unpacked. Scripts that cannot be unpacked are left out.
  function unpackAll(html) {
    var found = [];
    var re = /eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)[\s\S]*?\.split\(\s*(['"])\|\1\s*\)[^\n<]*/g;
    var m;
    while ((m = re.exec(String(html)))) {
      try {
        found.push(unpack(m[0]));
      } catch (e) {
      }
    }
    return found;
  }


  function attributes(line) {
    var out = {};
    var re = /([A-Z0-9-]+)=("[^"]*"|[^,]*)/g;
    var m;
    while ((m = re.exec(line))) {
      var v = m[2];
      out[m[1]] = v.charAt(0) === '"' ? v.slice(1, -1) : v;
    }
    return out;
  }

  function isMasterPlaylist(text) {
    return /#EXT-X-STREAM-INF/.test(String(text));
  }

  function qualityLabel(height, bandwidth) {
    if (height) return height + 'p';
    if (bandwidth) return Math.round(bandwidth / 1000) + ' kbps';
    return 'Auto';
  }

  function parseMaster(text, baseUrl) {
    var lines = String(text).split(/\r?\n/);
    var variants = [];
    var audio = [];
    var subtitles = [];
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].trim();
      if (line.indexOf('#EXT-X-STREAM-INF:') === 0) {
        var a = attributes(line.slice(18));
        var next = i + 1;
        while (next < lines.length && (lines[next].trim() === '' || lines[next].trim().charAt(0) === '#')) next++;
        if (next >= lines.length) continue;
        var size = /^(\d+)x(\d+)$/.exec(a.RESOLUTION || '');
        var bandwidth = parseInt(a.BANDWIDTH || a['AVERAGE-BANDWIDTH'] || '0', 10) || 0;
        var height = size ? parseInt(size[2], 10) : 0;
        variants.push({
          url: resolveRef(baseUrl, lines[next].trim()),
          bandwidth: bandwidth,
          width: size ? parseInt(size[1], 10) : 0,
          height: height,
          codecs: a.CODECS || null,
          frameRate: a['FRAME-RATE'] ? parseFloat(a['FRAME-RATE']) : null,
          audioGroup: a.AUDIO || null,
          quality: qualityLabel(height, bandwidth),
        });
        i = next;
      } else if (line.indexOf('#EXT-X-MEDIA:') === 0) {
        var t = attributes(line.slice(13));
        if (!t.URI) continue;
        var track = {
          url: resolveRef(baseUrl, t.URI),
          label: t.NAME || t.LANGUAGE || 'Track',
          language: t.LANGUAGE || null,
        };
        if (t.TYPE === 'AUDIO') audio.push(track);
        else if (t.TYPE === 'SUBTITLES') subtitles.push(track);
      }
    }
    variants.sort(function (x, y) {
      return y.bandwidth - x.bandwidth || y.height - x.height;
    });
    return { variants: variants, audio: audio, subtitles: subtitles };
  }

  function expandPlaylist(video, options) {
    var o = options || {};
    var headers = video.headers || o.headers || {};
    return g
      .fetch(video.url, { headers: headers })
      .then(function (response) {
        if (!response.ok) return [video];
        return response.text().then(function (text) {
          if (!isMasterPlaylist(text)) return [video];
          var master = parseMaster(text, response.url || video.url);
          if (master.variants.length < 2 || master.audio.length) return [video];
          var shared = {};
          if (video.headers) shared.headers = video.headers;
          if (video.subtitles && video.subtitles.length) shared.subtitles = video.subtitles;
          // KMK: each quality stays under its server's group in the player
          if (video.server) shared.server = video.server;
          if (video.hoster) shared.hoster = video.hoster;
          var auto = {};
          Object.keys(video).forEach(function (k) {
            auto[k] = video[k];
          });
          auto.quality = video.quality || 'Auto';
          var out = [auto];
          master.variants.forEach(function (v) {
            var one = { url: v.url, quality: v.quality };
            Object.keys(shared).forEach(function (k) {
              one[k] = shared[k];
            });
            out.push(one);
          });
          return out;
        });
      })
      .catch(function () {
        return [video];
      });
  }


  // A DASH manifest is XML, not line-oriented like HLS, so this reads
  // attributes with a regex instead of a real parser (the same trade-off
  // the local EPUB reader makes for its OPF/NCX). Only a Representation with
  // its own single <BaseURL> resolves to a variant; one built from
  // <SegmentTemplate>/<SegmentList> (many small fragments, no one URL to
  // download or play whole) is left out rather than guessed at.
  function isDashManifest(text) {
    return /<MPD[\s>]/i.test(String(text));
  }

  function dashAttr(tag, name) {
    var m = new RegExp('\\b' + name + '\\s*=\\s*(?:"([^"]*)"|\'([^\']*)\')', 'i').exec(tag);
    if (!m) return null;
    return m[1] !== undefined ? m[1] : m[2];
  }

  function parseDashManifest(text, baseUrl) {
    var xml = String(text);
    var mpdBase = /<BaseURL[^>]*>([\s\S]*?)<\/BaseURL>/i.exec(xml);
    var manifestBase = mpdBase ? resolveRef(baseUrl, mpdBase[1].trim()) : baseUrl;

    var variants = [];
    var audio = [];
    var adaptationRe = /<AdaptationSet\b([^>]*)>([\s\S]*?)<\/AdaptationSet>/gi;
    var adMatch;
    while ((adMatch = adaptationRe.exec(xml))) {
      var mimeType = dashAttr(adMatch[1], 'mimeType') || '';
      var isAudio = mimeType.indexOf('audio') === 0;
      var adBody = adMatch[2];

      var repRe = /<Representation\b([^>]*?)(?:\/>|>([\s\S]*?)<\/Representation>)/gi;
      var repMatch;
      while ((repMatch = repRe.exec(adBody))) {
        var repTag = repMatch[1];
        var repBody = repMatch[2] || '';
        var repBase = /<BaseURL[^>]*>([\s\S]*?)<\/BaseURL>/i.exec(repBody);
        if (!repBase) continue;
        var url = resolveRef(manifestBase, repBase[1].trim());
        var bandwidth = parseInt(dashAttr(repTag, 'bandwidth') || '0', 10) || 0;
        var width = parseInt(dashAttr(repTag, 'width') || '0', 10) || 0;
        var height = parseInt(dashAttr(repTag, 'height') || '0', 10) || 0;
        var codecs = dashAttr(repTag, 'codecs');
        if (isAudio) {
          audio.push({ url: url, bandwidth: bandwidth, codecs: codecs, label: qualityLabel(0, bandwidth) });
        } else {
          variants.push({ url: url, bandwidth: bandwidth, width: width, height: height, codecs: codecs, quality: qualityLabel(height, bandwidth) });
        }
      }
    }
    variants.sort(function (x, y) {
      return y.bandwidth - x.bandwidth || y.height - x.height;
    });
    return { variants: variants, audio: audio };
  }


  var b64 = {
    encode: function (str) {
      return g.btoa(
        utf8Bytes(String(str))
          .map(function (b) {
            return String.fromCharCode(b);
          })
          .join('')
      );
    },
    decode: function (str) {
      var bytes = b64.toBytes(str);
      return utf8String(bytes);
    },
    toBytes: function (str) {
      var s = String(str).replace(/-/g, '+').replace(/_/g, '/').replace(/\s/g, '');
      while (s.length % 4) s += '=';
      var raw = g.atob(s);
      var out = [];
      for (var i = 0; i < raw.length; i++) out.push(raw.charCodeAt(i));
      return out;
    },
  };

  function bytesToBase64(bytes) {
    return g.btoa(bytes.map(function (b) { return String.fromCharCode(b); }).join(''));
  }


  // Decodes a JWT's header and payload without needing to check its
  // signature — useful just to read what a token says. verify() checks it
  // against a key, for HS256/384/512 (a shared secret) or RS256/384/512 (an
  // RSA public key); any other alg (or a malformed token) is not verified.
  function jwtDecode(token) {
    var parts = String(token).split('.');
    if (parts.length < 2) throw new Error('jwt: not a JWT');
    return {
      header: JSON.parse(b64.decode(parts[0])),
      payload: JSON.parse(b64.decode(parts[1])),
      signature: parts[2] || null,
    };
  }

  function jwtVerify(token, options) {
    var o = options || {};
    var parts = String(token).split('.');
    if (parts.length !== 3) return Promise.resolve(false);
    var signingInput = parts[0] + '.' + parts[1];
    var header;
    try {
      header = JSON.parse(b64.decode(parts[0]));
    } catch (e) {
      return Promise.resolve(false);
    }
    var alg = header.alg || '';
    var sigBase64 = bytesToBase64(b64.toBytes(parts[2]));
    if (alg === 'HS256' || alg === 'HS384' || alg === 'HS512') {
      return cryptoApi.hmac('sha' + alg.slice(2), o.secret, signingInput, { output: 'base64' })
        .then(function (mac) { return mac === sigBase64; })
        .catch(function () { return false; });
    }
    if (alg === 'RS256' || alg === 'RS384' || alg === 'RS512') {
      return cryptoApi.rsa.verify(signingInput, sigBase64, o.publicKeyPem, { hash: 'sha' + alg.slice(2) })
        .catch(function () { return false; });
    }
    return Promise.resolve(false);
  }


  function subtitleTimeToMs(str) {
    var m = /^(\d{2}):(\d{2}):(\d{2})[.,](\d{1,3})$/.exec(String(str).trim());
    if (!m) return null;
    var ms = parseInt(m[4], 10);
    if (m[4].length === 1) ms *= 100;
    else if (m[4].length === 2) ms *= 10;
    return ((parseInt(m[1], 10) * 60 + parseInt(m[2], 10)) * 60 + parseInt(m[3], 10)) * 1000 + ms;
  }

  function msToSubtitleTime(ms, sep) {
    function pad(n, w) {
      var s = String(Math.floor(n));
      while (s.length < w) s = '0' + s;
      return s;
    }
    var h = Math.floor(ms / 3600000); ms -= h * 3600000;
    var m = Math.floor(ms / 60000); ms -= m * 60000;
    var s = Math.floor(ms / 1000); ms -= s * 1000;
    return pad(h, 2) + ':' + pad(m, 2) + ':' + pad(s, 2) + sep + pad(ms, 3);
  }

  function subtitleCuesFrom(text, requireHeader) {
    var body = String(text).replace(/\r/g, '');
    if (requireHeader) body = body.replace(/^WEBVTT[^\n]*\n/, '');
    var cues = [];
    body.split(/\n\n+/).forEach(function (block) {
      var lines = block.split('\n').filter(function (l) { return l.trim() !== ''; });
      var timeLineIndex = -1;
      for (var i = 0; i < lines.length; i++) {
        if (lines[i].indexOf('-->') !== -1) { timeLineIndex = i; break; }
      }
      if (timeLineIndex === -1) return;
      var m = /(.+?)\s*-->\s*(\S+)/.exec(lines[timeLineIndex]);
      if (!m) return;
      var start = subtitleTimeToMs(m[1]);
      var end = subtitleTimeToMs(m[2]);
      if (start === null || end === null) return;
      cues.push({
        index: cues.length + 1,
        start: start,
        end: end,
        text: lines.slice(timeLineIndex + 1).join('\n'),
      });
    });
    return cues;
  }

  var subtitlesApi = {
    parseSrt: function (text) { return subtitleCuesFrom(text, false); },
    parseVtt: function (text) { return subtitleCuesFrom(text, true); },
    toSrt: function (cues) {
      return cues.map(function (c, i) {
        return (i + 1) + '\n' + msToSubtitleTime(c.start, ',') + ' --> ' + msToSubtitleTime(c.end, ',') + '\n' + c.text;
      }).join('\n\n') + '\n';
    },
    toVtt: function (cues) {
      return 'WEBVTT\n\n' + cues.map(function (c) {
        return msToSubtitleTime(c.start, '.') + ' --> ' + msToSubtitleTime(c.end, '.') + '\n' + c.text;
      }).join('\n\n') + '\n';
    },
  };


  // Dates as sites write them: ISO, month names, numbers in either order, two digit years, and "3 hours ago".
  var MONTHS = {
    jan: 0, january: 0, feb: 1, february: 1, mar: 2, march: 2, apr: 3, april: 3, may: 4,
    jun: 5, june: 5, jul: 6, july: 6, aug: 7, august: 7, sep: 8, sept: 8, september: 8,
    oct: 9, october: 9, nov: 10, november: 10, dec: 11, december: 11,
  };
  var UNIT_MS = {
    s: 1000, sec: 1000, secs: 1000, second: 1000, seconds: 1000,
    m: 60000, min: 60000, mins: 60000, minute: 60000, minutes: 60000,
    h: 3600000, hr: 3600000, hrs: 3600000, hour: 3600000, hours: 3600000,
    d: 86400000, day: 86400000, days: 86400000,
    w: 604800000, wk: 604800000, wks: 604800000, week: 604800000, weeks: 604800000,
  };

  function fullYear(y) {
    var n = parseInt(y, 10);
    if (String(y).length <= 2) n += n > 69 ? 1900 : 2000;
    return n;
  }

  function built(y, mo, d, h, mi, s) {
    var date = new Date(y, mo, d, h || 0, mi || 0, s || 0);
    if (isNaN(date.getTime()) || date.getMonth() !== mo || date.getDate() !== d) return null;
    return date.toISOString();
  }

  function relative(text, now) {
    var t = text.toLowerCase().trim();
    if (/^(just now|now|moments? ago|a few seconds ago)$/.test(t)) return new Date(now).toISOString();
    if (t === 'today') return new Date(now).toISOString();
    if (t === 'yesterday') return new Date(now - 86400000).toISOString();
    var m = /^(an?|one|\d+)\s*([a-z]+?)\.?\s*(ago)?$/.exec(t) || /^(an?|one|\d+)\s*([a-z]+)\s+ago$/.exec(t);
    if (!m) return null;
    var count = /^(a|an|one)$/.test(m[1]) ? 1 : parseInt(m[1], 10);
    var unit = m[2];
    if (UNIT_MS[unit]) return new Date(now - count * UNIT_MS[unit]).toISOString();
    var date = new Date(now);
    if (/^(mo|mos|month|months)$/.test(unit)) {
      date.setMonth(date.getMonth() - count);
      return date.toISOString();
    }
    if (/^(y|yr|yrs|year|years)$/.test(unit)) {
      date.setFullYear(date.getFullYear() - count);
      return date.toISOString();
    }
    return null;
  }

  // A pattern such as "dd/MM/yyyy" or "MMM d, yy HH:mm" for sites whose numbers could be read either way.
  function withFormat(text, format) {
    var tokens = [];
    var source = '';
    var re = /(yyyy|yy|MMMM|MMM|MM|M|dd|d|HH|H|hh|h|mm|ss|a)/g;
    var last = 0;
    var match;
    while ((match = re.exec(format))) {
      source += format.slice(last, match.index).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      tokens.push(match[1]);
      source += match[1] === 'MMMM' || match[1] === 'MMM' ? '([A-Za-z]+)' : match[1] === 'a' ? '(am|pm|AM|PM)' : '(\\d{1,4})';
      last = match.index + match[1].length;
    }
    source += format.slice(last).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    var found = new RegExp(source.replace(/\s+/g, '\\s*')).exec(text);
    if (!found) return null;
    var y = new Date().getFullYear();
    var mo = 0;
    var d = 1;
    var h = 0;
    var mi = 0;
    var s = 0;
    var pm = null;
    tokens.forEach(function (token, i) {
      var v = found[i + 1];
      if (token === 'yyyy' || token === 'yy') y = fullYear(v);
      else if (token === 'MMMM' || token === 'MMM') {
        var key = v.toLowerCase();
        mo = MONTHS[key] !== undefined ? MONTHS[key] : MONTHS[key.slice(0, 3)] !== undefined ? MONTHS[key.slice(0, 3)] : -1;
      }
      else if (token === 'MM' || token === 'M') mo = parseInt(v, 10) - 1;
      else if (token === 'dd' || token === 'd') d = parseInt(v, 10);
      else if (token === 'HH' || token === 'H' || token === 'hh' || token === 'h') h = parseInt(v, 10);
      else if (token === 'mm') mi = parseInt(v, 10);
      else if (token === 'ss') s = parseInt(v, 10);
      else if (token === 'a') pm = v.toLowerCase() === 'pm';
    });
    if (mo < 0) return null;
    if (pm === true && h < 12) h += 12;
    if (pm === false && h === 12) h = 0;
    return built(y, mo, d, h, mi, s);
  }

  function parseDate(value, format, now) {
    if (value === null || value === undefined || value === '') return null;
    if (typeof value === 'number') {
      var ms = value < 1e12 ? value * 1000 : value;
      var n = new Date(ms);
      return isNaN(n.getTime()) ? null : n.toISOString();
    }
    var text = String(value).replace(/\s+/g, ' ').trim();
    if (!text) return null;
    var clock = typeof now === 'number' ? now : Date.now();
    if (format) return withFormat(text, format);
    var rel = relative(text.replace(/^(updated|released|posted)\s*:?\s*/i, ''), clock);
    if (rel) return rel;
    // ISO and other forms the built in reader is sure about.
    if (/^\d{4}-\d{2}-\d{2}/.test(text)) {
      var iso = new Date(text);
      if (!isNaN(iso.getTime())) return iso.toISOString();
    }
    var lower = text.toLowerCase().replace(/(\d)(st|nd|rd|th)\b/g, '$1').replace(/,/g, ' ').replace(/\s+/g, ' ');
    // "Aug-03-2018" and "03.Aug.2018" read like the spaced forms.
    if (/[a-z]{3}/.test(lower)) lower = lower.replace(/(\d|[a-z])[-\/.](?=\d|[a-z])/g, '$1 ');
    var time = /(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(am|pm)?/.exec(lower);
    var hh = 0;
    var mm = 0;
    var ss = 0;
    if (time) {
      hh = parseInt(time[1], 10);
      mm = parseInt(time[2], 10);
      ss = time[3] ? parseInt(time[3], 10) : 0;
      if (time[4] === 'pm' && hh < 12) hh += 12;
      if (time[4] === 'am' && hh === 12) hh = 0;
      lower = lower.replace(time[0], ' ').trim();
    }
    // "Nov 27 24", "November 27 2024"
    var a = /([a-z]{3,9})\.? (\d{1,2}) (\d{2,4})\b/.exec(lower);
    if (a && MONTHS[a[1]] !== undefined) return built(fullYear(a[3]), MONTHS[a[1]], parseInt(a[2], 10), hh, mm, ss);
    // "27 Nov 2024"
    var b = /(\d{1,2}) ([a-z]{3,9})\.? (\d{2,4})\b/.exec(lower);
    if (b && MONTHS[b[2]] !== undefined) return built(fullYear(b[3]), MONTHS[b[2]], parseInt(b[1], 10), hh, mm, ss);
    // "Nov 27" in the current year, or last year when that would be in the future.
    var c = /^([a-z]{3,9})\.? (\d{1,2})$/.exec(lower) || /^(\d{1,2}) ([a-z]{3,9})\.?$/.exec(lower);
    if (c) {
      var monthName = MONTHS[c[1]] !== undefined ? c[1] : c[2];
      var day = parseInt(MONTHS[c[1]] !== undefined ? c[2] : c[1], 10);
      if (MONTHS[monthName] !== undefined) {
        var thisYear = new Date(clock).getFullYear();
        var guess = built(thisYear, MONTHS[monthName], day, hh, mm, ss);
        if (guess && new Date(guess).getTime() > clock + 86400000) guess = built(thisYear - 1, MONTHS[monthName], day, hh, mm, ss);
        return guess;
      }
    }
    // Numbers: year first, or day and month in either order.
    var nums = /^(\d{1,4})[\/.\-](\d{1,2})[\/.\-](\d{1,4})$/.exec(lower);
    if (nums) {
      if (nums[1].length === 4) return built(parseInt(nums[1], 10), parseInt(nums[2], 10) - 1, parseInt(nums[3], 10), hh, mm, ss);
      var p = parseInt(nums[1], 10);
      var q = parseInt(nums[2], 10);
      var y = fullYear(nums[3]);
      // The day comes first unless it cannot.
      if (p > 12) return built(y, q - 1, p, hh, mm, ss);
      if (q > 12) return built(y, p - 1, q, hh, mm, ss);
      return built(y, q - 1, p, hh, mm, ss);
    }
    var native = new Date(text);
    return isNaN(native.getTime()) ? null : native.toISOString();
  }

  var dateApi = { parse: parseDate };

  var baseRequire = g.require;
  var extra = {
    crypto: cryptoApi,
    base64: b64,
    unpacker: { unpack: unpack, unpackAll: unpackAll },
    hls: { isMaster: isMasterPlaylist, parseMaster: parseMaster, expand: expandPlaylist },
    dash: { isManifest: isDashManifest, parseManifest: parseDashManifest },
    zip: g.host.zip,
    compress: compressApi,
    jwt: { decode: jwtDecode, verify: jwtVerify },
    subtitles: subtitlesApi,
  };
  g.require = function (name) {
    if (Object.prototype.hasOwnProperty.call(extra, name)) return extra[name];
    if (name === 'date') return dateApi;
    var mod;
    try {
      mod = baseRequire(name);
    } catch (e) {
      throw new Error('Unknown module "' + name + '". Available: entities, url, date, ' + Object.keys(extra).join(', '));
    }
    if (name === 'url') {
      return {
        resolve: mod.resolve,
        absolute: resolveRef,
      };
    }
    return mod;
  };
})();
