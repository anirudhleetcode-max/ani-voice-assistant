@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.lifecycle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// The real androidx defines lifecycleScope on LifecycleOwner, which both
// ComponentActivity and LifecycleService implement.
interface LifecycleOwner

val LifecycleOwner.lifecycleScope: CoroutineScope
    get() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

abstract class ViewModel {
    open fun onCleared() = Unit

    interface Factory
}

val ViewModel.viewModelScope: CoroutineScope
    get() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

object ViewModelProvider {
    interface Factory {
        fun <T : ViewModel> create(modelClass: Class<T>): T
    }
}

open class LifecycleService : Service(), LifecycleOwner {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
}
