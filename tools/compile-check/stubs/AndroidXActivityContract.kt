@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.activity.result.contract

abstract class ActivityResultContract<I, O>

object ActivityResultContracts {
    class RequestPermission : ActivityResultContract<String, Boolean>()
    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>()
    class StartActivityForResult : ActivityResultContract<android.content.Intent, Any?>()
}
