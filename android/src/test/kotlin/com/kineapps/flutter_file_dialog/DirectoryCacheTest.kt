// Copyright (c) 2026 KineApps. All rights reserved.
//
// This source code is licensed under the BSD-style license found in the
// LICENSE file in the root directory of this source tree.

package com.kineapps.flutter_file_dialog

import androidx.documentfile.provider.DocumentFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

class DirectoryCacheTest {

    @Test
    fun `get - should return stored directory`() {
        val cache = DirectoryCache()
        val key = DirectoryCacheKey("tree", listOf("reports"))
        val directory = mock(DocumentFile::class.java)

        cache.put(key, directory)

        assertEquals(directory, cache.get(key))
    }

    @Test
    fun `remove - should drop only the given directory`() {
        val cache = DirectoryCache()
        val firstKey = DirectoryCacheKey("tree", listOf("reports"))
        val secondKey = DirectoryCacheKey("tree", listOf("photos"))
        val firstDirectory = mock(DocumentFile::class.java)
        val secondDirectory = mock(DocumentFile::class.java)
        cache.put(firstKey, firstDirectory)
        cache.put(secondKey, secondDirectory)

        cache.remove(firstKey)

        assertNull(cache.get(firstKey))
        assertEquals(secondDirectory, cache.get(secondKey))
    }

    @Test
    fun `put - should drop all entries when the cache is full`() {
        val cache = DirectoryCache(maxSize = 2)
        val firstKey = DirectoryCacheKey("tree", listOf("reports"))
        val secondKey = DirectoryCacheKey("tree", listOf("photos"))
        val thirdKey = DirectoryCacheKey("tree", listOf("videos"))
        val thirdDirectory = mock(DocumentFile::class.java)
        cache.put(firstKey, mock(DocumentFile::class.java))
        cache.put(secondKey, mock(DocumentFile::class.java))

        cache.put(thirdKey, thirdDirectory)

        assertNull(cache.get(firstKey))
        assertNull(cache.get(secondKey))
        assertEquals(thirdDirectory, cache.get(thirdKey))
    }

    @Test
    fun `clear - should drop all entries`() {
        val cache = DirectoryCache()
        val key = DirectoryCacheKey("tree", listOf("reports"))
        cache.put(key, mock(DocumentFile::class.java))

        cache.clear()

        assertNull(cache.get(key))
    }
}
