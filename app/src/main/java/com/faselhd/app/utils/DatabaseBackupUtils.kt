//package com.faselhd.app.utils // Or your appropriate package
//
//import android.content.Context
//import android.net.Uri
//import android.widget.Toast
//import com.faselhd.app.db.AppDatabase
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import java.io.File
//import java.io.FileInputStream
//import java.io.FileOutputStream
//
//object DatabaseBackupUtils {
//
//    /**
//     * Exports the Room database to a user-selected location.
//     * This function should be called from a background coroutine.
//     */
//    suspend fun exportDatabase(context: Context, destinationUri: Uri) {
//        withContext(Dispatchers.IO) {
//            val dbName = AppDatabase.DB_NAME
//            val currentDbFile = context.getDatabasePath(dbName)
//
//            // Make sure the database is closed before copying
//            AppDatabase.closeInstance()
//
//            try {
//                context.contentResolver.openFileDescriptor(destinationUri, "w")?.use { pfd ->
//                    FileOutputStream(pfd.fileDescriptor).use { outputStream ->
//                        FileInputStream(currentDbFile).use { inputStream ->
//                            inputStream.copyTo(outputStream)
//                        }
//                    }
//                }
//                withContext(Dispatchers.Main) {
//                    Toast.makeText(context, "Export successful!", Toast.LENGTH_SHORT).show()
//                }
//            } catch (e: Exception) {
//                e.printStackTrace()
//                withContext(Dispatchers.Main) {
//                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
//                }
//            }
//        }
//    }
//
//    /**
//     * Imports a database from a user-selected file, replacing the current database.
//     * This function should be called from a background coroutine.
//     */
//    suspend fun importDatabase(context: Context, sourceUri: Uri): Boolean {
//        var success = false
//        withContext(Dispatchers.IO) {
//            val dbName = AppDatabase.DB_NAME
//            val currentDbFile = context.getDatabasePath(dbName)
//            val walFile = File(currentDbFile.parent, "$dbName-wal")
//            val shmFile = File(currentDbFile.parent, "$dbName-shm")
//
//            // Make sure the database is closed before replacing its files
//            AppDatabase.closeInstance()
//
//            try {
//                // Delete old journal files
//                if (walFile.exists()) walFile.delete()
//                if (shmFile.exists()) shmFile.delete()
//
//                // Copy the new database file over
//                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
//                    FileOutputStream(currentDbFile).use { outputStream ->
//                        inputStream.copyTo(outputStream)
//                    }
//                }
//                success = true
//                withContext(Dispatchers.Main) {
//                    Toast.makeText(context, "Import successful! Please restart the app.", Toast.LENGTH_LONG).show()
//                }
//            } catch (e: Exception) {
//                e.printStackTrace()
//                withContext(Dispatchers.Main) {
//                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
//                }
//            }
//        }
//        return success
//    }
//}