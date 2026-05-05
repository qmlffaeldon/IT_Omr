package com.ntc.roec_scanner.modules

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.media.MediaHttpUploader
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import java.text.SimpleDateFormat
import java.util.Locale

class GoogleDriveManager(private val context: Context) {

    private val driveScope = Scope(DriveScopes.DRIVE)

    fun getSignInClient(): GoogleSignInClient {
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(driveScope)
            .build()
        return GoogleSignIn.getClient(context, signInOptions)
    }

    // Helper to check if the user is already logged in
    fun getSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    // This builds the actual "Drive" object we use to upload/rename files
    fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE)
        )
        credential.selectedAccount = account.account

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("ROEC Scanner")
            .build()
    }

    /**
     * Checks if a folder exists. If not, creates it.
     * Returns the unique Google Drive Folder ID.
     */
    fun getOrCreateFolder(driveService: Drive, folderName: String): String? {
        try {
            // 1. Search for existing folder
            val query = "mimeType='application/vnd.google-apps.folder' and name='$folderName' and trashed=false"
            val result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .execute()

            if (result.files.isNotEmpty()) {
                return result.files[0].id // Folder exists! Return its ID.
            }

            // 2. If it doesn't exist, create it
            val folderMetadata = com.google.api.services.drive.model.File().apply {
                name = folderName
                mimeType = "application/vnd.google-apps.folder"
            }
            val folder = driveService.files().create(folderMetadata)
                .setFields("id")
                .execute()

            return folder.id
        } catch (e: Exception) {
            android.util.Log.e("GoogleDrive", "Failed to get/create folder", e)
            return null
        }
    }

    /**
     * Uploads a local CSV file directly into a specific Google Drive folder.
     */
    fun uploadCsvToDrive(driveService: Drive, localFile: java.io.File, folderId: String): String? {
        try {
            // Define the Google Drive file details (Name and Parent Folder)
            val fileMetadata = com.google.api.services.drive.model.File().apply {
                name = localFile.name
                parents = listOf(folderId)
            }

            // Read the local Android file
            val mediaContent = com.google.api.client.http.FileContent("text/csv", localFile)

            // Execute the upload
            val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id, name")
                .execute()

            return uploadedFile.id
        } catch (e: Exception) {
            android.util.Log.e("GoogleDrive", "Failed to upload file", e)
            return null
        }
    }

    /**
     * Renames an existing file inside Google Drive.
     * You need the 'fileId' of the file you want to rename.
     */
    fun renameFileInDrive(driveService: Drive, fileId: String, newName: String): Boolean {
        try {
            // Create a metadata object with ONLY the new name
            val newMetadata = com.google.api.services.drive.model.File().apply {
                name = newName
            }

            // Execute the update command
            driveService.files().update(fileId, newMetadata).execute()
            return true
        } catch (e: Exception) {
            android.util.Log.e("GoogleDrive", "Failed to rename file", e)
            return false
        }
    }

    /**
     * Signs the user out of their Google Account in the app.
     */
    fun signOut(onComplete: () -> Unit) {
        getSignInClient().signOut().addOnCompleteListener {
            // This runs when the logout is fully finished
            onComplete()
        }
    }

    fun processAndUploadScannedResult(
        context: Context,
        driveService: Drive,
        localCsvUri: Uri,
        onProgress: (String, Int) -> Unit // NEW: Callback for the UI
    ) {
        try {
            onProgress("Locating PC folders...", 10)

            // 1. Locate "ROEC System" folder
            val roecQuery = "mimeType='application/vnd.google-apps.folder' and name='ROEC System' and trashed=false"
            val roecFolders = driveService.files().list().setQ(roecQuery).setSpaces("drive").execute().files
            if (roecFolders.isEmpty()) {
                onProgress("Error: ROEC System folder not found", -1)
                return
            }
            val roecId = roecFolders[0].id

            // 2. Locate "Scores" folder
            val scoresQuery = "mimeType='application/vnd.google-apps.folder' and name='Scores' and '$roecId' in parents and trashed=false"
            val scoresFolders = driveService.files().list().setQ(scoresQuery).setSpaces("drive").execute().files
            if (scoresFolders.isEmpty()) {
                onProgress("Error: Scores folder not found", -1)
                return
            }
            val scoresId = scoresFolders[0].id

            onProgress("Archiving previous results...", 30)

            // 3. Locate existing "Scanned Result" file
            val fileQuery = "(name='Scanned Result.csv' or name='Scanned Result') and '$scoresId' in parents and trashed=false"
            val existingFiles = driveService.files().list().setQ(fileQuery).setSpaces("drive").execute().files

            if (existingFiles.isNotEmpty()) {
                val oldFileId = existingFiles[0].id

                val inputStream = driveService.files().get(oldFileId).executeMediaAsInputStream()
                val lines = inputStream.bufferedReader().readLines()

                if (lines.size > 1) {
                    val row = lines[1].split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())

                    if (row.size >= 5) {
                        val c2 = row[2].replace("\"", "").trim()
                        val d2 = row[3].replace("\"", "").trim()
                        val e2 = row[4].replace("\"", "").trim()

                        val regionMap = mapOf(
                            "BARMM" to "BARMM", "CAR" to "CAR", "CO" to "CO",
                            "I" to "REGION I", "II" to "REGION II", "III" to "REGION III",
                            "IV-A" to "REGION IV-A", "IV-B" to "REGION IV-B", "IX" to "REGION IX",
                            "NCR" to "REGION NCR", "NIR" to "NIR", "V" to "REGION V",
                            "VI" to "REGION VI", "VII" to "REGION VII", "VIII" to "REGION VIII",
                            "X" to "REGION X", "XI" to "REGION XI", "XII" to "REGION XII",
                            "XIII" to "REGION XIII"
                        )
                        val mappedRegion = regionMap[c2.uppercase()] ?: c2
                        val sanitizedPlace = d2.replace(Regex("[\\\\/:*?\"<>|']"), "")

                        var formattedDate = e2
                        try {
                            val parsed = SimpleDateFormat("MM/dd/yyyy", Locale.US).parse(e2)
                            if (parsed != null) formattedDate = SimpleDateFormat("MMM-dd-yyyy", Locale.US).format(parsed)
                        } catch (ignored: Exception) {}

                        val newName = "DONE - $mappedRegion, $sanitizedPlace, $formattedDate.csv"

                        // Check if a file with this EXACT new name already exists and delete it to prevent PC duplicates
                        val dupQuery = "name='$newName' and '$scoresId' in parents and trashed=false"
                        val duplicates = driveService.files().list().setQ(dupQuery).setSpaces("drive").execute().files
                        for (dup in duplicates) {
                            driveService.files().delete(dup.id).execute()
                        }

                        // Now safely rename the old file
                        val renameMeta = com.google.api.services.drive.model.File().apply { name = newName }
                        driveService.files().update(oldFileId, renameMeta).execute()
                    }
                }
            }

            onProgress("Uploading new data...", 60)

            // 4. Upload the newly exported CSV with Live Progress Tracking
            val newFileMeta = com.google.api.services.drive.model.File().apply {
                name = "Scanned Result.csv"
                mimeType = "text/csv"
                parents = listOf(scoresId)
            }

            context.contentResolver.openInputStream(localCsvUri)?.use { localStream ->
                val mediaContent = InputStreamContent("text/csv", localStream)
                val insertRequest = driveService.files().create(newFileMeta, mediaContent)

                // Enable progress tracking
                insertRequest.mediaHttpUploader.isDirectUploadEnabled = false
                insertRequest.mediaHttpUploader.chunkSize = MediaHttpUploader.MINIMUM_CHUNK_SIZE
                insertRequest.mediaHttpUploader.progressListener = MediaHttpUploaderProgressListener { uploader ->
                    when (uploader.uploadState) {
                        MediaHttpUploader.UploadState.INITIATION_STARTED -> onProgress("Starting upload...", 65)
                        MediaHttpUploader.UploadState.MEDIA_IN_PROGRESS -> {
                            val percent = (uploader.progress * 100).toInt()
                            // Map the 0-100 upload progress to the remaining 65-99% of our overall process
                            val overallPercent = 65 + (percent * 0.34).toInt()
                            onProgress("Uploading file...", overallPercent)
                        }
                        MediaHttpUploader.UploadState.MEDIA_COMPLETE -> onProgress("Upload complete!", 100)
                        else -> {}
                    }
                }

                insertRequest.execute()
            }

        } catch (e: Exception) {
            Log.e("DriveUpload", "Failed Google Drive sync flow", e)
            onProgress("Failed: ${e.message}", -1)
        }
    }}