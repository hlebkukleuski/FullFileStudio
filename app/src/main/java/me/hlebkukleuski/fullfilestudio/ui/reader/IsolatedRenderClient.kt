/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://gnu.org>.
 */

package me.hlebkukleuski.fullfilestudio.ui.reader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Stable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import me.hlebkukleuski.fullfilestudio.core.engine.IIsolatedEngineService
import me.hlebkukleuski.fullfilestudio.service.IsolatedRenderService

/**
 * Contract for managing Binder IPC lifecycle with [IsolatedRenderService].
 * Abstracted behind an interface so [ReaderViewModel] can be unit-tested deterministically
 * without starting a real OS service process when using an in-memory stub.
 */
@Stable
interface IsolatedRenderClient {
    interface ConnectionListener {
        fun onConnected(service: IIsolatedEngineService)
        fun onDisconnected()
    }

    fun setConnectionListener(listener: ConnectionListener?)
    fun peekService(): IIsolatedEngineService?
    suspend fun getService(): IIsolatedEngineService
    fun ensureBound()
    fun unbindSafely()
}

class AndroidIsolatedRenderClient(
    private val appContext: Context,
    private val timeoutMessageProvider: () -> String
) : IsolatedRenderClient {

    @Volatile
    private var isolatedService: IIsolatedEngineService? = null

    @Volatile
    private var connectionListener: IsolatedRenderClient.ConnectionListener? = null

    private var isServiceBound = false
    private val serviceLock = Any()
    private val serviceContinuations = mutableListOf<CancellableContinuation<IIsolatedEngineService>>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = IIsolatedEngineService.Stub.asInterface(service)
            val conts: List<CancellableContinuation<IIsolatedEngineService>>
            synchronized(serviceLock) {
                isolatedService = binder
                isServiceBound = true
                conts = serviceContinuations.toList()
                serviceContinuations.clear()
            }
            conts.forEach { if (it.isActive) it.resume(binder) }
            connectionListener?.onConnected(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val conts: List<CancellableContinuation<IIsolatedEngineService>>
            synchronized(serviceLock) {
                isolatedService = null
                isServiceBound = false
                conts = serviceContinuations.toList()
                serviceContinuations.clear()
            }
            conts.forEach {
                if (it.isActive) {
                    it.resumeWithException(IllegalStateException("Service disconnected"))
                }
            }
            connectionListener?.onDisconnected()
        }

        override fun onBindingDied(name: ComponentName?) {
            val conts: List<CancellableContinuation<IIsolatedEngineService>>
            synchronized(serviceLock) {
                isolatedService = null
                if (isServiceBound) {
                    try { appContext.unbindService(this) } catch (_: Throwable) {}
                    isServiceBound = false
                }
                conts = serviceContinuations.toList()
                serviceContinuations.clear()
            }
            conts.forEach {
                if (it.isActive) {
                    it.resumeWithException(IllegalStateException("Service binding died"))
                }
            }
            connectionListener?.onDisconnected()
        }
    }

    override fun setConnectionListener(listener: IsolatedRenderClient.ConnectionListener?) {
        connectionListener = listener
    }

    override fun peekService(): IIsolatedEngineService? = isolatedService

    override fun ensureBound() {
        synchronized(serviceLock) {
            if (!isServiceBound) {
                isServiceBound = try {
                    appContext.bindService(
                        Intent(appContext, IsolatedRenderService::class.java),
                        serviceConnection,
                        Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                    )
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    override fun unbindSafely() {
        val conts: List<CancellableContinuation<IIsolatedEngineService>>
        synchronized(serviceLock) {
            if (isServiceBound) {
                try { appContext.unbindService(serviceConnection) } catch (_: Exception) {}
                isServiceBound = false
            }
            isolatedService = null
            conts = serviceContinuations.toList()
            serviceContinuations.clear()
        }
        conts.forEach {
            if (it.isActive) {
                it.resumeWithException(IllegalStateException("Service unbound"))
            }
        }
    }

    override suspend fun getService(): IIsolatedEngineService = suspendCancellableCoroutine { cont ->
        var failImmediately = false
        synchronized(serviceLock) {
            val svc = isolatedService
            if (svc != null && svc.asBinder().isBinderAlive) {
                cont.resume(svc)
                return@suspendCancellableCoroutine
            }

            val hadDeadService = svc != null
            isolatedService = null
            serviceContinuations.add(cont)
            cont.invokeOnCancellation {
                synchronized(serviceLock) {
                    serviceContinuations.remove(cont)
                    if (isolatedService == null && serviceContinuations.isEmpty() && isServiceBound) {
                        try { appContext.unbindService(serviceConnection) } catch (_: Throwable) {}
                        isServiceBound = false
                    }
                }
            }
            if (hadDeadService && isServiceBound) {
                try { appContext.unbindService(serviceConnection) } catch (_: Throwable) {}
                isServiceBound = false
            }
            if (!isServiceBound) {
                isServiceBound = try {
                    appContext.bindService(
                        Intent(appContext, IsolatedRenderService::class.java),
                        serviceConnection,
                        Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                    )
                } catch (_: Throwable) {
                    false
                }
                if (!isServiceBound) {
                    serviceContinuations.remove(cont)
                    failImmediately = true
                }
            }
        }
        if (failImmediately && cont.isActive) {
            cont.resumeWithException(IllegalStateException(timeoutMessageProvider()))
        }
    }
}
