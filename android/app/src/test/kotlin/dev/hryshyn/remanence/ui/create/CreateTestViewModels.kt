package dev.hryshyn.remanence.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.TestCoroutineScheduler

/** Test-owned Android VM lifetime, including cancellation-delayed children. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class CreateTestViewModels(private val scheduler: TestCoroutineScheduler) {
    private val store = ViewModelStore()
    private val jobs = mutableListOf<Job>()
    fun <T : ViewModel> own(vm: T): T = vm.also {
        store.put("owned-${jobs.size}", vm)
        jobs += requireNotNull(vm.viewModelScope.coroutineContext[Job])
    }
    val allCompleted: Boolean get() = jobs.all { it.isCompleted }
    suspend fun clearAndJoin() {
        store.clear()
        scheduler.runCurrent()
        jobs.joinAll()
    }
}
