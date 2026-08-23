package app.postmark.memory.ui.auth

import postmark.core.model.NormalizedHandle

/** Client-side early validation only; the server stays authoritative. */
enum class RegistrationFieldError {
    EMAIL_INVALID,
    PASSWORD_TOO_SHORT,
    PASSWORD_TOO_LONG,
    HANDLE_INVALID,
}

data class RegistrationFormState(
    val email: String = "",
    val password: String = "",
    val handle: String = "",
)

/**
 * Field-level validation mirroring protocol.md section 12 limits:
 * password 12–128 Unicode code points, handle 3–30 ASCII after
 * normalization, email shape checked only as early UX feedback.
 */
object RegistrationFormValidator {

    private val EMAIL_SHAPE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun emailError(email: String): RegistrationFieldError? =
        when {
            email.isEmpty() -> null
            !EMAIL_SHAPE.matches(email) -> RegistrationFieldError.EMAIL_INVALID
            else -> null
        }

    /** Protocol limit counts Unicode code points, not UTF-16 units. */
    fun passwordError(password: String): RegistrationFieldError? {
        if (password.isEmpty()) return null
        val codePoints = password.codePointCount(0, password.length)
        return when {
            codePoints < MIN_PASSWORD_CODE_POINTS -> RegistrationFieldError.PASSWORD_TOO_SHORT
            codePoints > MAX_PASSWORD_CODE_POINTS -> RegistrationFieldError.PASSWORD_TOO_LONG
            else -> null
        }
    }

    fun handleError(handle: String): RegistrationFieldError? {
        if (handle.isEmpty()) return null
        return try {
            NormalizedHandle.parse(handle)
            null
        } catch (_: IllegalArgumentException) {
            RegistrationFieldError.HANDLE_INVALID
        }
    }

    fun visibleErrors(form: RegistrationFormState): Map<RegistrationFieldError, String> {
        val errors = mutableMapOf<RegistrationFieldError, String>()
        listOfNotNull(
            emailError(form.email),
            passwordError(form.password),
            handleError(form.handle),
        ).forEach { errors[it] = it.name }
        return errors
    }

    fun canSubmit(form: RegistrationFormState): Boolean =
        form.email.isNotEmpty() &&
            form.password.isNotEmpty() &&
            form.handle.isNotEmpty() &&
            visibleErrors(form).isEmpty()

    const val MIN_PASSWORD_CODE_POINTS: Int = 12
    const val MAX_PASSWORD_CODE_POINTS: Int = 128
}
