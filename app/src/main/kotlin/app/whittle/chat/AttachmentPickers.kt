package app.whittle.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.whittle.attachments.CameraFiles

/** The three ways to add to a message, each handing what it got to the chat. */
class AttachmentPickers(val photos: () -> Unit, val camera: () -> Unit, val files: () -> Unit)

/**
 * Android's photo picker, its camera app and its file picker. None needs a permission: each returns only what
 * the user chose, and the camera saves to a file Whittle hands it.
 */
@Composable
fun rememberAttachmentPickers(viewModel: ChatViewModel): AttachmentPickers {
    val context = LocalContext.current
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(viewModel.attachmentDefaults.maxPerMessage)) { uris ->
        viewModel.attach(uris)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> viewModel.attach(uris) }
    // Kept across a configuration change: the camera app may rotate the screen while it's open.
    var shot by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        shot?.takeIf { saved }?.let { viewModel.attach(listOf(it)) }
        shot = null
    }
    return AttachmentPickers(
        photos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        camera = {
            val target = CameraFiles.create(context, viewModel.attachmentDefaults.cameraDirectoryName)
            shot = target
            camera.launch(target)
        },
        files = { files.launch(arrayOf(ANY_TYPE)) },
    )
}

private const val ANY_TYPE = "*/*"
