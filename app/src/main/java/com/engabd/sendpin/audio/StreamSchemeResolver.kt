package com.engabd.sendpin.audio

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource

/**
 * The player-side half of [StreamSchemes]: a media3 [ResolvingDataSource.Resolver]
 * that rewrites `scheme://type/id` uris to real urls at open time.
 *
 * This file exists so [StreamSchemes] itself stays a pure JVM registry — unit-tested
 * without an android.net.Uri anywhere near it. The resolver is Android-glue and is
 * verified by compilation and device smoke, exactly like the player construction it
 * plugs into.
 *
 * Only uris whose scheme has a registered handler are rewritten; every other uri
 * (https to Navidrome, file from Downloads, content from Local) passes through
 * untouched, so this wraps the data source of **every** local player without any
 * behaviour change for the sources that answer with ordinary urls.
 *
 * `ResolvingDataSource` calls the resolver on its loading thread when a stream is
 * opened, which is the one moment per track this can afford a blocking round-trip:
 * the handler runs under [StreamSchemes.resolve]'s Dispatchers.IO hop.
 */
object StreamSchemeResolver {

    /** The uri schemes this phone plays natively without resolving. */
    private val PASS_THROUGH = setOf("http", "https", "file", "content")

    /**
     * The resolver wired into every local player's data source factory.
     *
     * A scheme uri is written `scheme://type/id` — `qobuz://track/123` reads as
     * type "track", id "123". Only the id reaches the handler today (a Qobuz id is
     * a Qobuz id whatever the type), but the type travels in the uri so a resolver
     * that someday needs it — Tidal video, say — has it without a format change.
     */
    val resolver = ResolvingDataSource.Resolver { dataSpec ->
        val uri = dataSpec.uri
        val scheme = uri.scheme
        if (scheme == null || scheme in PASS_THROUGH || !StreamSchemes.knows(scheme)) {
            dataSpec
        } else {
            val id = uri.lastPathSegment ?: uri.host ?: ""
            val resolved = kotlinx.coroutines.runBlocking {
                StreamSchemes.resolve(scheme, id)
            }
            dataSpec.buildUpon().setUri(android.net.Uri.parse(resolved)).build()
        }
    }

    /**
     * Wrap [base] so scheme uris resolve at open time. Both players —
     * `LocalPlayer` and the crossfade deck — build their factories through this,
     * so a scheme uri queues identically whichever path starts it.
     */
    fun factory(base: DataSource.Factory): ResolvingDataSource.Factory =
        ResolvingDataSource.Factory(base, resolver)
}
