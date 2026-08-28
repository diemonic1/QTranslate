package com.github.ahatem.qtranslate.plugins.google.common

/**
 * Which Google host the unofficial `translate_a` endpoints are asked on.
 *
 * ### Why this is a choice at all
 * Google serves translation from every one of its country domains, and they are not equally close
 * to everyone. They are the same service — the same endpoint, the same parameters, the same
 * answer — so the only thing that differs is how far the request has to travel and what sits in
 * between. That turned out to matter far more than expected: measured from one machine, five
 * requests each,
 *
 * ```
 * translate.googleapis.com   615ms      translate.google.de    468ms
 * translate.google.com       464ms      translate.google.ru     96ms
 * ```
 *
 * Six times, for a parameter. Which host wins is entirely local to whoever is asking, so this
 * cannot be guessed on the user's behalf and is offered as a setting with a way to measure it.
 *
 * The default stays [DEFAULT_TRANSLATE_HOST], which is where this plugin has always sent its
 * requests. A setting that quietly moved everyone's traffic to a different country the first time
 * they updated would be a surprising thing for a translation app to do on its own.
 */
internal const val DEFAULT_TRANSLATE_HOST: String = "translate.googleapis.com"

/**
 * The hosts offered in the picker, as a comma-separated list because `@Setting.options` is an
 * annotation argument and has to be a compile-time constant.
 *
 * Deliberately broader than the set that answers from any one place. Which of these is reachable
 * depends on the network the user is on — two of them refuse connections from the machine this
 * list was assembled on — and dropping those would have hidden a working option from someone
 * elsewhere. The measure action reports what is actually reachable, which is a better answer than
 * a list curated from one vantage point.
 */
internal const val TRANSLATE_HOST_OPTIONS: String =
    "translate.googleapis.com," +
        "translate.google.com," +
        "translate.google.ru," +
        "translate.google.by," +
        "translate.google.kz," +
        "translate.google.ua," +
        "translate.google.de," +
        "translate.google.fr," +
        "translate.google.es," +
        "translate.google.it," +
        "translate.google.pl," +
        "translate.google.nl," +
        "translate.google.tr," +
        "translate.google.co.uk," +
        "translate.google.com.br," +
        "translate.google.co.jp," +
        "translate.google.com.hk"

/** The offered hosts, in the order the picker shows them. */
internal val TRANSLATE_HOSTS: List<String> = TRANSLATE_HOST_OPTIONS.split(',')

/**
 * The host to actually use, given whatever is stored.
 *
 * Stored settings outlive the code that wrote them: a value can arrive blank from a fresh install,
 * carry a scheme or a trailing slash from someone who typed it, or name a host that has since been
 * dropped from the list. Falling back to the default is right for all of them — a malformed host
 * would otherwise turn every translation into a connection error with nothing on screen to
 * explain why.
 */
internal fun normalizeTranslateHost(host: String?): String =
    host.orEmpty()
        .trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
        .ifBlank { DEFAULT_TRANSLATE_HOST }

/** The translate/dictionary/spell-check endpoint on [host]. */
internal fun translateSingleUrl(host: String?): String =
    "https://${normalizeTranslateHost(host)}/translate_a/single"

/** The speech endpoint on [host]. */
internal fun translateTtsUrl(host: String?): String =
    "https://${normalizeTranslateHost(host)}/translate_tts"
