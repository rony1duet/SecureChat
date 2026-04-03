package com.securechat.app.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.securechat.app.data.model.Chat
import com.securechat.app.data.model.Message
import com.securechat.app.data.model.UserProfile
import com.securechat.app.data.model.getOptimizedGooglePhotoUrl
import com.securechat.app.data.repository.ChatRepository
import com.securechat.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatWindowScreen(chatId: String, otherUserId: String, onBackClick: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { ChatRepository() }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var viewingImage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val currentUser = repository.getCurrentUser()
    val currentUserId = currentUser?.uid ?: ""
    var otherUser by remember { mutableStateOf<UserProfile?>(null) }

    val listState = rememberLazyListState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val base64 = compressImageToBase64(context, it)
                selectedImageBase64 = base64
            }
        }
    }

    LaunchedEffect(otherUserId) {
        if (otherUserId.isNotEmpty()) {
            repository.getUserProfile(otherUserId).collectLatest { otherUser = it }
        }
    }

    LaunchedEffect(chatId) {
        repository.getMessages(chatId).collectLatest {
            messages = it
            if (it.isNotEmpty()) {
                listState.animateScrollToItem(it.size - 1)
            }
            // Clear unread for this user whenever the thread snapshot updates (including empty chats).
            repository.markMessagesAsSeen(chatId)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(
                modifier = Modifier.shadow(4.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(40.dp)) {
                                AsyncImage(
                                    model = getOptimizedGooglePhotoUrl(otherUser?.photoURL, otherUser?.email),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                                if (otherUser?.status == "online" && otherUser?.showOnlineStatus != false) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .align(Alignment.BottomEnd)
                                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                                            .padding(2.dp)
                                            .background(Color(0xFF22C55E), CircleShape)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = otherUser?.displayName ?: "Loading...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    fontSize = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (otherUser?.status == "online" && otherUser?.showOnlineStatus != false) "Online" else "Offline",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (otherUser?.status == "online" && otherUser?.showOnlineStatus != false) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    },
                    actions = {
                        IconButton(onClick = { /* Handle Audio Call */ }) {
                            Icon(Icons.Outlined.Call, contentDescription = "Call", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { /* Handle Video Call */ }) {
                            Icon(Icons.Outlined.Videocam, contentDescription = "Video Call", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        },
        bottomBar = {
            Column {
                if (selectedImageBase64 != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(60.dp)) {
                                AsyncImage(
                                    model = selectedImageBase64,
                                    contentDescription = "Selected Image",
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                IconButton(
                                    onClick = { selectedImageBase64 = null },
                                    modifier = Modifier.size(20.dp).align(Alignment.TopEnd).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(12.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Image selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (showEmojiPicker) {
                    EmojiPicker(onEmojiSelected = {
                        text += it
                        showEmojiPicker = false
                    })
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 16.dp,
                    modifier = Modifier.navigationBarsPadding().imePadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showEmojiPicker = !showEmojiPicker }) {
                            Icon(
                                if (showEmojiPicker) Icons.Default.Keyboard else Icons.Outlined.SentimentSatisfiedAlt,
                                contentDescription = "Emoji",
                                tint = if (showEmojiPicker) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                            Icon(Icons.Outlined.Image, contentDescription = "Image", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Type a message...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                            shape = RoundedCornerShape(26.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.primary
                            ),
                            maxLines = 4,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        FloatingActionButton(
                            onClick = {
                                if (text.isNotBlank() || selectedImageBase64 != null) {
                                    scope.launch {
                                        repository.sendMessage(chatId, text, selectedImageBase64)
                                        text = ""
                                        selectedImageBase64 = null
                                        showEmojiPicker = false
                                    }
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(22.dp))
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
                .chatBackgroundPattern(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(messages) { index, message ->
                    val isMe = message.senderId == currentUserId
                    val showAvatar = !isMe && (index == 0 || messages[index - 1].senderId != message.senderId)

                    MessageBubble(
                        message = message,
                        isMe = isMe,
                        showAvatar = showAvatar,
                        avatarUrl = otherUser?.photoURL,
                        email = otherUser?.email,
                        otherUserOnline = otherUser?.status == "online",
                        onImageClick = { viewingImage = it }
                    )
                }
            }

            // Fullscreen Image Viewer
            if (viewingImage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.9f))
                        .clickable { viewingImage = null },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = viewingImage,
                        contentDescription = "Fullscreen Image",
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentScale = ContentScale.Fit
                    )
                    IconButton(
                        onClick = { viewingImage = null },
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun EmojiPicker(onEmojiSelected: (String) -> Unit) {
    val emojis = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
        "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
        "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩",
        "🥳", "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣",
        "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬",
        "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗",
        "🤔", "🤭", "🤫", "🤥", "😶", "😐", "😑", "😬", "🙄", "😯",
        "😦", "😧", "😮", "😲", "🥱", "😴", "🤤", "😪", "😵", "🤐",
        "🥴", "🤢", "🤮", "🤧", "😷", "🤒", "🤕", "🤑", "🤠", "😈",
        "👿", "👹", "👺", "🤡", "💩", "👻", "💀", "☠️", "👽", "👾",
        "🤖", "🎃", "🎃", "😺", "😸", "😻", "😼", "😽", "🙀", "😿", "😾"
    )

    Surface(
        modifier = Modifier.fillMaxWidth().height(250.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(40.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(emojis) { emoji ->
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onEmojiSelected(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 24.sp)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: Message,
    isMe: Boolean,
    showAvatar: Boolean = false,
    avatarUrl: String? = null,
    email: String? = null,
    otherUserOnline: Boolean = false,
    onImageClick: (String) -> Unit = {}
) {
    val state = remember {
        MutableTransitionState(false).apply {
            targetState = true
        }
    }

    val isDeleted = message.text == "This message was deleted"

    AnimatedVisibility(
        visibleState = state,
        enter = slideInVertically(initialOffsetY = { 20 }) + fadeIn(),
        exit = fadeOut()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            if (!isMe) {
                Box(modifier = Modifier.size(32.dp)) {
                    if (showAvatar) {
                        AsyncImage(
                            model = getOptimizedGooglePhotoUrl(avatarUrl, email),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Surface(
                color = if (isMe) MaterialTheme.colorScheme.primary else if (isDeleted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = if (isMe) 20.dp else 2.dp,
                    bottomEnd = if (isMe) 2.dp else 20.dp
                ),
                shadowElevation = if (isDeleted) 0.dp else 1.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (message.imageUrl != null && !isDeleted) {
                        AsyncImage(
                            model = message.imageUrl,
                            contentDescription = "Message Image",
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .heightIn(max = 250.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onImageClick(message.imageUrl) },
                            contentScale = ContentScale.Crop
                        )
                        if (message.text.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    if (message.text.isNotBlank() || isDeleted) {
                        Text(
                            text = message.text,
                            style = if (isDeleted) {
                                MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic, fontSize = 15.sp)
                            } else {
                                MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp)
                            },
                            color = if (isMe) MaterialTheme.colorScheme.onPrimary else if (isDeleted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface,
                            lineHeight = 22.sp
                        )
                    }

                    Row(
                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(message.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = (if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                        if (isMe) {
                            Spacer(modifier = Modifier.width(4.dp))
                            val isSeen = message.status == "seen"
                            val isDelivered = otherUserOnline // Simple logic: if online, it's delivered

                            Icon(
                                imageVector = if (isSeen || isDelivered) Icons.Default.DoneAll else Icons.Default.Done,
                                contentDescription = null,
                                tint = if (isSeen) Color(0xFF3B82F6) else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun compressImageToBase64(context: android.content.Context, uri: Uri): String? {
    return withContext(Dispatchers.IO) {
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap == null) return@withContext null

            // Resize
            val maxWidth = 800
            val maxHeight = 800
            var width = originalBitmap.width
            var height = originalBitmap.height

            if (width > height) {
                if (width > maxWidth) {
                    height = (height * maxWidth.toFloat() / width).toInt()
                    width = maxWidth
                }
            } else {
                if (height > maxHeight) {
                    width = (width * maxHeight.toFloat() / height).toInt()
                    height = maxHeight
                }
            }

            val resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, width, height, true)
            val outputStream = ByteArrayOutputStream()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val byteArray = outputStream.toByteArray()

            "data:image/jpeg;base64," + Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

fun formatTime(timestamp: com.google.firebase.Timestamp): String {
    val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
    return sdf.format(timestamp.toDate())
}

fun Modifier.chatBackgroundPattern(color: Color) = this.drawBehind {
    val step = 42.dp.toPx()
    val plusSize = 8.dp.toPx()
    val strokeWidth = 1.dp.toPx()

    for (x in 0..(size.width / step).toInt() + 1) {
        for (y in 0..(size.height / step).toInt() + 1) {
            val px = x * step
            val py = y * step

            drawLine(
                color = color,
                start = Offset(px - plusSize / 2, py),
                end = Offset(px + plusSize / 2, py),
                strokeWidth = strokeWidth
            )
            drawLine(
                color = color,
                start = Offset(px, py - plusSize / 2),
                end = Offset(px, py + plusSize / 2),
                strokeWidth = strokeWidth
            )
        }
    }
}
