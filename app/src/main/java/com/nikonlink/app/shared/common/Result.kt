package com.nikonlink.app.shared.common

import timber.log.Timber

/**
 * 通用结果封装
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable, val message: String? = null) : Result<Nothing>()
    data object Loading : Result<Nothing>()

    val isSuccess get() = this is Success
    val isError get() = this is Error

    fun getOrNull(): T? = (this as? Success)?.data

    fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
        is Loading -> Loading
    }
}

/**
 * 扩展函数：安全执行带日志
 */
inline fun <T> safeCall(tag: String, block: () -> T): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: Exception) {
        Timber.tag(tag).e(e, "Operation failed")
        Result.Error(e, e.message)
    }
}
