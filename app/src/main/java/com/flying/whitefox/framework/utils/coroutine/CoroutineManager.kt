package com.flying.whitefox.framework.utils.coroutine

import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import kotlinx.coroutines.*
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext

/**
 * 协程管理类（单例，供 Java 极简调用）
 * 外部调用：直接用 CoroutineManager.INSTANCE.xxx()
 */
object CoroutineManager : CoroutineScope by MainScope() {

    // 线程调度枚举（Java 直接引用，无需理解 Kotlin Dispatcher）
    enum class DispatchType {
        MAIN,    // 主线程（UI 更新）
        IO,      // IO 线程（网络、数据库、文件，默认）
        DEFAULT  // CPU 密集型（计算任务）
    }

    // -------------------------- 简化回调接口 --------------------------
    // 无返回值任务回调（Java 实现更简单）
    interface SimpleCallback {
        @MainThread
        fun onSuccess() // 成功（主线程）

        @MainThread
        fun onFailure(e: Throwable) // 失败（主线程，带异常）
    }

    // 有返回值任务回调（可选，比 Future 更直观）
    interface ResultCallback<T> {
        @MainThread
        fun onSuccess(result: T) // 成功（带结果）

        @MainThread
        fun onFailure(e: Throwable) // 失败
    }


    // -------------------------- 1. 无返回值任务（最简化调用） --------------------------
    /**
     * 简化重载1：只传任务+回调（默认 IO 线程，不绑定生命周期）
     * Java 调用：CoroutineManager.INSTANCE.launch(task, callback);
     */
    @JvmStatic
    fun launch(task: Runnable, callback: SimpleCallback) {
        launch(null, DispatchType.IO, task, callback)
    }

    /**
     * 简化重载2：传生命周期+任务+回调（默认 IO 线程）
     * Java 调用：CoroutineManager.INSTANCE.launch(lifecycle, task, callback);
     */
    @JvmStatic
    fun launch(lifecycle: Lifecycle?, task: Runnable, callback: SimpleCallback) {
        launch(lifecycle, DispatchType.IO, task, callback)
    }

    /**
     * 完整方法（带 @JvmOverloads，支持所有参数配置）
     */
    @JvmStatic
    fun launch(
        lifecycle: Lifecycle? = null,
        dispatchType: DispatchType = DispatchType.IO,
        task: Runnable,
        callback: SimpleCallback
    ) {
        val scope = createScopedCoroutine(lifecycle)
        scope.launch(getDispatcher(dispatchType) + CoroutineExceptionHandler { _, e ->
            // 异常统一抛给回调（主线程）
            scope.launch(Dispatchers.Main) { callback.onFailure(e) }
        }) {
            // 任务在指定线程执行，结果切回主线程
            withContext(getDispatcher(dispatchType)) { task.run() }
            withContext(Dispatchers.Main) { callback.onSuccess() }
        }
    }


    // -------------------------- 2. 有返回值任务（Future 回调，Java 原生） --------------------------
    /**
     * 简化重载1：只传任务（默认 IO 线程，不绑定生命周期）
     * Java 调用：CoroutineManager.INSTANCE.async(task).whenComplete(...);
     */
    @JvmStatic
    fun <T> async(task: java.util.concurrent.Callable<T>): CompletableFuture<T> {
        return async(null, DispatchType.IO, task)
    }

    /**
     * 简化重载2：传生命周期+任务（默认 IO 线程）
     * Java 调用：CoroutineManager.INSTANCE.async(lifecycle, task).whenComplete(...);
     */
    @JvmStatic
    fun <T> async(lifecycle: Lifecycle?, task: java.util.concurrent.Callable<T>): CompletableFuture<T> {
        return async(lifecycle, DispatchType.IO, task)
    }

    /**
     * 完整方法（带 @JvmOverloads）
     */
    @JvmStatic
    fun <T> async(
        lifecycle: Lifecycle? = null,
        dispatchType: DispatchType = DispatchType.IO,
        task: java.util.concurrent.Callable<T>
    ): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val scope = createScopedCoroutine(lifecycle)
        scope.launch(getDispatcher(dispatchType)) {
            try {
                val result = withContext(getDispatcher(dispatchType)) { task.call() }
                withContext(Dispatchers.Main) { future.complete(result) }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { future.completeExceptionally(e) }
            }
        }
        return future
    }


    // -------------------------- 3. 有返回值任务（自定义回调，更直观） --------------------------
    /**
     * 简化重载1：只传任务+回调（默认 IO 线程，不绑定生命周期）
     * Java 调用：CoroutineManager.INSTANCE.async(task, callback);
     */
    @JvmStatic
    fun <T> async(task: java.util.concurrent.Callable<T>, callback: ResultCallback<T>) {
        async(null, DispatchType.IO, task, callback)
    }

    /**
     * 完整方法（不使用 JvmOverloads，避免 Java 泛型桥接问题）
     */
    @JvmStatic
    fun <T> async(
        lifecycle: Lifecycle? = null,
        dispatchType: DispatchType = DispatchType.IO,
        task: java.util.concurrent.Callable<T>,
        callback: ResultCallback<T>
    ) {
        val scope = createScopedCoroutine(lifecycle)
        scope.launch(getDispatcher(dispatchType) + CoroutineExceptionHandler { _, e ->
            scope.launch(Dispatchers.Main) { callback.onFailure(e) }
        }) {
            val result = withContext(getDispatcher(dispatchType)) { task.call() }
            withContext(Dispatchers.Main) { callback.onSuccess(result) }
        }
    }


    // -------------------------- 内部辅助方法（不变） --------------------------
    // 创建绑定生命周期的协程作用域（自动取消）
    private fun createScopedCoroutine(lifecycle: Lifecycle?): CoroutineScope {
        return if (lifecycle != null) {
            val scope = CoroutineScope(getDispatcher(DispatchType.DEFAULT) + SupervisorJob())
            val observer = object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    if (event == Lifecycle.Event.ON_DESTROY) {
                        scope.cancel()
                        lifecycle.removeObserver(this)
                    }
                }
            }
            lifecycle.addObserver(observer)
            scope
        } else {
            this // 无生命周期绑定，用全局单例作用域
        }
    }

    // 枚举转 Kotlin 调度器
    private fun getDispatcher(type: DispatchType): CoroutineContext {
        return when (type) {
            DispatchType.MAIN -> Dispatchers.Main
            DispatchType.IO -> Dispatchers.IO
            DispatchType.DEFAULT -> Dispatchers.Default
        }
    }

    /**
     * 手动取消全局协程（仅当任务未绑定生命周期时调用）
     */
    @JvmStatic
    fun cancelAll() {
        this.cancel()
    }
}