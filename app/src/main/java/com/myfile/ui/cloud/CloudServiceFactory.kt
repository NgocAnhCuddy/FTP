package com.myfile.ui.cloud

import android.content.Context
import com.myfile.ui.model.CloudProvider

object CloudServiceFactory {
    fun get(context: Context, provider: CloudProvider): CloudFileService = when (provider) {
        CloudProvider.GOOGLE_DRIVE -> GoogleDriveService(context)
        CloudProvider.DROPBOX -> DropboxService(context)
        CloudProvider.BOX -> BoxService(context)
    }
}
