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
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlin.time.*

actual fun test(timeout: Duration?, block: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
    when (timeout) {
        null -> block()
        else -> withTimeout(timeout) { block() }
    }
}

actual class TestPacketStore {

    private val sentIndex = atomic(0)
    private val sent = atomicArrayOfNulls<ByteReadPacket>(50)
    actual val stored: List<ByteReadPacket>
        get() = buildList {
            repeat(sentIndex.value) {
                val value = sent[it].value!!
                add(value)
            }
        }

    actual fun store(packet: ByteReadPacket) {
        sent[sentIndex.value].value = packet
        sentIndex.incrementAndGet()
    }
}

actual val platform: String get() = "NATIVE"