@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.navigation.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

@Composable
fun rememberNavController(): NavHostController = remember { NavHostController() }

@Composable
fun NavHostController.currentBackStackEntryAsState(): State<NavBackStackEntry?> =
    remember { mutableStateOf<NavBackStackEntry?>(null) }

@Composable
fun NavHost(
    navController: NavHostController,
    startDestination: String,
    builder: NavGraphBuilder.() -> Unit
) = Unit

fun NavGraphBuilder.composable(
    route: String,
    content: @Composable (NavBackStackEntry) -> Unit
) = Unit
