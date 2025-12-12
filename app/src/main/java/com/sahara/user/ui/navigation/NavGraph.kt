package com.sahara.user.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sahara.user.ui.screens.ChatScreen
import com.sahara.user.ui.screens.HomeScreen
import com.sahara.user.ui.screens.LoginScreen
import com.sahara.user.ui.screens.ProfileScreen
import com.sahara.user.ui.screens.ThinkingScreen

/**
 * Navigation Routes
 */
object Routes {
    const val HOME = "home"
    const val LOGIN = "login"
    const val CHAT = "chat" // Thinking/Chat Screen
    const val THINKING = "thinking"
    const val PROFILE = "profile"
}

/**
 * Main Navigation Graph
 */
@Composable
fun SaharaUserNavGraph(
    navController: NavHostController = rememberNavController(),
    // Hoist ViewModel to share state between Home and Chat/Thinking
    sharedViewModel: com.sahara.user.ui.viewmodel.ChatViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToProfile = {
                    navController.navigate(Routes.PROFILE)
                },
                onNavigateToChat = {
                    navController.navigate(Routes.THINKING)
                },
                viewModel = sharedViewModel
            )
        }
        
        composable(Routes.THINKING) {
            ThinkingScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                viewModel = sharedViewModel
            )
        }

        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }
        
        composable(Routes.CHAT) {
            ChatScreen(
                onNavigateToProfile = {
                    navController.navigate(Routes.PROFILE)
                }
            )
        }
        
        composable(Routes.PROFILE) {
            ProfileScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
    }
}
