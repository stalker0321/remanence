package app.postmark.memory.ui.auth

enum class LoginFormError {
    EMAIL_INVALID,
}

data class LoginFormState(
    val email: String = "",
    val password: String = "",
)

object LoginFormValidator {

    private val EMAIL_SHAPE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun emailError(email: String): LoginFormError? =
        when {
            email.isEmpty() -> null
            !EMAIL_SHAPE.matches(email) -> LoginFormError.EMAIL_INVALID
            else -> null
        }

    fun visibleErrors(form: LoginFormState): Set<LoginFormError> =
        listOfNotNull(emailError(form.email)).toSet()

    fun canSubmit(form: LoginFormState): Boolean =
        form.email.isNotEmpty() && form.password.isNotEmpty() && visibleErrors(form).isEmpty()
}
