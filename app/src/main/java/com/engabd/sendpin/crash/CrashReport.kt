package com.engabd.sendpin.crash

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One recorded crash. Kept small so the local store doesn't grow; the full stack
 * is enough to identify the site, and the [DebugBundle] it is written into carries
 * the context around it.
 */
@Serializable
data class CrashReport(
    /** ISO-8601 timestamp of when the crash happened. */
    val time: String,
    /** Exception class name, e.g. "java.lang.NullPointerException". */
    @SerialName("exception_class") val exceptionClass: String,
    /** The exception message, if any. */
    val message: String? = null,
    /** The exception's stack trace as plain text. */
    @SerialName("stack_trace") val stackTrace: String,
    /** Thread that threw the exception. */
    val thread: String,
    /** App versionCode at the time of the crash. */
    @SerialName("version_code") val versionCode: Int,
    /** App versionName at the time of the crash. */
    @SerialName("version_name") val versionName: String,
    /** Android API level (e.g. 36). */
    @SerialName("api_level") val apiLevel: Int,
    /** Device model / manufacturer summary. */
    val device: String,
    /** True if this crash has already been reported by the user. */
    val reported: Boolean = false,
)
