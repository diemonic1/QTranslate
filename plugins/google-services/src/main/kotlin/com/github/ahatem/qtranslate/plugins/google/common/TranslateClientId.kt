package com.github.ahatem.qtranslate.plugins.google.common

/**
 * The client identity sent to Google's unofficial `translate_a` endpoints.
 *
 * ### What this parameter is
 * `client` names which Google caller a request claims to be. It is not a key and grants no
 * quota — but Google counts its abuse limits per identity, so which name is used decides whether
 * a request is answered or refused.
 *
 * ### Why not `gtx`
 * `gtx` is the identity the translate.google.com page uses, and it is the one this plugin sent
 * until it stopped working. Google now answers it with `429` and its "Sorry..." block page for
 * whole IP ranges, and the block is not regional: `.com`, `.ru` and `translate.googleapis.com`
 * all refuse it identically, while the same request under this identity is answered in about
 * 300ms. The endpoint, the path and the parameters were never the problem.
 *
 * The cost of the block was not only Google being unavailable. A `429` is retryable, so every
 * translation spent three attempts and two backoff waits — about nine seconds — before the
 * translator gave up and fell back, and the dictionary and spell checker, which have no fallback,
 * simply stopped working.
 *
 * ### What this is not
 * Both identities are undocumented, and neither is a supported way to reach Google Translate.
 * This changes which unofficial door the plugin knocks on; it does not make the endpoint official
 * and it may need changing again. The supported path is a Cloud Translation API key, which
 * `GoogleSettings.translateApiKey` already accepts and which takes precedence when set.
 */
internal const val TRANSLATE_CLIENT_ID: String = "dict-chrome-ex"
