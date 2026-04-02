package com.securechat.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName

@IgnoreExtraProperties
data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val displayNameLower: String = "",
    val emailLower: String = "",
    @get:PropertyName("photoURL")
    @set:PropertyName("photoURL")
    var photoURL: String = "",
    val status: String = "offline",
    val lastSeen: Timestamp = Timestamp.now(),
    val showOnlineStatus: Boolean = true,
    val createdAt: Timestamp = Timestamp.now()
)

data class Chat(
    val id: String = "",
    val participants: List<String> = emptyList(),
    val lastMessage: LastMessage? = null,
    val lastMessageId: String = "",
    val unreadCount: Map<String, Long> = emptyMap(),
    val updatedAt: Timestamp = Timestamp.now(),
    val createdAt: Timestamp = Timestamp.now()
)

data class LastMessage(
    val text: String = "",
    val senderId: String = "",
    val timestamp: Timestamp = Timestamp.now(),
    val status: String = "sent"
)

data class Message(
    val id: String = "",
    val text: String = "",
    val senderId: String = "",
    val timestamp: Timestamp = Timestamp.now(),
    val status: String = "sent",
    val imageUrl: String? = null
)

data class TypingStatus(
    @get:PropertyName("isTyping")
    @set:PropertyName("isTyping")
    var isTyping: Boolean = false,
    val updatedAt: Timestamp = Timestamp.now()
)

fun getOptimizedGooglePhotoUrl(url: String?, email: String? = null, size: Int = 256): String {
    if (url.isNullOrBlank() && email.isNullOrBlank()) return ""

    val photoUrl = if (!url.isNullOrBlank()) url else "https://www.google.com/s2/photos/profile/$email?sz=$size"

    return when {
        photoUrl.contains("googleusercontent.com") -> {
            if (photoUrl.contains("=s")) {
                photoUrl.replace(Regex("=s\\d+(-c)?"), "=s$size-c")
            } else {
                "$photoUrl=s$size-c"
            }
        }
        photoUrl.contains("google.com/s2/photos") -> {
            if (photoUrl.contains("sz=")) {
                photoUrl.replace(Regex("sz=\\d+"), "sz=$size")
            } else {
                val separator = if (photoUrl.contains("?")) "&" else "?"
                "$photoUrl${separator}sz=$size"
            }
        }
        else -> photoUrl
    }
}
