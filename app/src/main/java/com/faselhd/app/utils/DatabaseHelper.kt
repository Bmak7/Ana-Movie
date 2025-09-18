package com.faselhd.app.utils

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.faselhd.app.db.AppDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class DatabaseHelper(private val context: Context) {

    companion object {
        const val DATABASE_NAME = "anime_app_database"
    }

    fun exportDatabase(launcher: ActivityResultLauncher<String>) {
        try {
            // We use the Storage Access Framework to let the user choose the destination.
            launcher.launch("anime_app_backup.db")
        } catch (e: Exception) {
            Toast.makeText(context, "Error initiating export: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun importDatabase(launcher: ActivityResultLauncher<Array<String>>) {
        try {
            // We use the Storage Access Framework to let the user pick the backup file.
            launcher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(context, "Error initiating import: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun performExport(uri: Uri): Boolean {
        val currentDb = context.getDatabasePath(DATABASE_NAME)
        if (!currentDb.exists()) {
            Toast.makeText(context, "Database not found!", Toast.LENGTH_SHORT).show()
            return false
        }

        try {
            context.contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { outputStream ->
                    FileInputStream(currentDb).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }
    }

    fun performImport(uri: Uri): Boolean {
        val currentDbPath = context.getDatabasePath(DATABASE_NAME)

        try {
            // VERY IMPORTANT: Close the database connection before replacing the file.
            AppDatabase.closeDatabase()

            // Overwrite the current database with the imported one.
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(currentDbPath).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            // If import fails, try to restore the original state if possible, though it might be corrupted.
            // For simplicity, we just show an error.
            return false
        }
    }
}