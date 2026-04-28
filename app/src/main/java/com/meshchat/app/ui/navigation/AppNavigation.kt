package com.meshchat.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.meshchat.app.MeshChatApplication
import com.meshchat.app.ui.screen.ChatScreen
import com.meshchat.app.ui.screen.ConversationsScreen
import com.meshchat.app.ui.screen.NearbyScreen
import com.meshchat.app.ui.screen.OnboardingScreen
import com.meshchat.app.ui.screen.PermissionScreen
import com.meshchat.app.ui.screen.SettingsScreen
import com.meshchat.app.ui.theme.Background
import com.meshchat.app.ui.theme.Primary
import com.meshchat.app.ui.theme.Surface
import com.meshchat.app.ui.theme.TextMuted
import com.meshchat.app.ui.viewmodel.BootstrapDest
import com.meshchat.app.ui.viewmodel.BootstrapViewModel
import com.meshchat.app.ui.viewmodel.ChatViewModel
import com.meshchat.app.ui.viewmodel.ConversationsViewModel
import com.meshchat.app.ui.viewmodel.NearbyViewModel
import com.meshchat.app.ui.viewmodel.OnboardingViewModel
import com.meshchat.app.ui.viewmodel.SettingsViewModel
import kotlinx.serialization.Serializable

// ── Route definitions ────────────────────────────────────────────────────────

@Serializable object Bootstrap
@Serializable object Onboarding
@Serializable object Permissions
@Serializable object Nearby
@Serializable object Conversations
@Serializable object Settings
@Serializable data class Chat(
    val conversationId: String,
    val peerName: String,
    val peerDeviceId: String
)

private data class TabEntry(
    val icon: String,
    val label: String,
    val isSelected: (androidx.navigation.NavDestination?) -> Boolean,
    val navigate: androidx.navigation.NavController.() -> Unit
)

private val tabEntries = listOf(
    TabEntry("[~]", "Nearby",
        isSelected = { it?.hasRoute(Nearby::class) == true },
        navigate   = { navigate(Nearby) { popUpTo(graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } }
    ),
    TabEntry("[>]", "Chats",
        isSelected = { it?.hasRoute(Conversations::class) == true },
        navigate   = { navigate(Conversations) { popUpTo(graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } }
    ),
    TabEntry("[*]", "Settings",
        isSelected = { it?.hasRoute(Settings::class) == true },
        navigate   = { navigate(Settings) { popUpTo(graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } }
    ),
)

// ── Root navigation ──────────────────────────────────────────────────────────

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as MeshChatApplication
    LaunchedEffect(Unit) {
        app.container.meshRuntimeRepository.restorePersistentServiceIfNeeded()
    }

    NavHost(navController = navController, startDestination = Bootstrap) {

        composable<Bootstrap> {
            val vm: BootstrapViewModel = viewModel {
                BootstrapViewModel(app.container.identityRepository, context)
            }
            val dest by vm.dest.collectAsState()
            LaunchedEffect(dest) {
                when (dest) {
                    BootstrapDest.ONBOARDING  -> navController.navigate(Onboarding)   { popUpTo(Bootstrap) { inclusive = true } }
                    BootstrapDest.PERMISSIONS -> navController.navigate(Permissions)  { popUpTo(Bootstrap) { inclusive = true } }
                    BootstrapDest.NEARBY      -> navController.navigate(Nearby)       { popUpTo(Bootstrap) { inclusive = true } }
                    BootstrapDest.PENDING     -> Unit
                }
            }
        }

        composable<Onboarding> {
            val vm: OnboardingViewModel = viewModel {
                OnboardingViewModel(app.container.identityRepository)
            }
            OnboardingScreen(vm = vm, onDone = {
                // Always go through the permission flow after completing onboarding.
                navController.navigate(Permissions) {
                    popUpTo(Onboarding) { inclusive = true }
                }
            })
        }

        composable<Permissions> {
            PermissionScreen(onDone = {
                navController.navigate(Nearby) {
                    popUpTo(Permissions) { inclusive = true }
                }
            })
        }

        // Main tabs share a single Scaffold with bottom nav
        composable<Nearby> {
            MainScaffold(currentRoute = it.destination, navController = navController) {
                val vm: NearbyViewModel = viewModel {
                    NearbyViewModel(
                        app.container.meshRepository,
                        app.container.meshRuntimeRepository
                    )
                }
                NearbyScreen(vm = vm, onOpenChat = { conv ->
                    navController.navigate(Chat(conv.id, conv.peerDisplayName, conv.peerDeviceId))
                })
            }
        }

        composable<Conversations> {
            MainScaffold(currentRoute = it.destination, navController = navController) {
                val vm: ConversationsViewModel = viewModel {
                    ConversationsViewModel(app.container.conversationRepository)
                }
                ConversationsScreen(vm = vm, onOpenChat = { conv ->
                    navController.navigate(Chat(conv.id, conv.peerDisplayName, conv.peerDeviceId))
                })
            }
        }

        composable<Settings> {
            MainScaffold(currentRoute = it.destination, navController = navController) {
                val vm: SettingsViewModel = viewModel {
                    SettingsViewModel(
                        app.container.identityRepository,
                        app.container.meshRuntimeRepository
                    )
                }
                SettingsScreen(vm = vm)
            }
        }

        composable<Chat> { backStackEntry ->
            val route = backStackEntry.toRoute<Chat>()
            val vm: ChatViewModel = viewModel {
                ChatViewModel(
                    conversationId        = route.conversationId,
                    peerDeviceId          = route.peerDeviceId,
                    peerDisplayName       = route.peerName,
                    identityRepo          = app.container.identityRepository,
                    conversationRepo      = app.container.conversationRepository,
                    meshRuntimeRepository = app.container.meshRuntimeRepository,
                    meshRouter            = app.container.meshRouter
                )
            }
            ChatScreen(
                vm       = vm,
                peerName = route.peerName,
                onBack   = { navController.popBackStack() }
            )
        }
    }
}

// ── Bottom nav scaffold shared across tab destinations ───────────────────────

@Composable
private fun MainScaffold(
    currentRoute: androidx.navigation.NavDestination?,
    navController: androidx.navigation.NavController,
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = Background,
        bottomBar = {
            NavigationBar(containerColor = Surface) {
                tabEntries.forEach { entry ->
                    val selected = entry.isSelected(currentRoute)
                    NavigationBarItem(
                        selected = selected,
                        onClick  = { entry.navigate(navController) },
                        icon     = { Text(entry.icon, color = if (selected) Primary else TextMuted) },
                        label    = { Text(entry.label) },
                        colors   = NavigationBarItemDefaults.colors(
                            selectedTextColor   = Primary,
                            unselectedTextColor = TextMuted,
                            indicatorColor      = Background
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        androidx.compose.foundation.layout.Box(modifier = Modifier.padding(innerPadding)) {
            content()
        }
    }
}
