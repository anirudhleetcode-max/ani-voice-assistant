@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.lifecycle.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun <T> StateFlow<T>.collectAsStateWithLifecycle(): State<T> = collectAsState()
