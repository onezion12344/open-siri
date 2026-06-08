package com.opensiri.agent.bootstrap

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * Storage bridge between the native Android filesystem and the proot
 * Linux filesystem used by the Termux bootstrap environment.
 *
 * Provides utility methods for:
 * - Navigating shared storage paths (/storage/emulated/0, Downloads, etc.)
 * - Mapping Android paths to proot-visible paths inside the prefix
 * - Listing directories and checking file existence
 * - Storage Access Framework (SAF) integration for cross-app file access
 *
 * All methods are static (Kotlin object) — no instantiation needed.
 */
object StorageBridge {

    private const val TAG = "StorageBridge"

    // ── Path constants ───────────────────────────────────────────────────

    /** Android shared storage root. */
    const val SHARED_STORAGE_PATH = "/storage/emulated/0"

    /** Proot-visible path prefix (where Android paths are bind-mounted inside the prefix). */
    const val PROOT_ANDROID_MOUNT = "/sdcard"

    // ── Public path utilities ────────────────────────────────────────────

    /**
     * Return the path to shared storage (external storage root).
     * Equivalent to `/storage/emulated/0` on most devices.
     */
    fun getSharedStoragePath(): String {
        return SHARED_STORAGE_PATH
    }

    /**
     * Return the path to the shared Downloads directory.
     */
    fun getDownloadsPath(): String {
        val downloadDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        return downloadDir.absolutePath
    }

    /**
     * Map an Android filesystem path to its proot-visible equivalent.
     *
     * The proot environment typically bind-mounts `/storage/emulated/0`
     * to `/sdcard` or a similar location inside the prefix.
     *
     * Mapping rules:
     * - `/storage/emulated/0/foo` → `/sdcard/foo`
     * - `/data/data/<pkg>/files/usr/foo` → `$PREFIX/foo` (inside prefix)
     * - Other paths → passed through as-is (proot can see the full Android tree)
     */
    fun getProotPath(androidPath: String): String {
        return when {
            // External storage
            androidPath.startsWith(SHARED_STORAGE_PATH) ->
                androidPath.replace(SHARED_STORAGE_PATH, PROOT_ANDROID_MOUNT)

            // Already a proot path
            androidPath.startsWith("/data/data/") && androidPath.contains("/files/usr") ->
                androidPath

            // Pass through for absolute paths (proot can see them)
            androidPath.startsWith("/") -> androidPath

            // Relative path — prepend home
            else -> "\$HOME/$androidPath"
        }
    }

    /**
     * Map a proot-visible path back to its Android filesystem equivalent.
     */
    fun getAndroidPath(prootPath: String): String {
        return when {
            prootPath.startsWith(PROOT_ANDROID_MOUNT) ->
                prootPath.replace(PROOT_ANDROID_MOUNT, SHARED_STORAGE_PATH)
            else -> prootPath
        }
    }

    // ── File system operations ───────────────────────────────────────────

    /**
     * List the contents of a directory at [path].
     * Returns a list of entries in the format `[type] name (size)`.
     */
    fun listDirectory(path: String): List<String> {
        val dir = File(path)
        if (!dir.isDirectory) {
            Log.w(TAG, "Not a directory: $path")
            return emptyList()
        }

        val entries = dir.listFiles() ?: return emptyList()
        return entries.map { file ->
            val type = if (file.isDirectory) "DIR" else "FILE"
            val size = if (file.isFile) formatSize(file.length()) else ""
            if (size.isNotEmpty()) "$type  $size  ${file.name}"
            else "$type  ${file.name}"
        }.sorted()
    }

    /**
     * Check whether a file or directory exists at [path].
     */
    fun fileExists(path: String): Boolean {
        return File(path).exists()
    }

    /**
     * Check whether [path] is a directory.
     */
    fun isDirectory(path: String): Boolean {
        return File(path).isDirectory
    }

    /**
     * Get the size of a file in bytes, or -1 if the file doesn't exist.
     */
    fun getFileSize(path: String): Long {
        val file = File(path)
        return if (file.isFile) file.length() else -1
    }

    /**
     * Read the entire contents of a text file. Returns null on failure.
     */
    fun readFileText(path: String): String? {
        return try {
            File(path).readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read $path: ${e.message}")
            null
        }
    }

    /**
     * Write text to a file, creating parent directories as needed.
     * Returns true on success.
     */
    fun writeFileText(path: String, content: String): Boolean {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write $path: ${e.message}")
            false
        }
    }

    // ── Storage Access Framework (SAF) helpers ──────────────────────────

    /**
     * Resolve a content:// URI to an absolute filesystem path via DocumentsProvider.
     * Supports both document URIs and tree URIs.
     *
     * @param context Android context
     * @param uri A content:// URI obtained from SAF picker
     * @return The absolute filesystem path, or null if it cannot be resolved
     */
    fun resolveContentUri(context: Context, uri: Uri): String? {
        // Handle DocumentsProvider Uris
        if (isDocumentUri(context, uri)) {
            return resolveDocumentUri(context, uri)
        }

        // Fallback: query the content resolver
        return resolveViaCursor(context, uri)
    }

    /**
     * Check if a URI is a document URI managed by the Storage Access Framework.
     */
    private fun isDocumentUri(context: Context, uri: Uri): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                DocumentsContract.isDocumentUri(context, uri)
            } else false
        } catch (_: Exception) { false }
    }

    /**
     * Resolve a document URI using DocumentsContract.
     */
    private fun resolveDocumentUri(context: Context, uri: Uri): String? {
        val docId = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                DocumentsContract.getDocumentId(uri)
            } else null
        } catch (_: Exception) { null } ?: return null

        val authority = uri.authority ?: return null

        val pathSegments = docId.split(":")
        if (pathSegments.size < 2) return null

        val primaryType = pathSegments[0]
        val relativePath = pathSegments[1]

        return when {
            authority == "com.android.externalstorage.documents" -> {
                when (primaryType) {
                    "primary" -> "$SHARED_STORAGE_PATH/$relativePath"
                    else -> "/storage/$primaryType/$relativePath"
                }
            }
            authority.contains("downloads") -> {
                "${getDownloadsPath()}/$relativePath"
            }
            else -> resolveViaCursor(context, uri)
        }
    }

    /**
     * Fallback resolution: query the content resolver for the _data column.
     */
    private fun resolveViaCursor(context: Context, uri: Uri): String? {
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf("_data"),
                null,
                null,
                null,
            )
            if (cursor != null && cursor.moveToFirst()) {
                val colIdx = cursor.getColumnIndexOrThrow("_data")
                return cursor.getString(colIdx)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not resolve URI $uri via cursor: ${e.message}")
        } finally {
            cursor?.close()
        }
        return null
    }

    /**
     * Build a document URI for a file at the given absolute path.
     */
    fun buildDocumentUri(path: String): Uri {
        val file = File(path)
        return Uri.fromFile(file)
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    /**
     * Format a file size in bytes to a human-readable string.
     */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
            else -> "${bytes / (1024 * 1024 * 1024)}GB"
        }
    }
}
