package app.lawnchair.data.folder.service

import android.content.Context
import androidx.core.content.edit
import android.content.pm.LauncherApps
import app.lawnchair.data.AppDatabase
import app.lawnchair.data.folder.FolderEntry
import app.lawnchair.data.folder.FolderInfoEntity
import app.lawnchair.data.folder.FolderItemEntity
import app.lawnchair.preferences.PreferenceManager
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

@LauncherAppSingleton
class FolderService @Inject constructor(
    @ApplicationContext private val context: Context,
) : SafeCloseable {

    private val folderDao = AppDatabase.INSTANCE.get(context).folderDao()
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val appFilter = AppFilter(context)
    private val prefs = PreferenceManager.getInstance(context)

    fun getFoldersFlow(): Flow<List<FolderEntry>> {
        return folderDao.getAllFoldersWithItems()
            .onStart { migrateLegacyFolderRanks() }
            .map { list ->
                list.map { it.toFolderEntry() }
            }
    }

    suspend fun updateFolderWithItems(folderInfoId: Int, title: String, componentKeys: List<String>) = withContext(Dispatchers.IO) {
        val items = componentKeys.mapIndexed { index, componentKey ->
            FolderItemEntity(
                folderId = folderInfoId,
                rank = index,
                componentKey = componentKey,
            )
        }
        folderDao.replaceFolderItems(folderInfoId, title, items)
    }

    suspend fun saveFolderInfo(title: String) = withContext(Dispatchers.IO) {
        val rank = (folderDao.getMaxFolderRank() ?: -1) + 1
        folderDao.insertFolder(FolderInfoEntity(title = title, rank = rank))
    }

    suspend fun updateFolderItems(folderInfoId: Int, items: List<ItemInfo>): Unit = withContext(Dispatchers.IO) {
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
        val folder = folderDao.getFolder(folderInfoId)
        if (folder != null) {
            folderDao.updateFolderInfo(folderInfoId, folder.title, folder.hide)
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

    suspend fun updateFolderInfo(folderInfo: FolderInfo, hide: Boolean = false): Unit = withContext(Dispatchers.IO) {
        folderDao.updateFolderInfo(folderInfo.id, folderInfo.title.toString(), hide)
    }

    suspend fun renameFolderInfo(folderId: Int, title: String) = withContext(Dispatchers.IO) {
        folderDao.updateFolderTitle(folderId, title)
    }

    suspend fun deleteFolderInfo(id: Int) = withContext(Dispatchers.IO) {
        folderDao.deleteFolder(id)
    }

    suspend fun updateFolderRanks(orderedIds: List<Int>) = withContext(Dispatchers.IO) {
        folderDao.updateFolderRanks(orderedIds)
    }

    private suspend fun migrateLegacyFolderRanks() = withContext(Dispatchers.IO) {
        val legacyOrder = prefs.sp.getString(LEGACY_FOLDER_ORDER_STRING, "") ?: ""
        if (legacyOrder.isNotEmpty()) {
            val legacyIds = stringToIntList(legacyOrder).distinct()
            val currentIds = folderDao.getAllFolderIds()
            val validLegacyIds = legacyIds.filter { it in currentIds }
            val missingCurrentIds = currentIds.filter { it !in validLegacyIds }
            val orderedIds = validLegacyIds + missingCurrentIds

            if (orderedIds.isNotEmpty()) {
                folderDao.updateFolderRanks(orderedIds)
            }
            prefs.sp.edit {
                putString(LEGACY_FOLDER_ORDER_STRING, "")
            }
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

    private fun stringToIntList(string: String, delimiter: String = ","): List<Int> {
        return string.takeIf { it.isNotBlank() }
            ?.split(delimiter)
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()
    }

    private fun FolderWithItems.toFolderEntry() = FolderEntry(
        id = folder.id,
        title = folder.title,
        hide = folder.hide,
        rank = folder.rank,
        itemComponentKeys = items
            .sortedBy { it.rank }
            .mapNotNull { it.componentKey },
    )

    override fun close() {
    }

    companion object {
        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getFolderService)
    }
}

private const val LEGACY_FOLDER_ORDER_STRING = "pref_drawerListOrder"
