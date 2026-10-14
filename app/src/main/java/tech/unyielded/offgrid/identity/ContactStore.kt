package tech.unyielded.offgrid.identity

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class ContactStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Contact(
        val shortId: String,
        val displayName: String,
        val addedAt: Long = System.currentTimeMillis(),
        val verified: Boolean = false,
        val lastSeenName: String? = null
    )

    fun getAll(): List<Contact> {
        val raw = prefs.getString(KEY_CONTACTS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                Contact(
                    shortId = obj.getString("shortId"),
                    displayName = obj.getString("displayName"),
                    addedAt = obj.optLong("addedAt", 0L),
                    verified = obj.optBoolean("verified", false),
                    lastSeenName = obj.optString("lastSeenName", null)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(contact: Contact): Boolean {
        val current = getAll().toMutableList()
        if (current.any { it.shortId.equals(contact.shortId, ignoreCase = true) }) {
            return false
        }
        current.add(contact)
        save(current)
        return true
    }

    fun remove(shortId: String) {
        val current = getAll().filterNot { it.shortId.equals(shortId, ignoreCase = true) }
        save(current)
    }

    fun find(shortId: String): Contact? {
        return getAll().find { it.shortId.equals(shortId, ignoreCase = true) }
    }

    fun markVerified(shortId: String) {
        val current = getAll().map {
            if (it.shortId.equals(shortId, ignoreCase = true)) it.copy(verified = true) else it
        }
        save(current)
    }

    private fun save(list: List<Contact>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("shortId", c.shortId)
                put("displayName", c.displayName)
                put("addedAt", c.addedAt)
                put("verified", c.verified)
                put("lastSeenName", c.lastSeenName)
            })
        }
        prefs.edit().putString(KEY_CONTACTS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "offgrid_contacts_v4"
        private const val KEY_CONTACTS = "contacts"
    }
}
