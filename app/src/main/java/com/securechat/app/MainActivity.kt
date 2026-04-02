package com.securechat.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.securechat.app.data.FirebaseConfig
import com.securechat.app.data.repository.ChatRepository
import com.securechat.app.service.CallService
import com.securechat.app.ui.screen.ChatListScreen
import com.securechat.app.ui.screen.ChatWindowScreen
import com.securechat.app.ui.screen.LoginScreen
import com.securechat.app.ui.screen.SettingsScreen
import com.securechat.app.ui.theme.SecureChatTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var auth: FirebaseAuth
    private val repository = ChatRepository()

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(credential).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        val db = FirebaseFirestore.getInstance(FirebaseConfig.FIRESTORE_DATABASE_ID)
                        val userMap = mutableMapOf<String, Any>(
                            "uid" to user.uid,
                            "displayName" to (user.displayName ?: "User"),
                            "displayNameLower" to (user.displayName ?: "User").lowercase(),
                            "email" to (user.email ?: ""),
                            "emailLower" to (user.email ?: "").lowercase(),
                            "photoURL" to (user.photoUrl?.toString() ?: ""),
                            "status" to "online",
                            "lastSeen" to com.google.firebase.Timestamp.now()
                        )

                        db.collection("users").document(user.uid).get().addOnSuccessListener { document ->
                            if (!document.exists()) {
                                userMap["createdAt"] = com.google.firebase.Timestamp.now()
                                userMap["showOnlineStatus"] = true
                                db.collection("users").document(user.uid).set(userMap)
                            } else {
                                db.collection("users").document(user.uid).update(userMap)
                            }
                        }
                        initZego(user.uid, user.displayName ?: "User")
                    }
                }
            }
        } catch (e: ApiException) {
            Log.e("MainActivity", "Google sign in failed", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        auth = FirebaseAuth.getInstance()

        val initialUser = auth.currentUser
        if (initialUser != null) {
            initZego(initialUser.uid, initialUser.displayName ?: "User")
        }

        setContent {
            SecureChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val scope = rememberCoroutineScope()
                    var currentUser by remember { mutableStateOf(auth.currentUser) }

                    // Sync Auth state and navigation
                    DisposableEffect(Unit) {
                        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
                            val newUser = firebaseAuth.currentUser
                            if (currentUser != newUser) {
                                currentUser = newUser
                                if (newUser == null) {
                                    navController.navigate("login") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                } else {
                                    navController.navigate("chatList") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                }
                            }
                        }
                        auth.addAuthStateListener(listener)
                        onDispose { auth.removeAuthStateListener(listener) }
                    }

                    // Handle online status
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner, currentUser) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (currentUser != null) {
                                when (event) {
                                    Lifecycle.Event.ON_RESUME -> scope.launch { repository.updateOnlineStatus(true) }
                                    Lifecycle.Event.ON_PAUSE -> scope.launch { repository.updateOnlineStatus(false) }
                                    else -> {}
                                }
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = if (currentUser != null) "chatList" else "login"
                    ) {
                        composable("login") {
                            LoginScreen(onSignInClick = { email -> signIn(email) })
                        }
                        composable("chatList") {
                            ChatListScreen(
                                onChatClick = { chat ->
                                    val otherUserId = chat.participants.find { it != auth.currentUser?.uid } ?: ""
                                    navController.navigate("chatWindow/${chat.id}/$otherUserId")
                                },
                                onSettingsClick = { navController.navigate("settings") }
                            )
                        }
                        composable(
                            route = "chatWindow/{chatId}/{otherUserId}",
                            arguments = listOf(
                                navArgument("chatId") { type = NavType.StringType },
                                navArgument("otherUserId") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
                            val otherUserId = backStackEntry.arguments?.getString("otherUserId") ?: ""
                            ChatWindowScreen(
                                chatId = chatId,
                                otherUserId = otherUserId,
                                onBackClick = { navController.popBackStack() }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBackClick = { navController.popBackStack() },
                                onLogoutClick = {
                                    scope.launch {
                                        repository.updateOnlineStatus(false)
                                        val gso = getGoogleSignInOptions()
                                        GoogleSignIn.getClient(this@MainActivity, gso).signOut().addOnCompleteListener {
                                            auth.signOut()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun signIn(email: String? = null) {
        val gso = getGoogleSignInOptions(email)
        val googleSignInClient = GoogleSignIn.getClient(this, gso)
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun getGoogleSignInOptions(email: String? = null): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(FirebaseConfig.GOOGLE_WEB_CLIENT_ID)
            .requestEmail()
            .apply { if (email != null) setAccountName(email) }
            .build()
    }

    private fun initZego(uid: String, name: String) {
        CallService.init(
            application = application,
            appID = 494321620,
            appSign = "db8c17a04a07cf02c03c11a8ac3f755b5bffe03eeb7f224d7bc19471ae36c2d9",
            userID = uid,
            userName = name
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        CallService.unInit()
    }
}
