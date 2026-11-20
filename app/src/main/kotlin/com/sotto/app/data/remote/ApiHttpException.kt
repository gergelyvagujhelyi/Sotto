package com.sotto.app.data.remote

/** The relay answered with an HTTP error. A 4xx won't change on a retry; network errors might. */
class ApiHttpException(val status: Int, message: String) : IllegalStateException(message) {
    val isClientError: Boolean get() = status in 400..499
}
