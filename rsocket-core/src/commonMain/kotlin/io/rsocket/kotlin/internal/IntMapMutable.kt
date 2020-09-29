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
internal class IntMapMutable<V : Any>(
    initialCapacity: Int = 0,
    private val loadFactor: Float = 0.5f,
) : SynchronizedObject() {
    init {
        require(loadFactor > 0.0f && loadFactor <= 1.0f) { "loadFactor must be > 0 and <= 1" }
    }

    /** The maximum number of elements allowed without allocating more space.  */
    private var maxSize: Int

    /** The load factor for the map. Used to calculate [.maxSize].  */
    private var keys: IntArray
    private var values: Array<V?>
    private var size = 0
    private var mask: Int

    init {
        require(!(loadFactor <= 0.0f || loadFactor > 1.0f)) {
            // Cannot exceed 1 because we can never store more than capacity elements;
            // using a bigger loadFactor would trigger rehashing before the desired load is reached.
            "loadFactor must be > 0 and <= 1"
        }

        // Adjust the initial capacity if necessary.
        val capacity = safeFindNextPositivePowerOfTwo(initialCapacity)
        mask = capacity - 1

        // Allocate the arrays.
        keys = IntArray(capacity)
        val temp = arrayOfNulls<Any>(capacity) as Array<V?>
        values = temp

        // Initialize the maximum size value.
        maxSize = calcMaxSize(capacity)
    }

    operator fun get(key: Int): V? = synchronized(this) {
        val index = indexOf(key)
        return if (index == -1) null else values[index]
    }

    operator fun set(key: Int, value: V): V? = synchronized(this) {
        val startIndex = hashIndex(key)
        var index = startIndex
        while (true) {
            if (values[index] == null) {
                // Found empty slot, use it.
                keys[index] = key
                values[index] = value
                growSize()
                return null
            }
            if (keys[index] == key) {
                // Found existing entry with this key, just replace the value.
                val previousValue = values[index]
                values[index] = value
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
        val prev = values[index]
        removeAt(index)
        return prev
    }

    fun clear() = synchronized(this) {
        keys.fill(0)
        values.fill(null)
        size = 0
    }

    operator fun contains(key: Int): Boolean = synchronized(this) { indexOf(key) >= 0 }

    fun iterateValues(block: (V) -> Unit) = synchronized(this) {
        values.forEach { it?.let(block) }
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
        while (true) {
            if (values[index] == null) {
                // It's available, so no chance that this value exists anywhere in the map.
                return -1
            }
            if (key == keys[index]) {
                return index
            }

            // Conflict, keep probing ...
            if (probeNext(index).also { index = it } == startIndex) {
                return -1
            }
        }
    }

    /**
     * Returns the hashed index for the given key.
     */
    private fun hashIndex(key: Int): Int {
        // The array lengths are always a power of two, so we can use a bitmask to stay inside the array bounds.
        return key and mask
    }

    /**
     * Get the next sequential index after `index` and wraps if necessary.
     */
    private fun probeNext(index: Int): Int {
        // The array lengths are always a power of two, so we can use a bitmask to stay inside the array bounds.
        return index + 1 and mask
    }

    /**
     * Grows the map size after an insertion. If necessary, performs a rehash of the map.
     */
    private fun growSize() {
        size++
        if (size > maxSize) {
            check(keys.size != Int.MAX_VALUE) { "Max capacity reached at size=$size" }

            // Double the capacity.
            rehash(keys.size shl 1)
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
        --size
        // Clearing the key is not strictly necessary (for GC like in a regular collection),
        // but recommended for security. The memory location is still fresh in the cache anyway.
        keys[index] = 0
        values[index] = null

        // In the interval from index to the next available entry, the arrays may have entries
        // that are displaced from their base position due to prior conflicts. Iterate these
        // entries and move them back if possible, optimizing future lookups.
        // Knuth Section 6.4 Algorithm R, also used by the JDK's IdentityHashMap.
        var nextFree = index
        var i = probeNext(index)
        var value = values[i]
        while (value != null) {
            val key = keys[i]
            val bucket = hashIndex(key)
            if (i < bucket && (bucket <= nextFree || nextFree <= i) || bucket <= nextFree && nextFree <= i) {
                // Move the displaced entry "back" to the first available position.
                keys[nextFree] = key
                values[nextFree] = value
                // Put the first entry after the displaced entry
                keys[i] = 0
                values[i] = null
                nextFree = i
            }
            value = values[probeNext(i).also { i = it }]
        }
        return nextFree != index
    }

    /**
     * Calculates the maximum size allowed before rehashing.
     */
    private fun calcMaxSize(capacity: Int): Int {
        // Clip the upper bound so that there will always be at least one available slot.
        val upperBound = capacity - 1
        return min(upperBound, (capacity * loadFactor).toInt())
    }

    /**
     * Rehashes the map for the given capacity.
     *
     * @param newCapacity the new capacity for the map.
     */
    private fun rehash(newCapacity: Int) {
        val oldKeys = keys
        val oldVals = values
        keys = IntArray(newCapacity)
        val temp = arrayOfNulls<Any>(newCapacity) as Array<V?>
        values = temp
        maxSize = calcMaxSize(newCapacity)
        mask = newCapacity - 1

        // Insert to the new arrays.
        for (i in oldVals.indices) {
            val oldVal = oldVals[i]
            if (oldVal != null) {
                // Inlined put(), but much simpler: we don't need to worry about
                // duplicated keys, growing/rehashing, or failing to insert.
                val oldKey = oldKeys[i]
                var index = hashIndex(oldKey)
                while (true) {
                    if (values[index] == null) {
                        keys[index] = oldKey
                        values[index] = oldVal
                        break
                    }

                    // Conflict, keep probing. Can wrap around, but never reaches startIndex again.
                    index = probeNext(index)
                }
            }
        }
    }
}

private fun safeFindNextPositivePowerOfTwo(value: Int): Int = when {
    value <= 0          -> 1
    value >= 0x40000000 -> 0x40000000
    else                -> 1 shl 32 - (value - 1).countLeadingZeroBits()
}
