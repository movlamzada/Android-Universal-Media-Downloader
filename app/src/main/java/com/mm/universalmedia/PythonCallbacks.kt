package com.mm.universalmedia

/** Public JVM methods are intentionally simple for Chaquopy's Java-object bridge. */
interface PythonProgressCallback {
    fun onProgress(percent: Double, stage: String, detail: String)
    fun isCancelled(): Boolean
}
