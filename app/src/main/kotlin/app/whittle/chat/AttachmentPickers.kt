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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.whittle.R
import app.whittle.attachments.AttachmentNames
import app.whittle.attachments.AttachmentProblem
import app.whittle.attachments.CameraFiles
import app.whittle.settings.AttachmentDefaults

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

/** What to tell the user when something they picked wasn't added. */
@Composable
fun attachmentProblemMessages(defaults: AttachmentDefaults): Map<AttachmentProblem, String> = mapOf(
    AttachmentProblem.TOO_MANY to pluralStringResource(R.plurals.attach_too_many, defaults.maxPerMessage, defaults.maxPerMessage),
    AttachmentProblem.TOO_LARGE to stringResource(R.string.attach_too_large, AttachmentNames.size(defaults.maxFileBytes)),
    AttachmentProblem.UNREADABLE to stringResource(R.string.attach_unreadable),
    AttachmentProblem.NO_WORKSPACE to stringResource(R.string.attach_no_workspace),
)

private const val ANY_TYPE = "*/*"
