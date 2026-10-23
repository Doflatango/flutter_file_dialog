// Copyright (c) 2020 KineApps. All rights reserved.
//
// This source code is licensed under the BSD-style license found in the
// LICENSE file in the root directory of this source tree.

package com.kineapps.flutter_file_dialog

import androidx.documentfile.provider.DocumentFile

/**
 * Identifies a directory below a picked tree URI.
 */
internal data class DirectoryCacheKey(val treeUri: String, val path: List<String>)

/**
 * Caches directories that were already resolved or created so repeated saves
 * into the same subdirectory do not query the DocumentsProvider again.
 *
 * Cached DocumentFiles hold the application context. Entries are expected to
 * be invalidated when creating a file fails; an external rename/move that
 * keeps the document URI valid may therefore be followed until the cache is
 * cleared. When [maxSize] is reached the whole cache is dropped instead of
 * tracking LRU order.
 */
internal class DirectoryCache(
        private val maxSize: Int = DEFAULT_MAX_SIZE
) {
    companion object {
        private const val DEFAULT_MAX_SIZE = 16
    }

    private val entries = mutableMapOf<DirectoryCacheKey, DocumentFile>()

    fun get(key: DirectoryCacheKey): DocumentFile? {
        synchronized(entries) {
            return entries[key]
        }
    }

    fun put(key: DirectoryCacheKey, directory: DocumentFile) {
        synchronized(entries) {
            if (entries.size >= maxSize && !entries.containsKey(key)) {
                entries.clear()
            }
            entries[key] = directory
        }
    }

    fun remove(key: DirectoryCacheKey) {
        synchronized(entries) {
            entries.remove(key)
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
        }
    }
}
