// Copyright (c) 2020 KineApps. All rights reserved.
//
// This source code is licensed under the BSD-style license found in the
// LICENSE file in the root directory of this source tree.

package com.kineapps.flutter_file_dialog

import android.app.Activity
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

class FlutterFileDialogPlugin : FlutterPlugin, ActivityAware, MethodCallHandler {
    private var fileDialog: FileDialog? = null
    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var methodChannel: MethodChannel? = null

    // Avoid repeating DocumentsProvider lookups when several files are saved
    // into the same subdirectory (queries can be slow, especially for cloud
    // providers).
    private val directoryCache = DirectoryCache()

    companion object {
        const val LOG_TAG = "FlutterFileDialogPlugin"
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Log.d(LOG_TAG, "onAttachedToEngine - IN")

        if (pluginBinding != null) {
            Log.w(LOG_TAG, "onAttachedToEngine - already attached")
        }

        pluginBinding = binding

        pluginBinding?.binaryMessenger?.let { doOnAttachedToEngine(it) }

        Log.d(LOG_TAG, "onAttachedToEngine - OUT")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Log.d(LOG_TAG, "onDetachedFromEngine")
        doOnDetachedFromEngine()
    }

    // note: this may be called multiple times on app startup
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        Log.d(LOG_TAG, "onAttachedToActivity")
        doOnAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        Log.d(LOG_TAG, "onDetachedFromActivity")
        doOnDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        Log.d(LOG_TAG, "onReattachedToActivityForConfigChanges")

        if (fileDialog != null) {
            this.activityBinding = binding
            fileDialog!!.setActivity(binding.activity)
        } else {
            doOnAttachedToActivity(binding)
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        Log.d(LOG_TAG, "onDetachedFromActivityForConfigChanges")
        this.activityBinding = null
        if (fileDialog != null) {
            fileDialog!!.setActivity(null)
        }
        directoryCache.clear()
    }

    private fun doOnAttachedToEngine(messenger: BinaryMessenger) {
        Log.d(LOG_TAG, "doOnAttachedToEngine - IN")

        methodChannel = MethodChannel(messenger, "flutter_file_dialog").apply {
            setMethodCallHandler(this@FlutterFileDialogPlugin)
        }

        Log.d(LOG_TAG, "doOnAttachedToEngine - OUT")
    }

    private fun doOnDetachedFromEngine() {
        Log.d(LOG_TAG, "doOnDetachedFromEngine - IN")

        if (pluginBinding == null) {
            Log.w(LOG_TAG, "doOnDetachedFromEngine - already detached")
        }
        pluginBinding = null

        directoryCache.clear()
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null

        Log.d(LOG_TAG, "doOnDetachedFromEngine - OUT")
    }

    private fun doOnAttachedToActivity(activityBinding: ActivityPluginBinding?) {
        Log.d(LOG_TAG, "doOnAttachedToActivity - IN")

        // remove a previously registered FileDialog from the old binding so
        // stale listeners cannot process dialog results, and resolve its
        // pending result (if any) so the Dart future does not hang
        if (fileDialog != null) {
            this.activityBinding?.removeActivityResultListener(fileDialog!!)
            fileDialog!!.cancelPendingResult()
            fileDialog = null
        }

        this.activityBinding = activityBinding
        activityBinding?.let { createFileDialog(it) }

        Log.d(LOG_TAG, "doOnAttachedToActivity - OUT")
    }

    private fun doOnDetachedFromActivity() {
        Log.d(LOG_TAG, "doOnDetachedFromActivity - IN")

        if (fileDialog != null) {
            activityBinding?.removeActivityResultListener(fileDialog!!)
            fileDialog!!.cancelPendingResult()
            fileDialog = null
        }
        activityBinding = null
        directoryCache.clear()

        Log.d(LOG_TAG, "doOnDetachedFromActivity - OUT")
    }


    override fun onMethodCall(call: MethodCall, result: Result) {
        Log.d(LOG_TAG, "onMethodCall - IN , method=${call.method}")
        if (activityBinding == null) {
            result.error("init_failed", "Not attached to activity", null)
            return
        }
        when (call.method) {
            "pickDirectory" -> fileDialog!!.pickDirectory(result)
            "saveFileToDirectory" -> saveFileToDirectory(
                    result,
                    mimeType = call.argument("mimeType") as String?,
                    fileName = call.argument("fileName") as String?,
                    directory = call.argument("directory") as String?,
                    data = call.argument("data") as ByteArray?,
            )
            "pickFile" -> fileDialog!!.pickFile(
                    result,
                    fileExtensionsFilter = parseMethodCallArrayArgument(call, "fileExtensionsFilter"),
                    mimeTypesFilter = parseMethodCallArrayArgument(call, "mimeTypesFilter"),
                    localOnly = call.argument("localOnly") as Boolean? == true,
                    copyFileToCacheDir = call.argument("copyFileToCacheDir") as Boolean? != false
            )
            "saveFile" -> fileDialog!!.saveFile(
                    result,
                    sourceFilePath = call.argument("sourceFilePath"),
                    data = call.argument("data"),
                    fileName = call.argument("fileName"),
                    mimeTypesFilter = parseMethodCallArrayArgument(call, "mimeTypesFilter"),
                    localOnly = call.argument("localOnly") as Boolean? == true
            )
            else -> result.notImplemented()
        }
    }

    private fun saveFileToDirectory(
            result: Result,
            directory: String?,
            mimeType: String?,
            fileName: String?,
            data: ByteArray?,
    ) {
        Log.d(LOG_TAG, "saveFileToDirectory - IN")

        if (directory.isNullOrEmpty()) {
            result.error("invalid_arguments", "Missing 'directory'", null)
            return
        }

        if (mimeType.isNullOrEmpty()) {
            result.error("invalid_arguments", "Missing 'mimeType'", null)
            return
        }

        if (fileName.isNullOrEmpty()) {
            result.error("invalid_arguments", "Missing 'fileName'", null)
            return
        }

        if (data == null) {
            result.error("invalid_arguments", "Missing 'data'", null)
            return
        }

        val activity = activityBinding?.activity
        if (activity == null) {
            result.error("internal_error", "No activity is available", null)
            return
        }

        val pathSegments = parseRelativePath(fileName)
        if (pathSegments == null) {
            result.error("invalid_arguments", "Invalid 'fileName'", null)
            return
        }

        val relativePath = pathSegments.dropLast(1)
        val cacheKey = DirectoryCacheKey(directory, relativePath)

        try {
            val cachedFolder = directoryCache.get(cacheKey)

            // Try the cached folder first; resolve (or re-resolve, if the
            // cached directory was deleted/renamed) and retry once on failure.
            var newFile = cachedFolder?.createFile(mimeType, pathSegments.last())
            if (newFile == null) {
                if (cachedFolder != null) {
                    directoryCache.remove(cacheKey)
                }

                val destinationFolder = resolveAndCacheDirectory(activity, cacheKey)
                if (destinationFolder == null) {
                    result.error("directory_create_failed", "Failed to create the target directory", null)
                    return
                }

                newFile = destinationFolder.createFile(mimeType, pathSegments.last())
            }

            if (newFile == null) {
                directoryCache.remove(cacheKey)
                result.error("file_create_failed", "Failed to create the target file", null)
                return
            }

            writeFile(activity, data, newFile.uri)
            result.success(newFile.uri.path)
        } catch (e: Exception) {
            directoryCache.remove(cacheKey)
            Log.e(LOG_TAG, "saveFileToDirectory failed", e)
            result.error("file_create_failed", e.localizedMessage, e.toString())
        }

        Log.d(LOG_TAG, "saveFileToDirectory - OUT")
    }

    /**
     * Splits a relative file path into its segments, rejecting absolute paths
     * and parent-directory references so a save can never escape the picked
     * directory.
     */
    internal fun parseRelativePath(path: String): List<String>? {
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            return null
        }
        return segments
    }

    /** Creates (or reuses) the directory segments below [parent]. */
    private fun ensureDirectory(parent: DocumentFile, segments: List<String>): DocumentFile? {
        var directory = parent
        for (segment in segments) {
            val existing = directory.findFile(segment)
            directory = when {
                existing == null -> directory.createDirectory(segment) ?: return null
                existing.isDirectory -> existing
                else -> return null
            }
        }
        return directory
    }

    private fun resolveAndCacheDirectory(
            activity: Activity,
            cacheKey: DirectoryCacheKey
    ): DocumentFile? {
        val context = activity.applicationContext ?: activity
        val outputFolder: DocumentFile? = DocumentFile.fromTreeUri(context, Uri.parse(cacheKey.treeUri))
        if (outputFolder == null) {
            return null
        }

        val destinationFolder = ensureDirectory(outputFolder, cacheKey.path) ?: return null
        directoryCache.put(cacheKey, destinationFolder)
        return destinationFolder
    }

    private fun writeFile(
            activity: Activity,
            data: ByteArray,
            destinationFileUri: Uri
    ) {
        activity.contentResolver.openOutputStream(destinationFileUri).use { outputStream ->
            outputStream as java.io.FileOutputStream
            outputStream.channel.truncate(0)
            outputStream.write(data)
        }
        Log.d(LOG_TAG, "Saved file to '${destinationFileUri.path}'")
    }

    private fun createFileDialog(activityBinding: ActivityPluginBinding) {
        Log.d(LOG_TAG, "createFileDialog - IN")

        val fileDialog = FileDialog(activity = activityBinding.activity)
        activityBinding.addActivityResultListener(fileDialog)
        this.fileDialog = fileDialog

        Log.d(LOG_TAG, "createFileDialog - OUT")
    }

    private fun parseMethodCallArrayArgument(call: MethodCall, arg: String): Array<String>? {
        if (call.hasArgument(arg)) {
            return call.argument<ArrayList<String>>(arg)?.toTypedArray()
        }
        return null
    }
}
