package com.byd.proximitylab.target

import kotlinx.coroutines.flow.Flow

/**
 * 绑定目标的本地持久化入口。V1 只保存一个当前目标。
 */
interface TargetBindingStore {

    fun observeBinding(): Flow<BoundTarget?>

    suspend fun loadBinding(): BoundTarget?

    suspend fun saveBinding(binding: BoundTarget)

    suspend fun clearBinding()
}

sealed interface TargetActionResult {

    data class Success(val binding: BoundTarget) : TargetActionResult

    data object AlreadyBound : TargetActionResult

    data object NotBound : TargetActionResult

    data object CandidateNotFound : TargetActionResult
}
