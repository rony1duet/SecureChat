package com.securechat.app.data.repository

import android.util.Log
import com.securechat.app.data.FirebaseConfig
import com.securechat.app.data.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Date

class ChatRepository {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance(FirebaseConfig.FIRESTORE_DATABASE_ID)

    fun getCurrentUser() = auth.currentUser

    fun getChats(): Flow<List<Chat>> = callbackFlow {
        val userId = auth.currentUser?.uid ?: return@callbackFlow
        Log.d("ChatRepository", "Fetching chats for user: $userId")
        val listener = db.collection("chats")
            .whereArrayContains("participants", userId)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ChatRepository", "Error fetching chats", error)
                    return@addSnapshotListener
                }
                Log.d("ChatRepository", "Received ${snapshot?.size()} chat documents")
                launch {
                    val chats = snapshot?.documents?.map { doc ->
                        async { doc.toChat(userId) }
                    }?.awaitAll()?.filterNotNull() ?: emptyList()
                    Log.d("ChatRepository", "Emitting ${chats.size} chats")
                    trySend(chats)
                }
            }
        awaitClose { listener.remove() }
    }

    private suspend fun DocumentSnapshot.toChat(userId: String): Chat? {
        val participants = get("participants") as? List<*> ?: return null
        val participantIds = participants.mapNotNull { it as? String }
        if (participantIds.isEmpty()) return null

        val unreadCountRaw = get("unreadCount") as? Map<*, *> ?: emptyMap<Any, Any>()
        val parsedUnreadCount = unreadCountRaw.entries
            .mapNotNull { (key, value) ->
                val userKey = key as? String ?: return@mapNotNull null
                val count = (value as? Number)?.toLong() ?: 0L
                userKey to count
            }
            .toMap()

        // Web Sidebar parity: unread = messages from others with status !== "seen" (see React reduce on messages).
        val resolvedUnreadForUser = countUnreadMessagesLikeWeb(chatId = id, userId = userId)

        Log.d("ChatRepository", "Chat $id: unread for $userId = $resolvedUnreadForUser (web-parity); map was ${parsedUnreadCount[userId]}")

        val mapVal = parsedUnreadCount[userId]
        if (mapVal == null || mapVal != resolvedUnreadForUser) {
            db.collection("chats").document(id).update("unreadCount.$userId", resolvedUnreadForUser)
        }

        val resolvedUnreadCount = parsedUnreadCount + (userId to resolvedUnreadForUser)

        return Chat(
            id = id,
            participants = participantIds,
            lastMessage = parseLastMessage(get("lastMessage") as? Map<*, *>),
            lastMessageId = getString("lastMessageId") ?: "",
            unreadCount = resolvedUnreadCount,
            updatedAt = parseFlexibleTimestamp(get("updatedAt")) ?: Timestamp.now(),
            createdAt = parseFlexibleTimestamp(get("createdAt")) ?: Timestamp.now()
        )
    }

    /**
     * Matches web `Sidebar` chatMeta: reduce over all message docs where
     * `senderId !== currentUser && message.status !== 'seen'`.
     */
    private suspend fun countUnreadMessagesLikeWeb(chatId: String, userId: String): Long {
        return try {
            val snapshot = db.collection("chats")
                .document(chatId)
                .collection("messages")
                .get()
                .await()
            snapshot.documents.count { doc ->
                val senderId = doc.getString("senderId")
                val status = doc.getString("status")
                senderId != null && senderId != userId && status != "seen"
            }.toLong()
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error counting unread messages (web parity)", e)
            0L
        }
    }

    private fun parseLastMessage(raw: Map<*, *>?): LastMessage? {
        if (raw == null) return null

        val text = raw["text"] as? String ?: ""
        val senderId = raw["senderId"] as? String ?: ""
        val status = raw["status"] as? String ?: "sent"
        val timestamp = parseFlexibleTimestamp(raw["timestamp"]) ?: Timestamp.now()

        if (text.isBlank() && senderId.isBlank()) return null

        return LastMessage(
            text = text,
            senderId = senderId,
            timestamp = timestamp,
            status = status
        )
    }

    /** Firestore map values are often [Long] or [Integer]; never use `as? Long` alone. */
    private fun parseFirestoreLong(value: Any?): Long =
        (value as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L

    private fun parseFlexibleTimestamp(value: Any?): Timestamp? {
        return when (value) {
            is Timestamp -> value
            is Date -> Timestamp(value)
            is Number -> Timestamp(Date(value.toLong()))
            is String -> {
                runCatching { Timestamp(Date(Instant.parse(value).toEpochMilli())) }.getOrNull()
            }
            else -> null
        }
    }

    fun getMessages(chatId: String): Flow<List<Message>> = callbackFlow {
        val listener = db.collection("chats").document(chatId).collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ChatRepository", "Error fetching messages", error)
                    return@addSnapshotListener
                }
                val messages = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(Message::class.java)?.copy(id = doc.id)
                } ?: emptyList()
                trySend(messages)
            }
        awaitClose { listener.remove() }
    }

    suspend fun sendMessage(chatId: String, text: String, imageUrl: String? = null) = withContext(Dispatchers.IO + NonCancellable) {
        val userId = auth.currentUser?.uid ?: return@withContext
        val chatRef = db.collection("chats").document(chatId)

        try {
            val chatDoc = chatRef.get().await()
            val participants = chatDoc.get("participants") as? List<String> ?: emptyList()
            val otherUserId = participants.find { it != userId } ?: ""

            val messageRef = chatRef.collection("messages").document()
            val messageId = messageRef.id
            val timestamp = com.google.firebase.Timestamp.now()

            val messageData = hashMapOf(
                "id" to messageId,
                "text" to text,
                "senderId" to userId,
                "timestamp" to timestamp,
                "status" to "sent"
            )
            if (imageUrl != null) messageData["imageUrl"] = imageUrl

            val lastMessageData = hashMapOf(
                "text" to (imageUrl?.let { "📷 Image" } ?: text),
                "senderId" to userId,
                "timestamp" to timestamp,
                "status" to "sent"
            )

            val batch = db.batch()
            batch.set(messageRef, messageData)

            val updateData = mutableMapOf<String, Any>(
                "lastMessage" to lastMessageData,
                "lastMessageId" to messageId,
                "updatedAt" to timestamp,
                "lastReadAt.$userId" to timestamp,
                "unreadCount.$userId" to 0L
            )

            if (otherUserId.isNotEmpty()) {
                updateData["unreadCount.$otherUserId"] = FieldValue.increment(1)
            }

            batch.update(chatRef, updateData)
            batch.commit().await()
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error sending message", e)
        }
    }

    suspend fun markMessagesAsSeen(chatId: String) = withContext(Dispatchers.IO + NonCancellable) {
        val userId = auth.currentUser?.uid ?: return@withContext
        Log.d("ChatRepository", "Marking messages as seen for chat $chatId, user $userId")
        val chatRef = db.collection("chats").document(chatId)

        try {
            val chatDoc = chatRef.get().await()
            if (!chatDoc.exists()) {
                Log.d("ChatRepository", "Chat $chatId does not exist")
                return@withContext
            }

            val participants = chatDoc.get("participants") as? List<String> ?: emptyList()
            val otherUserId = participants.find { it != userId } ?: return@withContext

            val unreadMap = chatDoc.get("unreadCount") as? Map<*, *>
            val unreadFromMap = parseFirestoreLong(unreadMap?.get(userId))

            val previousLastReadAt = parseFlexibleTimestamp((chatDoc.get("lastReadAt") as? Map<*, *>)?.get(userId))
                ?: parseFlexibleTimestamp(chatDoc.get("createdAt"))
                ?: Timestamp(Date(0L))

            val lastMsg = chatDoc.get("lastMessage") as? Map<*, *>
            val lastMsgTimestamp = parseFlexibleTimestamp(lastMsg?.get("timestamp"))
            val lastMessageFromOtherUnseen = lastMsg != null &&
                lastMsg["senderId"] == otherUserId &&
                lastMsgTimestamp != null &&
                lastMsgTimestamp > previousLastReadAt &&
                (lastMsg["status"] as? String ?: "sent") != "seen"

            // Same definition as web Sidebar + countUnreadMessagesLikeWeb: any message from other not yet "seen".
            val messagesFromOther = chatRef.collection("messages")
                .whereEqualTo("senderId", otherUserId)
                .get().await()
            val pendingFromOther = messagesFromOther.documents.count { doc ->
                (doc.getString("status") ?: "sent") != "seen"
            }

            val shouldFullMark =
                unreadFromMap > 0L || lastMessageFromOtherUnseen || pendingFromOther > 0
            if (!shouldFullMark) {
                Log.d("ChatRepository", "Chat $chatId already seen by $userId (unread=$unreadFromMap)")
                chatRef.update("lastReadAt.$userId", Timestamp.now())
                return@withContext
            }
            val seenAt = Timestamp.now()

            Log.d("ChatRepository", "Updating unreadCount to 0 for $userId in chat $chatId")
            val batch = db.batch()
            var needsUpdate = false

            // 1. Persist read marker and reset unread counter for compatibility with old/new docs.
            batch.update(chatRef, mapOf(
                "lastReadAt.$userId" to seenAt,
                "unreadCount.$userId" to 0L
            ))
            needsUpdate = true

            // 2. Mark every message from the other user that is not already "seen" (web parity; no lastReadAt cutoff).
            if (messagesFromOther.documents.isNotEmpty()) {
                messagesFromOther.documents.forEach { doc ->
                    if ((doc.getString("status") ?: "sent") != "seen") {
                        batch.update(doc.reference, "status", "seen")
                    }
                }
                needsUpdate = true
            }

            // 3. Update embedded lastMessage status when it was from the other user and still unread.
            if (lastMessageFromOtherUnseen) {
                batch.update(chatRef, "lastMessage.status", "seen")
                needsUpdate = true
            }

            if (needsUpdate) {
                batch.commit().await()
                Log.d("ChatRepository", "Successfully marked messages as seen for chat $chatId")
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error marking messages as seen", e)
        }
    }

    suspend fun searchUsers(searchQuery: String): List<UserProfile> {
        if (searchQuery.isBlank()) return emptyList()
        val userId = auth.currentUser?.uid ?: return emptyList()
        val normalized = searchQuery.trim().lowercase()

        return try {
            val nameQuery = db.collection("users")
                .orderBy("displayNameLower")
                .startAt(normalized)
                .endAt(normalized + "\uf8ff")
                .limit(20)
                .get().await()

            val emailQuery = db.collection("users")
                .orderBy("emailLower")
                .startAt(normalized)
                .endAt(normalized + "\uf8ff")
                .limit(20)
                .get().await()

            val uidQuery = db.collection("users")
                .whereEqualTo("uid", searchQuery.trim())
                .limit(1)
                .get().await()

            val recentQuery = db.collection("users")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(100)
                .get().await()

            val resultsMap = mutableMapOf<String, UserProfile>()
            val queryTokens = normalized.split(Regex("[^a-z0-9]")).filter { it.length >= 2 }

            (nameQuery.documents + emailQuery.documents + uidQuery.documents + recentQuery.documents).forEach { doc ->
                val profile = doc.toObject(UserProfile::class.java)?.copy(uid = doc.id)
                if (profile != null && profile.uid != userId) {
                    val nameLower = profile.displayNameLower.ifEmpty { profile.displayName.lowercase() }
                    val emailLower = profile.emailLower.ifEmpty { profile.email.lowercase() }

                    val profileKeywords = (nameLower.split(Regex("[^a-z0-9]")) +
                            emailLower.split("@")[0].split(Regex("[^a-z0-9]")))
                        .filter { it.length >= 2 }

                    val isMatch = profile.uid == searchQuery.trim() ||
                            nameLower.contains(normalized) ||
                            emailLower.contains(normalized) ||
                            profileKeywords.any { keyword -> normalized.contains(keyword) } ||
                            queryTokens.any { qToken -> profileKeywords.any { pKeyword -> pKeyword.contains(qToken) } }

                    if (isMatch) {
                        resultsMap[profile.uid] = profile
                    }
                }
            }

            resultsMap.values.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun createChat(otherUserId: String): String {
        val userId = auth.currentUser?.uid ?: throw Exception("Not authenticated")

        val existing = db.collection("chats")
            .whereArrayContains("participants", userId)
            .get().await()
            .documents.find {
                val participants = it.get("participants") as? List<String>
                participants?.contains(otherUserId) == true && participants.size == 2
            }

        if (existing != null) return existing.id

        val chatData = hashMapOf(
            "participants" to listOf(userId, otherUserId),
            "unreadCount" to mapOf(userId to 0L, otherUserId to 0L),
            "lastReadAt" to mapOf(userId to com.google.firebase.Timestamp.now(), otherUserId to com.google.firebase.Timestamp.now()),
            "createdAt" to com.google.firebase.Timestamp.now(),
            "updatedAt" to com.google.firebase.Timestamp.now()
        )
        return db.collection("chats").add(chatData).await().id
    }

    suspend fun updateOnlineStatus(isOnline: Boolean) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).update(
            "status", if (isOnline) "online" else "offline",
            "lastSeen", com.google.firebase.Timestamp.now()
        ).await()
    }

    suspend fun updateShowOnlineStatus(show: Boolean) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).update(
            "showOnlineStatus", show
        ).await()
    }

    fun getUserProfile(userId: String): Flow<UserProfile?> = callbackFlow {
        val listener = db.collection("users").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                trySend(snapshot?.toObject(UserProfile::class.java)?.copy(uid = snapshot.id))
            }
        awaitClose { listener.remove() }
    }

    fun getTypingStatus(chatId: String, userId: String): Flow<Boolean> = callbackFlow {
        val listener = db.collection("chats").document(chatId).collection("typing").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                val status = snapshot?.toObject(TypingStatus::class.java)
                trySend(status?.isTyping ?: false)
            }
        awaitClose { listener.remove() }
    }

    suspend fun setTypingStatus(chatId: String, isTyping: Boolean) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("chats").document(chatId).collection("typing").document(userId).set(
            hashMapOf(
                "isTyping" to isTyping,
                "updatedAt" to com.google.firebase.Timestamp.now()
            )
        ).await()
    }
}
