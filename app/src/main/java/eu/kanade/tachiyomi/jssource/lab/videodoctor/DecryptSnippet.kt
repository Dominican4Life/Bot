package eu.kanade.tachiyomi.jssource.lab.videodoctor

/**
 * KMK (r115): the JS a source uses to read a hidden video address itself, without the hidden browser (the
 * "Decrypt with the key from the page" fix). Only what the stdlib already offers: `require('crypto').aes.decrypt`
 * (the `aes` host call: a passphrase in the OpenSSL / CryptoJS "Salted__" format, or a raw key + iv),
 * `require('base64')` and `require('unpacker')`. No new host function is needed.
 */
internal object DecryptSnippet {

    /**
     * `revealVideoUrl(text, how)`: finds the blob with `how.pattern` (group 1) in [text] (a page or script) and
     * turns it into an address. `how.method`: "aes-passphrase" (`how.key` the passphrase), "aes-key" (`how.key`,
     * `how.iv` in `how.keyEncoding`: "hex" or "utf8"; no iv = zeros), "base64", "base64-reversed" or "packed".
     * Answers the first http(s) address in the result, or null.
     */
    const val HELPER: String = """
// KMK (r115): reads the video address a page hides (written by Source Lab's "Why won't it play")
async function revealVideoUrl(text, how) {
  var src = String(text || '');
  var plain = null;
  if (how.method === 'packed') {
    plain = require('unpacker').unpackAll(src).join('\n');
  } else {
    var m = new RegExp(how.pattern).exec(src);
    if (!m) return null;
    var blob = m[1];
    if (how.method === 'aes-passphrase') {
      plain = await require('crypto').aes.decrypt(blob, { passphrase: how.key });
    } else if (how.method === 'aes-key') {
      var o = { key: how.key, keyEncoding: how.keyEncoding || 'utf8', mode: 'cbc' };
      if (how.iv) {
        o.iv = how.iv;
        o.ivEncoding = how.keyEncoding || 'utf8';
      }
      plain = await require('crypto').aes.decrypt(blob, o);
    } else if (how.method === 'base64-reversed') {
      plain = require('base64').decode(blob.split('').reverse().join(''));
    } else {
      plain = require('base64').decode(blob);
    }
  }
  if (!plain) return null;
  plain = String(plain).replace(/\\\//g, '/');
  var media = /https?:\/\/[^"'\s<>\\]+?\.(?:m3u8|mpd|mp4|mkv|webm)(?:[?#][^"'\s<>\\]*)?/i.exec(plain);
  var any = media || /https?:\/\/[^"'\s<>\\]+/.exec(plain);
  return any ? any[0] : null;
}
"""

    /** The `how` object of [fix] as JS (`{ method: …, pattern: …, key: …, iv: …, keyEncoding: … }`) */
    fun howOf(fix: VideoFix.DecryptWithKey): String = buildString {
        append("{ method: ").append(jsString(fix.method))
        append(", pattern: ").append(jsString(fix.pattern))
        if (fix.key.isNotEmpty()) append(", key: ").append(jsString(fix.key))
        fix.iv?.let { append(", iv: ").append(jsString(it)) }
        if (fix.method == "aes-key") append(", keyEncoding: ").append(jsString(fix.keyEncoding))
        append(" }")
    }

    /**
     * The call a source's video part makes with the page's text in [textVar]:
     * `var url = await revealVideoUrl(html, { … });` (the host adds [HELPER] once to the source's code).
     */
    fun forFix(fix: VideoFix.DecryptWithKey, textVar: String = "html"): String =
        "var url = await revealVideoUrl($textVar, ${howOf(fix)});"

    /** A JS string literal (single quotes) */
    fun jsString(value: String): String = buildString {
        append('\'')
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                ' ' -> append("\\u2028")
                ' ' -> append("\\u2029")
                else -> append(c)
            }
        }
        append('\'')
    }
}
