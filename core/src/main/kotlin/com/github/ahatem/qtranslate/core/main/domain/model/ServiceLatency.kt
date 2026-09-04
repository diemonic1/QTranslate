package com.github.ahatem.qtranslate.core.main.domain.model

/**
 * How a translation service answered the probe the main window sends it on startup.
 *
 * The picker lists every translator a plugin registered, and until now they were
 * indistinguishable: one that answers in a fifth of a second and one that has been rate-limited
 * into uselessness look exactly alike, and the only way to tell them apart was to translate
 * something and wait. This is that experiment run once, up front, so the list itself carries the
 * answer.
 *
 * A service is measured, not merely timed: a fast reply that translates the probe word wrongly is
 * [Failed], because a number beside it would recommend a service that does not work.
 */
sealed interface ServiceLatency {

    /** The probe is in flight. */
    data object Probing : ServiceLatency

    /**
     * The service returned the expected translation, in [millis] milliseconds.
     *
     * Measured from just before the request is handed to the plugin until its reply is back, so it
     * covers the plugin's own overhead as well as the network. That is deliberate — it is the wait
     * the user would actually experience.
     */
    data class Answered(val millis: Long) : ServiceLatency

    /**
     * The service was still silent when the deadline passed.
     *
     * Kept apart from [Failed] because the two say different things to someone choosing a
     * service. A failure is a verdict — this service will not translate your text. A timeout is
     * only the end of our patience: the service may be reachable and merely slow, or briefly
     * overloaded, and it is worth trying again. Reporting both as "translation failed" told the
     * user a working-but-slow service was broken.
     */
    data object TimedOut : ServiceLatency

    /**
     * The service refused the request, errored, or answered with something other than the
     * expected translation.
     *
     * Those three stay together on purpose: they differ in cause but not in consequence, and each
     * one means this service will not translate the next thing the user types. A timeout is the
     * one outcome that does not mean that, which is why it is [TimedOut] instead.
     */
    data object Failed : ServiceLatency
}
