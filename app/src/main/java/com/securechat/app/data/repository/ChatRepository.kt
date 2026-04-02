package com.securechat.app.data.repository

import android.util.Log
import com.securechat.app.data.FirebaseConfig
import com.securechat.app.data.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ChatRepository {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance(FirebaseConfig.FIRESTORE_DATABASE_ID)

    fun getCurrentUser() = auth.currentUser

    fun getChats(): Flow<List<Chat>> = callbackFlow {
        val userId = auth.currentUser?.uid ?: return@callbackFlow
        val listener = db.collection("chats")
            .whereArrayContains("participants", userId)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ChatRepository", "Error fetching chats", error)
                    return@addSnapshotListener
                }
                val chats = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(Chat::class.java)?.copy(id = doc.id)
                } ?: emptyList()
                trySend(chats)
            }
        awaitClose { listener.remove() }
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
                "updatedAt" to timestamp
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
        val chatRef = db.collection("chats").document(chatId)
        
        try {
            val chatDoc = chatRef.get().await()
            if (!chatDoc.exists()) return@withContext
            
            val participants = chatDoc.get("participants") as? List<String> ?: emptyList()
            val otherUserId = participants.find { it != userId } ?: return@withContext
            
            val unreadCountMap = chatDoc.get("unreadCount") as? Map<*, *>
            val unreadCount = (unreadCountMap?.get(userId) as? Number)?.toLong() ?: 0L
            
            val batch = db.batch()
            var needsUpdate = false
            
            // 1. Reset unread count for current user if it's > 0
            if (unreadCount > 0) {
                batch.update(chatRef, "unreadCount.$userId", 0L)
                needsUpdate = true
            }

            // 2. Mark unread messages from other user as seen
            val unreadMessages = chatRef.collection("messages")
                .whereEqualTo("senderId", otherUserId)
                .whereEqualTo("status", "sent")
                .get().await()

            if (!unreadMessages.isEmpty) {
                unreadMessages.documents.forEach { doc ->
                    batch.update(doc.reference, "status", "seen")
                }
                needsUpdate = true
            }
            
            // 3. Update lastMessage status if it was from other user and still "sent"
            val lastMsg = chatDoc.get("lastMessage") as? Map<*, *>
            if (lastMsg != null && lastMsg["senderId"] == otherUserId && lastMsg["status"] == "sent") {
                batch.update(chatRef, "lastMessage.status", "seen")
                needsUpdate = true
            }

            if (needsUpdate) {
                batch.commit().await()
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
