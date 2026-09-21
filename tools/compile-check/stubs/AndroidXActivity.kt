// Stubs for androidx.activity — not published to Maven Central.
// Signatures mirror the real library so mismatches in app code still surface.
@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.activity

import android.app.Activity
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

open class ComponentActivity : Activity(), androidx.lifecycle.LifecycleOwner {
    fun <I, O> registerForActivityResult(
        contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
        callback: (O) -> Unit
    ): androidx.activity.result.ActivityResultLauncher<I> =
        androidx.activity.result.ActivityResultLauncher()
}

fun ComponentActivity.enableEdgeToEdge() = Unit

inline fun <reified VM : ViewModel> ComponentActivity.viewModels(
    noinline factoryProducer: (() -> ViewModelProvider.Factory)? = null
): Lazy<VM> = lazy { factoryProducer!!.invoke().create(VM::class.java) }
