package com.omran.caption

// Session-only. Never save this token in preferences, bundles, logs or URLs.
object PrivateAccess {
    @Volatile private var token: String = ""
    fun valid(value: String): Boolean = value.length in 32..256 &&
        value.all { it.code in 33..126 }
    fun set(value: String): Boolean {
        if (!valid(value)) return false
        token = value
        return true
    }
    fun get(): String = token
    fun clear() { token = "" }
}
