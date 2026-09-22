@file:Suppress("unused")

package androidx.annotation

/**
 * Stub for `androidx.annotation.RequiresApi`.
 *
 * The real artifact lives only on Google Maven, which this harness cannot reach. The
 * annotation carries no behaviour — it exists so Android lint can verify that a call to a
 * newer API sits behind a version check — so a declaration with the same shape is enough
 * for the sources to type-check here. Lint itself runs in CI, against the real artifact,
 * which is where the annotation actually does its job.
 */
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER
)
annotation class RequiresApi(val value: Int = 1, val api: Int = 1)
