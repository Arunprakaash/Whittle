package app.whittle.core.common

/** Result of an operation that can fail with a typed [HarnessError] instead of an exception. */
sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>
    data class Failure(val error: HarnessError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.getOrElse(onFailure: (HarnessError) -> T): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> onFailure(error)
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value
