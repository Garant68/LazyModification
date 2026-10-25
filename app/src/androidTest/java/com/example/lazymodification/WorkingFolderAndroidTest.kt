package com.example.lazymodification

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lazymodification.utils.StorageRoots
import com.example.lazymodification.utils.WorkingFolderHelper
import android.os.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkingFolderAndroidTest {
    @Test
    fun workingFolderPersistsAndClears() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "working-folder-test").apply { mkdirs() }

        WorkingFolderHelper.save(context, folder)
        assertEquals(folder.absolutePath, WorkingFolderHelper.getPath(context))
        assertEquals(folder.absolutePath, WorkingFolderHelper.getFolder(context)?.absolutePath)

        WorkingFolderHelper.clear(context)
        assertNull(WorkingFolderHelper.getPath(context))
        assertNull(WorkingFolderHelper.getFolder(context))
    }

    @Test
    fun storageRootsContainsInternalStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roots = StorageRoots.getRoots(context)
        assertTrue(roots.isNotEmpty())
        assertNotNull(
            roots.firstOrNull {
                it.dir.absolutePath == Environment.getExternalStorageDirectory().absolutePath
            }
        )
    }
}
