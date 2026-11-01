// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.preference.PreferenceManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.GameDir
import org.yuzu.yuzu_emu.model.MinimalDocumentFile
import androidx.core.content.edit
import androidx.core.net.toUri

object GameHelper {
    private const val KEY_OLD_GAME_PATH = "game_path"
    const val KEY_GAMES = "Games"

    // Enough to find a game folder inside a couple of category folders
    private const val GAME_SCAN_DEPTH = 4

    var cachedGameList = mutableListOf<Game>()

    // Encrypted or Switch 1 games seen by the last scan, which can't be listed
    var unsupportedGameCount = 0
        private set

    val hasAllFilesAccess: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()

    private lateinit var preferences: SharedPreferences
    private var requestedAllFilesAccess = false

    fun getGames(): List<Game> {
        val foundGames = mutableListOf<Game>()
        unsupportedGameCount = 0
        val context = YuzuApplication.appContext
        preferences = PreferenceManager.getDefaultSharedPreferences(context)

        val gameDirs = mutableListOf<GameDir>()
        val oldGamesDir = preferences.getString(KEY_OLD_GAME_PATH, "") ?: ""
        if (oldGamesDir.isNotEmpty()) {
            gameDirs.add(GameDir(oldGamesDir, true))
            preferences.edit() { remove(KEY_OLD_GAME_PATH) }
        }
        gameDirs.addAll(NativeConfig.getGameDirs())

        // Ensure keys are loaded so that ROM metadata can be decrypted.
        NativeLibrary.reloadKeys()

        // Reset metadata so we don't use stale information
        GameMetadata.resetMetadata()

        // Remove previous filesystem provider information so we can get up to date version info
        NativeLibrary.clearFilesystemProvider()

        val mountedContainerUris = mutableSetOf<String>()
        mountExternalContentDirectories(mountedContainerUris)

        val badDirs = mutableListOf<Int>()
        gameDirs.forEachIndexed { index: Int, gameDir: GameDir ->
            val gameDirUri = gameDir.uriString.toUri()
            val isValid = FileUtil.isTreeUriValid(gameDirUri)
            if (isValid) {
                addGamesRecursive(foundGames, FileUtil.listFiles(gameDirUri), GAME_SCAN_DEPTH)
            } else if (context.contentResolver.persistedUriPermissions.none { it.uri == gameDirUri }) {
                badDirs.add(index)
            }
        }

        // Remove all game dirs with insufficient permissions from config
        if (badDirs.isNotEmpty()) {
            var offset = 0
            badDirs.forEach {
                gameDirs.removeAt(it - offset)
                offset++
            }
        }

        // Setting these clears the list first, and a config save at that moment would lose it
        if (badDirs.isNotEmpty() || oldGamesDir.isNotEmpty()) {
            NativeConfig.setGameDirs(gameDirs.toTypedArray())
        }

        val games = keepBestVersions(foundGames)

        // Cache list of games found on disk
        val serializedGames = mutableSetOf<String>()
        games.forEach {
            serializedGames.add(Json.encodeToString(it))
        }
        preferences.edit() {
            remove(KEY_GAMES)
                .putStringSet(KEY_GAMES, serializedGames)
        }

        cachedGameList = games.toMutableList()
        return games.toList()
    }

    fun restoreContentForGame(game: Game) {
        NativeLibrary.reloadKeys()

        val mountedContainerUris = mutableSetOf<String>()
        mountExternalContentDirectories(mountedContainerUris)
        mountGameFolderContent(Uri.parse(game.path), mountedContainerUris)
        NativeLibrary.addFileToFilesystemProvider(game.path)
    }

    // File extensions considered as external content, buuut should
    // be done better imo.
    private val externalContentExtensions = setOf("nsp", "xci", "nsz", "xcz")

    private fun scanContentContainersRecursive(
        files: Array<MinimalDocumentFile>,
        depth: Int,
        onContainerFound: (MinimalDocumentFile) -> Unit
    ) {
        if (depth <= 0) {
            return
        }

        files.forEach {
            if (it.isDirectory) {
                scanContentContainersRecursive(
                    FileUtil.listFiles(it.uri),
                    depth - 1,
                    onContainerFound
                )
            } else {
                val extension = FileUtil.getExtension(it.uri).lowercase()
                if (externalContentExtensions.contains(extension)) {
                    onContainerFound(it)
                }
            }
        }
    }

    private fun addGamesRecursive(
        games: MutableList<Game>,
        files: Array<MinimalDocumentFile>,
        depth: Int
    ) {
        if (addLooseExeFs(games, files)) {
            return
        }

        // The rest of a game folder is its romfs, which can hold thousands of files
        files.find { it.isDirectory && it.filename == "exefs" }?.let {
            addGamesRecursive(games, FileUtil.listFiles(it.uri), depth)
            return
        }

        if (depth <= 0) {
            return
        }

        files.forEach {
            if (it.isDirectory) {
                addGamesRecursive(games, FileUtil.listFiles(it.uri), depth - 1)
            } else if (externalContentExtensions.contains(FileUtil.getExtension(it.uri).lowercase())) {
                unsupportedGameCount++
            }
        }
    }

    /**
     * Switch 2 titles boot from a loose ExeFS, and the loader reads the files next to main and the
     * romfs directory next to exefs. A content uri has no containing directory, so these games are
     * kept by their real path, which needs All Files Access.
     */
    private fun addLooseExeFs(
        games: MutableList<Game>,
        files: Array<MinimalDocumentFile>
    ): Boolean {
        val main = files.find { it.filename == "main" } ?: return false
        if (files.none { it.filename == "main.npdm" }) {
            return false
        }

        if (!hasAllFilesAccess) {
            requestAllFilesAccess()
            return true
        }

        val path = PathUtil.getPathFromDocumentUri(main.uri) ?: return true
        if (!GameMetadata.getIsSwitch2(path)) {
            unsupportedGameCount++
            return true
        }

        val game = getGame(path.toUri(), true, false, getTitleDir(File(path))?.name) ?: return true
        if (games.none { it.path == game.path }) {
            games.add(game)
        }
        return true
    }

    /**
     * A dump can hold base and updates/<version> side by side, each with its own exefs, and only
     * some of them with a romfs. Those are one game, so list the newest copy that has a romfs.
     */
    private fun keepBestVersions(games: List<Game>): List<Game> =
        games.groupBy { getTitleDir(File(it.path)) to it.programId }.values.map { versions ->
            versions.maxWith(
                compareBy<Game>(
                    { hasRomFs(File(it.path)) },
                    { getContentDir(File(it.path))?.name?.toLongOrNull() ?: 0L }
                )
            )
        }

    private fun getContentDir(main: File): File? {
        val exeFsDir = main.parentFile
        return if (exeFsDir?.name == "exefs") exeFsDir.parentFile else exeFsDir
    }

    private fun getTitleDir(main: File): File? {
        val contentDir = getContentDir(main)
        val updatesDir = contentDir?.parentFile
        return when {
            contentDir?.name == "base" -> updatesDir
            updatesDir?.name == "updates" && contentDir.name.toLongOrNull() != null ->
                updatesDir.parentFile
            else -> contentDir
        }
    }

    // Matches the loader, which takes a .romfs file next to main or a romfs directory next to exefs
    private fun hasRomFs(main: File): Boolean =
        main.parentFile?.listFiles()?.any { it.extension == "romfs" } == true ||
            getContentDir(main)?.resolve("romfs")?.isDirectory == true

    private fun requestAllFilesAccess() {
        if (requestedAllFilesAccess) {
            return
        }
        requestedAllFilesAccess = true

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                YuzuApplication.appContext,
                R.string.all_files_permission_loose_exefs,
                Toast.LENGTH_LONG
            ).show()
            openAllFilesAccessSettings()
        }
    }

    fun openAllFilesAccessSettings() {
        val context = YuzuApplication.appContext
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun mountExternalContentDirectories(mountedContainerUris: MutableSet<String>) {
        val uniqueExternalContentDirs = linkedSetOf<String>()
        NativeConfig.getExternalContentDirs().forEach { externalDir ->
            if (externalDir.isNotEmpty()) {
                uniqueExternalContentDirs.add(externalDir)
            }
        }

        for (externalDir in uniqueExternalContentDirs) {
            val externalDirUri = externalDir.toUri()
            if (FileUtil.isTreeUriValid(externalDirUri)) {
                scanContentContainersRecursive(FileUtil.listFiles(externalDirUri), 3) {
                    val containerUri = it.uri.toString()
                    if (mountedContainerUris.add(containerUri)) {
                        NativeLibrary.addFileToFilesystemProvider(containerUri)
                    }
                }
            }
        }
    }

    private fun mountGameFolderContent(gameUri: Uri, mountedContainerUris: MutableSet<String>) {
        if (gameUri.scheme == "content") {
            val parentUri = getParentDocumentUri(gameUri) ?: return
            scanContentContainersRecursive(FileUtil.listFiles(parentUri), 1) {
                val containerUri = it.uri.toString()
                if (mountedContainerUris.add(containerUri)) {
                    NativeLibrary.addGameFolderFileToFilesystemProvider(containerUri)
                }
            }
            return
        }

        val gameFile = File(gameUri.path ?: gameUri.toString())
        val parentDir = gameFile.parentFile ?: return
        parentDir.listFiles()?.forEach { sibling ->
            if (!sibling.isFile) {
                return@forEach
            }

            val extension = sibling.extension.lowercase()
            if (externalContentExtensions.contains(extension)) {
                val containerUri = Uri.fromFile(sibling).toString()
                if (mountedContainerUris.add(containerUri)) {
                    NativeLibrary.addGameFolderFileToFilesystemProvider(containerUri)
                }
            }
        }
    }

    private fun getParentDocumentUri(uri: Uri): Uri? {
        return try {
            val documentId = DocumentsContract.getDocumentId(uri)
            val separatorIndex = documentId.lastIndexOf('/')
            if (separatorIndex == -1) {
                null
            } else {
                val parentDocumentId = documentId.substring(0, separatorIndex)
                DocumentsContract.buildDocumentUriUsingTree(uri, parentDocumentId)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getGame(
        uri: Uri,
        addedToLibrary: Boolean,
        registerFilesystemProvider: Boolean = true,
        fallbackTitle: String? = null
    ): Game? {
        val filePath = uri.toString()
        if (!GameMetadata.getIsValid(filePath)) {
            return null
        }

        if (registerFilesystemProvider) {
            // Needed to update installed content information
            NativeLibrary.addFileToFilesystemProvider(filePath)
        }

        var name = GameMetadata.getTitle(filePath)

        // If the game's title field is empty, use the filename.
        if (name.isEmpty()) {
            name = fallbackTitle ?: FileUtil.getFilename(uri)
        }
        var programId = GameMetadata.getProgramId(filePath)

        // If the game's ID field is empty, use the filename without extension.
        if (programId.isEmpty()) {
            programId = name.substring(0, name.lastIndexOf("."))
        }

        val newGame = Game(
            name,
            filePath,
            programId,
            GameMetadata.getDeveloper(filePath),
            GameMetadata.getVersion(filePath, false),
            GameMetadata.getIsHomebrew(filePath)
        )

        if (addedToLibrary) {
            val addedTime = preferences.getLong(newGame.keyAddedToLibraryTime, 0L)
            if (addedTime == 0L) {
                preferences.edit()
                    .putLong(newGame.keyAddedToLibraryTime, System.currentTimeMillis())
                    .apply()
            }
        }

        return newGame
    }
}
