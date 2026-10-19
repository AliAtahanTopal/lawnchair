package app.lawnchair.data.folder.model

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lawnchair.data.folder.FolderEntry
import app.lawnchair.data.folder.service.FolderService
import app.lawnchair.preferences2.ReloadHelper
import com.android.launcher3.LauncherAppState
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FolderViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository: FolderService = FolderService.INSTANCE.get(application)

    val folders: StateFlow<List<FolderEntry>?> = repository.getFoldersFlow()
        .distinctUntilChanged()
        .catch { exception ->
            Log.e("FolderViewModel", "Error in folders flow", exception)
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null,
        )

    val allFolderPackages: StateFlow<Set<String>?> = folders
        .map { folderList ->
            folderList?.flatMap { folder -> folder.itemComponentKeys }
                ?.map { key -> key.substringBefore("/") }
                ?.toSet()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null,
        )

    private val reloadHelper = ReloadHelper(application)

    fun getFolderFlowForId(folderId: Int): Flow<FolderEntry?> {
        return folders.map { list -> list?.find { it.id == folderId } }
    }

    fun renameFolder(folderId: Int, title: String) {
        viewModelScope.launch {
            repository.renameFolderInfo(folderId, title)
            reloadHelper.reloadGrid()
        }
    }

    fun renameFolder(folderInfo: FolderInfo, hide: Boolean) {
        viewModelScope.launch {
            repository.updateFolderInfo(folderInfo, hide)
            reloadHelper.reloadGrid()
        }
    }

    fun updateFolderItems(id: Int, title: String, componentKeys: List<String>) {
        viewModelScope.launch {
            repository.updateFolderWithItems(id, title, componentKeys)

            // Sync to home screen folders
            val appState = LauncherAppState.getInstance(getApplication())
            val model = appState.model
            model.enqueueModelUpdateTask { taskController, dataModel, allApps ->
                val foldersToUpdate = dataModel.itemsIdMap.filterIsInstance<FolderInfo>()
                    .filter { it.syncId == id }

                foldersToUpdate.forEach { homeFolder ->
                    val modelWriter = taskController.getModelWriter()

                    // Clear existing contents in DB and memory
                    val oldContents = ArrayList(homeFolder.getContents())
                    oldContents.forEach { item ->
                        modelWriter.deleteItemFromDatabase(item, "Synced Folder update")
                    }
                    homeFolder.getContents().clear()

                    // Add new contents
                    componentKeys.forEachIndexed { index, keyString ->
                        val componentKey = com.android.launcher3.util.ComponentKey.fromString(keyString) ?: return@forEachIndexed
                        val app = allApps.data.find {
                            it.componentName == componentKey.componentName && it.user == componentKey.user
                        }
                        val item = app?.makeWorkspaceItem(appState.context)
                        if (item != null) {
                            item.rank = index
                            item.container = homeFolder.id
                            homeFolder.add(item)
                            modelWriter.addItemToDatabase(item, homeFolder.id, 0, 0, 0)
                        }
                    }
                    modelWriter.updateItemInDatabase(homeFolder)
                }
                if (foldersToUpdate.isNotEmpty()) {
                    taskController.bindUpdatedWorkspaceItems(foldersToUpdate)
                }
            }

            reloadHelper.reloadGrid()
        }
    }

    fun createFolder(title: String) {
        viewModelScope.launch {
            repository.saveFolderInfo(title)
        }
    }

    fun createFolder(folderInfo: FolderInfo, onCreated: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val id = repository.saveFolderInfo(folderInfo)
            onCreated(id.toInt())
        }
    }

    fun deleteFolder(id: Int) {
        viewModelScope.launch {
            repository.deleteFolderInfo(id)
            reloadHelper.reloadGrid()
        }
    }

    fun updateFolderOrder(orderedIds: List<Int>) {
        viewModelScope.launch {
            repository.updateFolderRanks(orderedIds)
        }
    }
}
