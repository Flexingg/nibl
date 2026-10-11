package com.bytelog.app.data.remote

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Marks a request as made on behalf of a family member (Sparky's "active user" context). */
data class ActingAs(val userId: String)

/**
 * Someone whose data the signed-in user can see on Sparky through Family Access. [permissions] are the flags the
 * member granted (can_manage_diary, can_view_reports, ...); [accessEnds] is the end date of a time-limited grant.
 */
data class FamilyMember(
    val userId: String,
    val name: String,
    val email: String?,
    val permissions: Map<String, Boolean>,
    val accessEnds: String? = null
) {
    /** What to call them: their name, else the start of their email. */
    val displayName: String get() = name.ifBlank { email?.substringBefore('@').orEmpty() }.ifBlank { "Family member" }
    val initial: String get() = displayName.trim().firstOrNull()?.uppercase() ?: "?"

    /** Can add and change things in their diary (needed to share a meal with them). */
    val canWriteDiary: Boolean get() = permissions["can_manage_diary"] == true

    /** Can read their diary. Sparky allows it for diary management or report viewing. */
    val canViewDiary: Boolean get() = canWriteDiary || permissions["can_view_reports"] == true

    companion object {
        fun parse(obj: JsonObject): FamilyMember? {
            fun str(k: String) = (obj[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
            // Sparky names the id user_id in this list (owner_user_id in the raw access table)
            val id = str("user_id") ?: str("owner_user_id") ?: return null
            val perms = ((obj["permissions"] ?: obj["access_permissions"]) as? JsonObject)
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.booleanOrNull?.let { k to it } }
                ?.toMap().orEmpty()
            return FamilyMember(id, str("full_name").orEmpty(), str("email"), perms, str("access_end_date"))
        }
    }
}
