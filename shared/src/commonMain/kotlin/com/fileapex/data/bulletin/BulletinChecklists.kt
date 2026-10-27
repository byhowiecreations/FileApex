package com.fileapex.data.bulletin

import com.fileapex.di.FileApexServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BulletinChecklists {
    suspend fun create(title: String, lines: List<String>) {
        val repository = FileApexServices.bulletinBoardRepository
        val (message, items) = repository.ingestLocalChecklist(title, lines)
        val engine = FileApexServices.bulletinSyncEngineOrNull() ?: return
        withContext(Dispatchers.IO) {
            engine.publishMessage(message)
            items.forEach { engine.publishChecklistItem(it) }
        }
    }

    suspend fun add(messageId: String, text: String) = send(FileApexServices.bulletinBoardRepository.addChecklistItem(messageId, text))

    suspend fun setDone(itemId: String, done: Boolean) = send(FileApexServices.bulletinBoardRepository.setChecklistItemDone(itemId, done))

    suspend fun remove(itemId: String) = send(FileApexServices.bulletinBoardRepository.removeChecklistItem(itemId))

    private suspend fun send(item: ChecklistItemEntity?) {
        if (item != null) withContext(Dispatchers.IO) { FileApexServices.bulletinSyncEngineOrNull()?.publishChecklistItem(item) }
    }
}
