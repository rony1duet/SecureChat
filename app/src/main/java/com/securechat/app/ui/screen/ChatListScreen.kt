package com.securechat.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.securechat.app.data.model.Chat
import com.securechat.app.data.model.UserProfile
import com.securechat.app.data.model.getOptimizedGooglePhotoUrl
import com.securechat.app.data.repository.ChatRepository
import com.securechat.app.ui.theme.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(onChatClick: (Chat) -> Unit, onSettingsClick: () -> Unit) {
    val repository = remember { ChatRepository() }
    var chats by remember { mutableStateOf<List<Chat>>(emptyList()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    var currentUser by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser) }
    var currentUserProfile by remember { mutableStateOf<UserProfile?>(null) }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        FirebaseAuth.getInstance().addAuthStateListener(listener)
        onDispose {
            FirebaseAuth.getInstance().removeAuthStateListener(listener)
        }
    }

    LaunchedEffect(currentUser?.uid) {
        currentUser?.uid?.let { uid ->
            repository.getUserProfile(uid).collectLatest { profile ->
                currentUserProfile = profile
            }
        }
    }

    LaunchedEffect(currentUser?.uid) {
        if (currentUser?.uid != null) {
            repository.getChats().collectLatest { chats = it }
        } else {
            chats = emptyList()
        }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            isSearching = true
            delay(500)
            searchResults = repository.searchUsers(searchQuery)
            isSearching = false
        } else {
            isSearching = false
            searchResults = emptyList()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(48.dp)) {
                            AsyncImage(
                                model = getOptimizedGooglePhotoUrl(currentUserProfile?.photoURL ?: currentUser?.photoUrl?.toString(), currentUser?.email),
                                contentDescription = "Profile",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )

                            val isVisible = currentUserProfile?.showOnlineStatus != false
                            val isOnline = (currentUserProfile?.status == "online") && isVisible

                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .align(Alignment.BottomEnd)
                                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                                    .padding(2.5.dp)
                                    .background(
                                        when {
                                            !isVisible -> Slate400
                                            isOnline -> Color(0xFF22C55E)
                                            else -> Color.Gray
                                        },
                                        CircleShape
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = (currentUserProfile?.displayName ?: currentUser?.displayName ?: "MY PROFILE"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            val isVisible = currentUserProfile?.showOnlineStatus != false
                            val isOnline = (currentUserProfile?.status == "online") && isVisible

                            Text(
                                text = if (isOnline) "ONLINE" else if (!isVisible) "INVISIBLE" else "OFFLINE",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOnline) Color(0xFF22C55E) else Slate500,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = Slate500,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = Slate400,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    focusManager.clearFocus()
                                }
                            ),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Search people...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Slate400
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear search",
                                    tint = Slate400,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (searchQuery.isBlank() && chats.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    if (searchQuery.isNotBlank()) {
                        item {
                            SectionHeader(if (isSearching) "SEARCHING..." else "SEARCH RESULTS")
                        }

                        if (isSearching) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else if (searchResults.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No users found",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Slate400
                                    )
                                }
                            }
                        } else {
                            items(searchResults) { user ->
                                if (user.uid != currentUser?.uid) {
                                    UserListItem(user = user) {
                                        scope.launch {
                                            val chatId = repository.createChat(user.uid)
                                            onChatClick(Chat(id = chatId, participants = listOf(currentUser?.uid ?: "", user.uid)))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        items(chats) { chat ->
                            ChatListItem(
                                chat = chat,
                                currentUserId = currentUser?.uid ?: "",
                                repository = repository
                            ) {
                                onChatClick(chat)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(90.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.Message,
                    contentDescription = null,
                    tint = Slate400.copy(alpha = 0.5f),
                    modifier = Modifier.size(44.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "No chats yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            "Search for a user to start messaging.",
            style = MaterialTheme.typography.bodyMedium,
            color = Slate400,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, start = 48.dp, end = 48.dp),
            lineHeight = 22.sp
        )
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = Slate400,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
fun UserListItem(user: UserProfile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(50.dp)) {
            AsyncImage(
                model = getOptimizedGooglePhotoUrl(user.photoURL, user.email),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )

            val isOnline = user.status == "online" && user.showOnlineStatus == true
            if (isOnline) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .padding(2.dp)
                        .background(Color(0xFF22C55E), CircleShape)
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = user.email,
                style = MaterialTheme.typography.bodySmall,
                color = Slate500
            )
        }
    }
}

@Composable
fun ChatListItem(
    chat: Chat,
    currentUserId: String,
    repository: ChatRepository,
    onClick: () -> Unit
) {
    val otherUserId = chat.participants.find { it != currentUserId } ?: "Unknown"
    var otherUser by remember { mutableStateOf<UserProfile?>(null) }
    var isTyping by remember { mutableStateOf(false) }
    val unreadCount = (chat.unreadCount[currentUserId] ?: 0L).coerceAtLeast(0L)
    val hasUnread = unreadCount > 0L

    LaunchedEffect(otherUserId) {
        repository.getUserProfile(otherUserId).collectLatest { otherUser = it }
    }

    LaunchedEffect(chat.id, otherUserId) {
        repository.getTypingStatus(chat.id, otherUserId).collectLatest { isTyping = it }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(if (hasUnread) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .padding(start = if (hasUnread) 8.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasUnread) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(56.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(99.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
        }

        Box(modifier = Modifier.size(56.dp)) {
            AsyncImage(
                model = getOptimizedGooglePhotoUrl(otherUser?.photoURL, otherUser?.email),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )

            val isOnline = otherUser?.status == "online" && otherUser?.showOnlineStatus == true
            if (isOnline) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.BottomEnd)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .padding(2.5.dp)
                        .background(Color(0xFF22C55E), CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = (otherUser?.displayName ?: otherUserId).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (hasUnread) FontWeight.ExtraBold else FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatTimestampShort(chat.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasUnread) MaterialTheme.colorScheme.primary else Slate500,
                    fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isTyping) {
                    Text(
                        text = "typing...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PrimaryBlue,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (chat.lastMessage?.senderId == currentUserId) {
                            val status = chat.lastMessage.status
                            val isOnline = otherUser?.status == "online"

                            Icon(
                                imageVector = if (status == "seen") Icons.Default.DoneAll else if (isOnline) Icons.Default.DoneAll else Icons.Default.Done,
                                contentDescription = null,
                                tint = if (status == "seen") Color(0xFF3B82F6) else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = chat.lastMessage?.text ?: "No messages yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hasUnread) MaterialTheme.colorScheme.onSurface else Slate500,
                            fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (hasUnread) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatTimestampShort(timestamp: com.google.firebase.Timestamp): String {
    val date = timestamp.toDate()
    val now = java.util.Date()
    val diff = now.time - date.time

    return when {
        diff < 60000 -> "now"
        diff < 3600000 -> "${diff / 60000} minutes"
        diff < 86400000 -> "${diff / 3600000} hours"
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            sdf.format(date)
        }
    }
}
