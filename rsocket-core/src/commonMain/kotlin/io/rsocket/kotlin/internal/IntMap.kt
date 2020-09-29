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

package io.rsocket.kotlin.internal

import kotlinx.atomicfu.*
import kotlinx.atomicfu.locks.*
import kotlin.math.*

/**
 * A hash map implementation of [IntObjectMap] that uses open addressing for keys.
 * To minimize the memory footprint, this class uses open addressing rather than chaining.
 * Collisions are resolved using linear probing. Deletions implement compaction, so cost of
 * remove can approach O(N) for full maps, which makes a small loadFactor recommended.
 *
 * @param <V> The value type stored in the map.
 */
internal class IntMap<V : Any>(
    initialCapacity: Int = 0,
    private val loadFactor: Float = 0.5f,
) : SynchronizedObject() {
    init {
        require(loadFactor > 0.0f && loadFactor <= 1.0f) { "loadFactor must be > 0 and <= 1" }
    }

    private val size = atomic(0)
    private val state = atomic(State<V>(safeFindNextPositivePowerOfTwo(initialCapacity), loadFactor))

    operator fun get(key: Int): V? = synchronized(this) {
        val index = indexOf(key)
        return if (index == -1) null else state.value.value(index)
    }

    operator fun set(key: Int, value: V): V? = synchronized(this) {
        val startIndex = hashIndex(key)
        var index = startIndex
        val state = state.value
        while (true) {
            if (state.value(index) == null) {
                // Found empty slot, use it.
                state[index] = key
                state[index] = value
                growSize()
                return null
            }
            if (state.key(index) == key) {
                // Found existing entry with this key, just replace the value.
                val previousValue = state.value(index)
                state[index] = value
                return previousValue
            }

            // Conflict, keep probing ...
            check(probeNext(index).also { index = it } != startIndex) {
                // Can only happen if the map was full at MAX_ARRAY_SIZE and couldn't grow.
                "Unable to insert"
            }
        }
        error("") //never happens
    }

    fun remove(key: Int): V? = synchronized(this) {
        val index = indexOf(key)
        if (index == -1) {
            return null
        }
        val prev = state.value.value(index)
        removeAt(index)
        return prev
    }

    fun clear() = synchronized(this) {
        state.value.clear()
        size.value = 0
    }

    operator fun contains(key: Int): Boolean = synchronized(this) { indexOf(key) >= 0 }

    fun iterateValues(block: (V) -> Unit) = synchronized(this) {
        val state = state.value
        repeat(state.size) {
            state.value(it)?.let(block)
        }
    }

    //--- implementation details

    /**
     * Locates the index for the given key. This method probes using double hashing.
     *
     * @param key the key for an entry in the map.
     * @return the index where the key was found, or `-1` if no entry is found for that key.
     */
    private fun indexOf(key: Int): Int {
        val startIndex = hashIndex(key)
        var index = startIndex
        val state = state.value
        while (true) {
            if (state.value(index) == null) return -1 // It's available, so no chance that this value exists anywhere in the map.
            if (key == state.key(index)) return index

            // Conflict, keep probing ...
            if (probeNext(index).also { index = it } == startIndex) return -1
        }
    }

    /**
     * Returns the hashed index for the given key.
     */
    private fun hashIndex(key: Int): Int {
        // The array lengths are always a power of two, so we can use a bitmask to stay inside the array bounds.
        return key and state.value.mask
    }

    /**
     * Get the next sequential index after `index` and wraps if necessary.
     */
    private fun probeNext(index: Int): Int {
        // The array lengths are always a power of two, so we can use a bitmask to stay inside the array bounds.
        return index + 1 and state.value.mask
    }

    /**
     * Grows the map size after an insertion. If necessary, performs a rehash of the map.
     */
    private fun growSize() {
        val state = state.value
        if (size.incrementAndGet() > state.maxSize) {
            check(state.size != Int.MAX_VALUE) { "Max capacity reached at size=${size.value}" }

            // Double the capacity.
            rehash(state.size shl 1)
        }
    }

    /**
     * Removes entry at the given index position. Also performs opportunistic, incremental rehashing
     * if necessary to not break conflict chains.
     *
     * @param index the index position of the element to remove.
     * @return `true` if the next item was moved back. `false` otherwise.
     */
    private fun removeAt(index: Int): Boolean {
        size.decrementAndGet()
        // Clearing the key is not strictly necessary (for GC like in a regular collection),
        // but recommended for security. The memory location is still fresh in the cache anyway.
        val state = state.value
        state[index] = 0
        state[index] = null

        // In the interval from index to the next available entry, the arrays may have entries
        // that are displaced from their base position due to prior conflicts. Iterate these
        // entries and move them back if possible, optimizing future lookups.
        // Knuth Section 6.4 Algorithm R, also used by the JDK's IdentityHashMap.
        var nextFree = index
        var i = probeNext(index)
        var value = state.value(i)
        while (value != null) {
            val key = state.key(i)
            val bucket = hashIndex(key)
            if (i < bucket && (bucket <= nextFree || nextFree <= i) || bucket <= nextFree && nextFree <= i) {
                // Move the displaced entry "back" to the first available position.
                state[nextFree] = key
                state[nextFree] = value
                // Put the first entry after the displaced entry
                state[i] = 0
                state[i] = null
                nextFree = i
            }
            value = state.value(probeNext(i).also { i = it })
        }
        return nextFree != index
    }

    /**
     * Rehashes the map for the given capacity.
     *
     * @param newCapacity the new capacity for the map.
     */
    private fun rehash(newCapacity: Int) {
        val oldState = state.value
        val newState = State<V>(newCapacity, loadFactor)
        state.value = newState

        // Insert to the new arrays.
        repeat(oldState.size) {
            oldState.value(it)?.let { oldValue ->
                val oldKey = oldState.key(it)
                var index = hashIndex(oldKey)
                while (true) {
                    if (newState.value(index) == null) {
                        newState[index] = oldKey
                        newState[index] = oldValue
                        break
                    }

                    // Conflict, keep probing. Can wrap around, but never reaches startIndex again.
                    index = probeNext(index)
                }
            }
        }
        oldState.clear()
    }
}

private fun safeFindNextPositivePowerOfTwo(value: Int): Int = when {
    value <= 0          -> 1
    value >= 0x40000000 -> 0x40000000
    else                -> 1 shl 32 - (value - 1).countLeadingZeroBits()
}

private class State<V : Any>(val size: Int, loadFactor: Float) {
    val mask = size - 1

    // Clip the upper bound so that there will always be at least one available slot.
    val maxSize = min(size - 1, (size * loadFactor).toInt())

    val keys: AtomicIntArray = AtomicIntArray(size)
    val values: AtomicArray<V?> = atomicArrayOfNulls(size)

    fun key(index: Int): Int = keys[index].value
    fun value(index: Int): V? = values[index].value

    operator fun set(index: Int, key: Int) {
        keys[index].value = key
    }

    operator fun set(index: Int, value: V?) {
        values[index].value = value
    }

    fun clear() {
        repeat(size) {
            keys[it].value = 0
            values[it].value = null
        }
    }
}
