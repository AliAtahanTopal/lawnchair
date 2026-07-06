package app.lawnchair.data.folder.service

import android.content.Context
import android.content.pm.LauncherApps
import android.util.Log
import app.lawnchair.data.AppDatabase
import app.lawnchair.data.folder.FolderInfoEntity
import app.lawnchair.data.toEntity
import com.android.launcher3.AppFilter
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.SafeCloseable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@LauncherAppSingleton
class FolderService @Inject constructor(
    @ApplicationContext private val context: Context,
) : SafeCloseable {

    private val folderDao = AppDatabase.INSTANCE.get(context).folderDao()
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val appFilter = AppFilter(context)

    fun getFoldersFlow(): Flow<List<FolderInfo>> {
        return folderDao.getAllFolders().map { folderEntities ->
            folderEntities.mapNotNull { folderEntity ->
                getFolderInfo(folderEntity.id, true)
            }
        }
    }

    suspend fun updateFolderWithItems(folderInfoId: Int, title: String, appInfos: List<AppInfo>) = withContext(Dispatchers.IO) {
        folderDao.insertFolderWithItems(
            FolderInfoEntity(id = folderInfoId, title = title),
            appInfos.mapIndexed { index, appInfo ->
                appInfo.toEntity(folderInfoId).copy(rank = index)
            }.toList(),
        )
    }

    suspend fun updateFolderItems(folderInfoId: Int, items: List<ItemInfo>) = withContext(Dispatchers.IO) {
        folderDao.deleteFolderItemsByFolderId(folderInfoId)
        var rank = 0
        val entities = items.mapNotNull { item ->
            val key = item.componentKey?.toString() ?: return@mapNotNull null
            app.lawnchair.data.folder.FolderItemEntity(
                folderId = folderInfoId,
                componentKey = key,
                rank = rank++,
            )
        }
        folderDao.insertFolderItems(entities)
        folderDao.getFolder(folderInfoId)?.let {
            folderDao.updateFolderInfo(folderInfoId, it.title, it.hide)
        }
    }

    fun syncFolder(folderInfo: FolderInfo) {
        val syncId = folderInfo.syncId
        if (syncId == 0) return
        val items = folderInfo.getContents().toList()
        val title = folderInfo.title?.toString() ?: ""

        CoroutineScope(Dispatchers.IO).launch {
            updateFolderItems(syncId, items)
            folderDao.updateFolderInfo(syncId, title, false)

            // Trigger update on other home screen folders with same syncId
            val launcherModel = com.android.launcher3.LauncherAppState.getInstance(context).model
            launcherModel.enqueueModelUpdateTask { taskController, dataModel, _ ->
                val foldersToUpdate = dataModel.itemsIdMap.filterIsInstance<FolderInfo>()
                    .filter { it.syncId == syncId && it.id != folderInfo.id }

                foldersToUpdate.forEach { otherFolder ->
                    val modelWriter = taskController.getModelWriter()

                    // Sync contents
                    val oldContents = ArrayList(otherFolder.getContents())
                    oldContents.forEach { item ->
                        modelWriter.deleteItemFromDatabase(item, "Synced Folder sibling update")
                    }
                    otherFolder.getContents().clear()

                    val newItemsToAdd = mutableListOf<com.android.launcher3.model.data.ItemInfo>()
                    items.forEachIndexed { index, item ->
                        val newItem = when (item) {
                            is com.android.launcher3.model.data.WorkspaceItemFactory -> item.makeWorkspaceItem(context)
                            is com.android.launcher3.model.data.WorkspaceItemInfo -> item.clone()
                            else -> null
                        }
                        if (newItem != null) {
                            newItem.rank = index
                            newItem.container = otherFolder.id
                            otherFolder.add(newItem)
                            newItemsToAdd.add(newItem)
                        }
                    }
                    if (newItemsToAdd.isNotEmpty()) {
                        modelWriter.addItemsToDatabase(newItemsToAdd)
                    }

                    // Sync title
                    otherFolder.title = title
                    modelWriter.updateItemInDatabase(otherFolder)
                }

                if (foldersToUpdate.isNotEmpty()) {
                    taskController.bindUpdatedWorkspaceItems(foldersToUpdate)
                }
            }
        }
    }

    suspend fun saveFolderInfo(folderInfo: FolderInfo): Long = withContext(Dispatchers.IO) {
        folderDao.insertFolder(FolderInfoEntity(title = folderInfo.title.toString()))
    }

    suspend fun updateFolderInfo(folderInfo: FolderInfo, hide: Boolean = false) = withContext(Dispatchers.IO) {
        folderDao.updateFolderInfo(folderInfo.id, folderInfo.title.toString(), hide)
    }

    suspend fun deleteFolderInfo(id: Int) = withContext(Dispatchers.IO) {
        folderDao.deleteFolder(id)
    }

    suspend fun getFolderInfo(folderId: Int, hasId: Boolean = false): FolderInfo? = withContext(Dispatchers.Default) {
        folderDao.getFolderWithItems(folderId)?.let {
            mapToFolderInfo(it, hasId)
        }
    }

    private fun mapToFolderInfo(folderWithItems: FolderWithItems, hasId: Boolean): FolderInfo? {
        return try {
            val domainFolderInfo = FolderInfo().apply {
                // if no id, launcher automatically creates an id for this
                if (hasId) id = folderWithItems.folder.id
                title = folderWithItems.folder.title
            }

            folderWithItems.items.sortedBy { it.rank }.forEach { itemEntity ->
                // Consider caching toItemInfo results if componentKey lookups are slow
                // and items don't change frequently without folder data changing
                toItemInfo(itemEntity.componentKey)?.let { appInfo ->
                    domainFolderInfo.add(appInfo)
                }
            }
            domainFolderInfo
        } catch (e: Exception) {
            Log.e("FolderService", "Failed to map FolderWithItems for id: ${folderWithItems.folder.id}", e)
            null
        }
    }

    private fun toItemInfo(componentKeyStr: String?): AppInfo? {
        val componentKey = componentKeyStr?.let { ComponentKey.fromString(it) } ?: return null
        if (launcherApps != null) {
            val activityList = launcherApps.getActivityList(componentKey.componentName.packageName, componentKey.user)
            val activityInfo = activityList.find { it.componentName == componentKey.componentName }
            if (activityInfo != null && appFilter.shouldShowApp(activityInfo.componentName)) {
                return AppInfo(context, activityInfo, componentKey.user)
            }
        }
        return null
    }

    suspend fun getAllFolders(): List<FolderInfo> = withContext(Dispatchers.Main) {
        try {
            val folderEntities = folderDao.getAllFolders().firstOrNull() ?: emptyList()
            folderEntities.mapNotNull { folderEntity ->
                getFolderInfo(folderEntity.id, true)
            }
        } catch (e: Exception) {
            Log.e("FolderService", "Failed to get all folders", e)
            emptyList()
        }
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    companion object {
        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getFolderService)
    }
}
