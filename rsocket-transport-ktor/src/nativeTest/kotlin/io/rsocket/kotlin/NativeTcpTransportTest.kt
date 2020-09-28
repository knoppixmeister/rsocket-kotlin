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

import io.ktor.network.selector.*
import kotlin.time.*

class NativeTcpTransportTest : TcpTransportTest() {
    override val testTimeout: Duration = 30.minutes //big tests are very long
    override val clientSelector: SelectorManager get() = selectorClient
    override val serverSelector: SelectorManager get() = selectorServer

    companion object {
        private val selectorClient = SelectorManager()
        private val selectorServer = SelectorManager()
    }
}