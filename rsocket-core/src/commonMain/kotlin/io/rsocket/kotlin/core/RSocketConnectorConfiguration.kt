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

package io.rsocket.kotlin.core

import io.rsocket.kotlin.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.keepalive.*
import io.rsocket.kotlin.payload.*
import io.rsocket.kotlin.plugin.*

data class RSocketConnectorConfiguration(
    val plugin: Plugin = Plugin(),
    val fragmentation: Int = 0,
    val keepAlive: KeepAlive = KeepAlive(),
    val payloadMimeType: PayloadMimeType = PayloadMimeType(),
    val setupPayload: Payload = Payload.Empty,
    val ignoredFrameConsumer: (Frame) -> Unit = {},
    val acceptor: RSocketAcceptor = { RSocketRequestHandler { } },
)