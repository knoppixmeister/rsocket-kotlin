/*
 * Copyright 2015-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.kotlin

import io.ktor.utils.io.core.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class RSocketRequestHandlerBuilder internal constructor(private val job: Job) {
    var metadataPush: (suspend RSocket.(metadata: ByteReadPacket) -> Unit)? = null
    var fireAndForget: (suspend RSocket.(payload: Payload) -> Unit)? = null
    var requestResponse: (suspend RSocket.(payload: Payload) -> Payload)? = null
    var requestStream: (RSocket.(payload: Payload) -> Flow<Payload>)? = null
    var requestChannel: (RSocket.(payloads: Flow<Payload>) -> Flow<Payload>)? = null

    internal fun build(): RSocket = RSocketRequestHandler(job, metadataPush, fireAndForget, requestResponse, requestStream, requestChannel)
}

@Suppress("FunctionName")
fun RSocketRequestHandler(parentJob: Job? = null, configure: RSocketRequestHandlerBuilder.() -> Unit): RSocket {
    val builder = RSocketRequestHandlerBuilder(Job(parentJob))
    builder.configure()
    return builder.build()
}

private class RSocketRequestHandler(
    override val job: Job,
    private val metadataPush: (suspend RSocket.(metadata: ByteReadPacket) -> Unit)? = null,
    private val fireAndForget: (suspend RSocket.(payload: Payload) -> Unit)? = null,
    private val requestResponse: (suspend RSocket.(payload: Payload) -> Payload)? = null,
    private val requestStream: (RSocket.(payload: Payload) -> Flow<Payload>)? = null,
    private val requestChannel: (RSocket.(payloads: Flow<Payload>) -> Flow<Payload>)? = null,
) : RSocket {
    override suspend fun metadataPush(metadata: ByteReadPacket): Unit =
        metadataPush?.invoke(this, metadata) ?: super.metadataPush(metadata)

    override suspend fun fireAndForget(payload: Payload): Unit =
        fireAndForget?.invoke(this, payload) ?: super.fireAndForget(payload)

    override suspend fun requestResponse(payload: Payload): Payload =
        requestResponse?.invoke(this, payload) ?: super.requestResponse(payload)

    override fun requestStream(payload: Payload): Flow<Payload> =
        requestStream?.invoke(this, payload) ?: super.requestStream(payload)

    override fun requestChannel(payloads: Flow<Payload>): Flow<Payload> =
        requestChannel?.invoke(this, payloads) ?: super.requestChannel(payloads)

}
