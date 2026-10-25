package io.legado.app.ui.book.group

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.sync.SyncManager

class GroupViewModel(application: Application) : BaseViewModel(application) {

    fun upGroup(vararg bookGroup: BookGroup, finally: (() -> Unit)? = null) {
        execute {
            val oldNames = bookGroup.mapNotNull { appDb.bookGroupDao.getByID(it.groupId) }
            appDb.bookGroupDao.update(*bookGroup)
            oldNames.forEach { old ->
                val new = bookGroup.firstOrNull { it.groupId == old.groupId }
                if (new != null && new.groupName != old.groupName) {
                    SyncManager.enqueueGroup(old, removed = true)
                }
            }
            bookGroup.filter { it.groupId > 0 }.forEach { SyncManager.enqueueGroup(it) }
        }.onFinally {
            finally?.invoke()
        }
    }

    fun addGroup(
        groupName: String,
        bookSort: Int,
        enableRefresh: Boolean,
        onlyUpdateRead: Boolean,
        cover: String?,
        finally: () -> Unit
    ) {
        execute {
            val groupId = appDb.bookGroupDao.getUnusedId()
            val bookGroup = BookGroup(
                groupId = groupId,
                groupName = groupName,
                cover = cover,
                bookSort = bookSort,
                enableRefresh = enableRefresh,
                onlyUpdateRead = onlyUpdateRead,
                order = appDb.bookGroupDao.maxOrder.plus(1)
            )
            appDb.bookGroupDao.getByID(groupId) ?: appDb.bookDao.removeGroup(groupId)
            appDb.bookGroupDao.insert(bookGroup)
            if (bookGroup.groupId > 0) SyncManager.enqueueGroup(bookGroup)
        }.onFinally {
            finally()
        }
    }

    fun delGroup(bookGroup: BookGroup, finally: () -> Unit) {
        execute {
            if (bookGroup.groupId > 0) SyncManager.enqueueGroup(bookGroup, removed = true)
            appDb.bookGroupDao.delete(bookGroup)
            appDb.bookDao.removeGroup(bookGroup.groupId)
        }.onFinally {
            finally()
        }
    }


}