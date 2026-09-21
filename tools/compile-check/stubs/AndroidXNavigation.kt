@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.navigation

open class NavDestination {
    val route: String? = null
}

open class NavBackStackEntry {
    val destination: NavDestination = NavDestination()
}

class NavOptionsBuilder {
    var launchSingleTop: Boolean = false
    var restoreState: Boolean = false
    fun popUpTo(route: String, builder: PopUpToBuilder.() -> Unit = {}) = Unit
}

class PopUpToBuilder {
    var inclusive: Boolean = false
    var saveState: Boolean = false
}

open class NavGraphBuilder

open class NavHostController {
    fun navigate(route: String, builder: NavOptionsBuilder.() -> Unit = {}) = Unit
    fun popBackStack(): Boolean = true
}
