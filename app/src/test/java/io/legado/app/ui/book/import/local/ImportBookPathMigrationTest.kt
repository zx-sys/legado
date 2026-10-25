package io.legado.app.ui.book.import.local

import io.legado.app.model.localBook.isMissingLocalBookFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException

class ImportBookPathMigrationTest {

    @Test
    fun `automatic restore only handles confirmed missing files`() {
        assertTrue(isMissingLocalBookFile(true, false, FileNotFoundException()))
        assertTrue(
            isMissingLocalBookFile(
                true,
                false,
                IllegalArgumentException(
                    "Failed to determine if child is child of parent: " +
                            "java.io.FileNotFoundException: Missing file for child"
                )
            )
        )
        assertFalse(isMissingLocalBookFile(true, false, IllegalArgumentException()))
        assertFalse(isMissingLocalBookFile(true, false, IOException()))
        assertFalse(isMissingLocalBookFile(true, false, SecurityException()))
        assertFalse(isMissingLocalBookFile(false, false, CancellationException()))
        assertTrue(isMissingLocalBookFile(false, false, IOException()))
        assertFalse(isMissingLocalBookFile(false, true, FileNotFoundException()))
    }

    @Test
    fun `existing local book path is rebound without changing book identity`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/book/import/local/ImportBookActivity.kt"
        )
        val startRead = source.substringAfter("override fun startRead(fileDoc: FileDoc)")

        assertTrue(startRead.contains("startReadJob?.isActive == true"))
        assertTrue(startRead.contains("startReadJob = lifecycleScope.launch(IO)"))
        assertTrue(startRead.contains("appDb.bookDao.getBook(filePath)"))
        assertTrue(startRead.contains("appDb.bookDao.getBookByFileName(fileDoc.name)"))
        assertTrue(startRead.contains("book.removeLocalUriCache()"))
        assertTrue(startRead.contains("book.cacheLocalUri(fileDoc.uri)"))
        assertFalse(startRead.contains("book.bookUrl = filePath"))
        assertFalse(startRead.contains("appDb.bookDao.replace("))
        assertFalse(startRead.contains("BookHelp.updateCacheFolder("))
        assertTrue(startRead.contains("LocalBook.withParserCacheInvalidated("))
        assertTrue(startRead.contains("withContext(Main)"))
        assertTrue(startRead.contains("if (!isFinishing && !isDestroyed)"))
        assertTrue(
            startRead.indexOf("appDb.bookDao.getBook(filePath)") <
                    startRead.indexOf("appDb.bookDao.getBookByFileName(fileDoc.name)")
        )
        assertTrue(
            startRead.indexOf("book.removeLocalUriCache()") <
                    startRead.indexOf("book.cacheLocalUri(fileDoc.uri)")
        )
    }

    @Test
    fun `local scan matches shelf files without per-file queries`() {
        val shelfFiles = ImportBookShelfFiles(
            fileNames = listOf("book.txt"),
            alternateOrigins = listOf(
                "loc_book::ARCHIVE.ZIP",
            ),
        )

        assertTrue("book.txt" in shelfFiles)
        assertTrue("archive.zip" in shelfFiles)
        assertFalse("BOOK.TXT" in shelfFiles)
        assertFalse("missing.txt" in shelfFiles)

        val importBook = readProjectFile(
            "src/main/java/io/legado/app/ui/book/import/local/ImportBook.kt"
        )
        val viewModel = readProjectFile(
            "src/main/java/io/legado/app/ui/book/import/local/ImportBookViewModel.kt"
        )
        assertFalse(importBook.contains("LocalBook.isOnBookShelf"))
        assertFalse(viewModel.contains("private var shelfFiles"))
        assertTrue(viewModel.contains("appDb.bookDao.localBookFileNames"))
        assertTrue(viewModel.contains("appDb.bookDao.localBookAlternateOrigins"))
    }

    @Test
    fun `partial imports only mark successful files on shelf`() {
        val localBook = readProjectFile(
            "src/main/java/io/legado/app/model/localBook/LocalBook.kt"
        ).substringAfter("fun importFiles(uris: List<Uri>)")
            .substringBefore("private fun analyzeNameAuthor")
        val viewModel = readProjectFile(
            "src/main/java/io/legado/app/ui/book/import/local/ImportBookViewModel.kt"
        ).substringAfter("fun addToBookshelf(")
            .substringBefore("fun deleteDoc(")
        val activity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/import/local/ImportBookActivity.kt"
        ).substringAfter("override fun onClickSelectBarMainAction()")
            .substringBefore("private fun initView()")

        assertTrue(localBook.contains("val importedUris = linkedSetOf<Uri>()"))
        assertTrue(localBook.contains("onBookImported = importedBooks::add"))
        assertTrue(localBook.contains("importedUris.add(uri)"))
        assertTrue(localBook.contains("var firstError: Throwable? = null"))
        assertTrue(localBook.contains("if (firstError == null) firstError = it"))
        assertTrue(localBook.contains("throw firstError"))
        assertTrue(localBook.contains("if (importedBooks.isEmpty())"))
        assertTrue(localBook.contains("return importedUris to importedBooks"))
        assertTrue(
            localBook.indexOf("kotlin.runCatching") <
                    localBook.indexOf("FileDoc.fromUri(uri, false)")
        )
        assertTrue(viewModel.contains(".onSuccess { (importedUris, importedBookCount, groupError) ->"))
        assertFalse(viewModel.contains(".onFinally"))
        assertTrue(viewModel.contains("it.localizedMessage"))
        assertTrue(viewModel.contains("importedUris.size == fileUris.size"))
        assertTrue(activity.contains("it.file.uri in importedUris"))
    }

    @Test
    fun `batch import groups only successful book products`() {
        val localBook = readProjectFile(
            "src/main/java/io/legado/app/model/localBook/LocalBook.kt"
        )
        val viewModel = readProjectFile(
            "src/main/java/io/legado/app/ui/book/import/local/ImportBookViewModel.kt"
        )
        val bookDao = readProjectFile(
            "src/main/java/io/legado/app/data/dao/BookDao.kt"
        )
        val activity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/import/local/ImportBookActivity.kt"
        )

        assertTrue(localBook.contains("onBookImported: (Book) -> Unit = {}"))
        assertTrue(localBook.contains("}.also(onBookImported)"))
        assertTrue(viewModel.contains("appDb.runInTransaction"))
        assertTrue(viewModel.contains("if (!groupDao.canAddGroup)"))
        assertTrue(viewModel.contains("groupDao.getUnusedId()"))
        assertTrue(viewModel.contains("groupName = name"))
        assertTrue(viewModel.contains("val (importedUris, importedBooks)"))
        assertTrue(viewModel.contains("importedBooks.map { it.bookUrl }.chunked(900).forEach"))
        assertTrue(viewModel.contains("bookDao.addGroup(it, groupId)"))
        assertFalse(viewModel.contains("bookDao.update("))
        assertTrue(bookDao.contains("set `group` = `group` | :groupId"))
        assertTrue(bookDao.contains("where bookUrl in (:bookUrls)"))
        assertTrue(viewModel.contains("}.exceptionOrNull()"))
        assertTrue(viewModel.contains("Triple(importedUris, importedBooks.size, groupError)"))
        assertTrue(activity.contains("selected.size < 2 || isRecursiveScan"))
        assertTrue(activity.contains("isRecursiveScan = true"))
        assertTrue(activity.contains("isRecursiveScan = false"))
        assertTrue(activity.contains("dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false"))
    }

    @Test
    fun `new local import never replaces a book with the same identity`() {
        val localBook = readProjectFile(
            "src/main/java/io/legado/app/model/localBook/LocalBook.kt"
        ).substringAfter("fun importFile(uri: Uri): Book")
            .substringBefore("fun upBookInfo(book: Book)")
        val bookDao = readProjectFile(
            "src/main/java/io/legado/app/data/dao/BookDao.kt"
        )
        val book = readProjectFile(
            "src/main/java/io/legado/app/data/entities/Book.kt"
        )

        assertTrue(localBook.contains("appDb.bookDao.insertIgnore(book) == -1L"))
        assertTrue(localBook.contains("R.string.local_book_identity_conflict"))
        assertFalse(localBook.contains("appDb.bookDao.insert(book)"))
        assertTrue(bookDao.contains("@Insert(onConflict = OnConflictStrategy.IGNORE)"))
        assertTrue(bookDao.contains("fun insertIgnore(book: Book): Long"))
        assertTrue(book.contains("Index(value = [\"name\", \"author\"], unique = true)"))

        val baseActivity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/import/BaseImportBookActivity.kt"
        )
        val archiveImport = baseActivity.substringAfter("private inline fun addArchiveToBookShelf(")
            .substringBefore("private fun showImportAlert(")
        assertTrue(archiveImport.contains("catch (error: Exception)"))
        assertTrue(archiveImport.contains("toastOnUi("))
        assertTrue(archiveImport.contains("error.localizedMessage"))
    }

    @Test
    fun `local file consumers use the rebound uri`() {
        val extensions = readProjectFile(
            "src/main/java/io/legado/app/help/book/BookExtensions.kt"
        ).substringAfter("fun Book.getLocalUri(): Uri")
            .substringBefore("fun Book.getArchiveUri(): Uri?")
        assertFalse(extensions.contains("bookUrl = fileDoc.toString()"))
        assertFalse(extensions.contains("save()"))
        assertEquals(
            2,
            Regex("cacheLocalUri\\(fileDoc\\.uri\\)").findAll(extensions).count()
        )

        val localBook = readProjectFile(
            "src/main/java/io/legado/app/model/localBook/LocalBook.kt"
        )
        val inputStream = localBook.substringAfter("fun getBookInputStream(book: Book)")
            .substringBefore("fun getLastModified(book: Book)")
        val lastModified = localBook.substringAfter("fun getLastModified(book: Book)")
            .substringBefore("@Throws(TocEmptyException::class)")
        val deleteBook = localBook.substringAfter("fun deleteBook(book: Book")
            .substringBefore("suspend fun saveBookFile(")
        val archiveRestore = localBook.substringAfter("private fun restoreArchiveBookFile")
            .substringBefore("//文件类书源")
        assertTrue(inputStream.contains("restoreArchiveBookFile(book, localArchiveUri)"))
        assertTrue(inputStream.contains("isMissingLocalBookFile("))
        assertTrue(inputStream.contains("throw readError"))
        assertFalse(inputStream.contains("downloadRemoteBook("))
        assertFalse(inputStream.contains("importArchiveFile("))
        assertTrue(lastModified.contains("book.getLocalUri()"))
        assertFalse(lastModified.contains("book.bookUrl"))
        assertTrue(deleteBook.contains("FileDoc.fromUri(book.getLocalUri(), false).delete()"))
        assertFalse(deleteBook.contains("book.bookUrl"))
        assertTrue(archiveRestore.contains("book.cacheLocalUri(fileUri)"))
        assertFalse(archiveRestore.contains("book.bookUrl"))
        assertFalse(archiveRestore.contains("book.origin ="))
        assertFalse(archiveRestore.contains("importArchiveFile("))

        assertFalse(localBook.contains("fun downloadRemoteBook"))

        val bookInfoActivity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
        )
        val localBookSize = bookInfoActivity.substringAfter("private fun upKinds")
            .substringBefore("if (kinds.isEmpty())")
        assertTrue(localBookSize.contains("val size = try"))
        assertTrue(localBookSize.contains("FileDoc.fromUri(book.getLocalUri(), false).size"))
        assertTrue(localBookSize.contains("catch (e: Exception)"))
        assertTrue(localBookSize.contains("currentCoroutineContext().ensureActive()"))
        assertTrue(localBookSize.contains("0L"))
        assertFalse(bookInfoActivity.contains("FileDoc.fromFile(book.bookUrl).size"))

        val bookInfoViewModel = readProjectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt"
        ).substringAfter("fun refreshBook(book: Book)")
            .substringBefore("fun loadBookInfo(")
        assertFalse(bookInfoViewModel.contains("downloadRemoteBook("))
        assertFalse(bookInfoViewModel.contains("book.bookUrl ="))

        val readActivity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/BaseReadBookActivity.kt"
        ).substringAfter("private val selectBookFolderResult")
            .substringBefore("override fun onCreate")
        assertTrue(readActivity.contains("AppConfig.importBookPath = uri.toString()"))
        assertTrue(readActivity.contains("LocalBook.withParserCacheInvalidated(book)"))
        assertTrue(readActivity.contains("book.cacheLocalUri(doc.uri)"))
        assertFalse(readActivity.contains("book.bookUrl ="))
        assertFalse(readActivity.contains("book.save()"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
