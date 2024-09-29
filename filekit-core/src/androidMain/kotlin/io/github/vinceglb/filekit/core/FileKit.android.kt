package io.github.vinceglb.filekit.core

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

public actual object FileKit {
    public var registry: ActivityResultRegistry? = null
        private set

    private var _context: WeakReference<Context?> = WeakReference(null)
    public val context: Context?
        get() = _context.get()

    public fun init(activity: ComponentActivity) {
        _context = WeakReference(activity.applicationContext)
        registry = activity.activityResultRegistry
    }

    public fun init(context: Context, registry: ActivityResultRegistry) {
        this._context = WeakReference(context)
        this.registry = registry
    }
}

public actual suspend fun <Out> FileKit.pickFile(
    type: PickerType,
    mode: PickerMode<Out>,
    title: String?,
    initialDirectory: String?,
    platformSettings: FileKitPlatformSettings?,
): Out? = withContext(Dispatchers.IO) {
    // Throw exception if registry is not initialized
    val registry = registry ?: throw FileKitNotInitializedException()

    // It doesn't really matter what the key is, just that it is unique
    val key = UUID.randomUUID().toString()

    // Get context
    val context = FileKit.context
        ?: throw FileKitNotInitializedException()

    val result: List<PlatformFile>? = suspendCoroutine { continuation ->
        when (type) {
            PickerType.Image,
            PickerType.Video,
            PickerType.ImageAndVideo -> {
                val request = when (type) {
                    PickerType.Image -> PickVisualMediaRequest(ImageOnly)
                    PickerType.Video -> PickVisualMediaRequest(VideoOnly)
                    PickerType.ImageAndVideo -> PickVisualMediaRequest(ImageAndVideo)
                    else -> throw IllegalArgumentException("Unsupported type: $type")
                }

                val launcher = when {
                    mode is PickerMode.Single || mode is PickerMode.Multiple && mode.maxItems == 1 -> {
                        val contract = PickVisualMedia()
                        registry.register(key, contract) { uri ->
                            val result = uri?.let { listOf(PlatformFile(it, context)) }
                            continuation.resume(result)
                        }
                    }

                    mode is PickerMode.Multiple -> {
                        val contract = when {
                            mode.maxItems != null -> PickMultipleVisualMedia(mode.maxItems)
                            else -> PickMultipleVisualMedia()
                        }
                        registry.register(key, contract) { uri ->
                            val result = uri.map { PlatformFile(it, context) }
                            continuation.resume(result)
                        }
                    }

                    else -> throw IllegalArgumentException("Unsupported mode: $mode")
                }
                launcher.launch(request)
            }

            is PickerType.File -> {
                when (mode) {
                    is PickerMode.Single -> {
                        val contract = ActivityResultContracts.OpenDocument()
                        val launcher = registry.register(key, contract) { uri ->
                            val result = uri?.let { listOf(PlatformFile(it, context)) }
                            continuation.resume(result)
                        }
                        launcher.launch(getMimeTypes(type.extensions))
                    }

                    is PickerMode.Multiple -> {
                        // TODO there might be a way to limit the amount of documents, but
                        //  I haven't found it yet.
                        val contract = ActivityResultContracts.OpenMultipleDocuments()
                        val launcher = registry.register(key, contract) { uris ->
                            val result = uris.map { PlatformFile(it, context) }
                            continuation.resume(result)
                        }
                        launcher.launch(getMimeTypes(type.extensions))
                    }
                }
            }
        }
    }

    mode.parseResult(result)
}

public actual suspend fun FileKit.saveFile(
    bytes: ByteArray?,
    baseName: String,
    extension: String,
    initialDirectory: String?,
    platformSettings: FileKitPlatformSettings?,
): PlatformFile? = withContext(Dispatchers.IO) {
    suspendCoroutine { continuation ->
        // Throw exception if registry is not initialized
        val registry = registry ?: throw FileKitNotInitializedException()

        // It doesn't really matter what the key is, just that it is unique
        val key = UUID.randomUUID().toString()

        // Get context
        val context = FileKit.context
            ?: throw FileKitNotInitializedException()

        // Get MIME type
        val mimeType = getMimeType(extension)

        // Create Launcher
        val contract = ActivityResultContracts.CreateDocument(mimeType)
        val launcher = registry.register(key, contract) { uri ->
            val platformFile = uri?.let {
                // Write the bytes to the file
                bytes?.let { bytes ->
                    context.contentResolver.openOutputStream(it)?.use { output ->
                        if(output is FileOutputStream) {
                            // If we are overriding a file we want to make sure that we remove
                            // any extra bytes. Eg. if file was originally 250kb and new file
                            // would only be 200kb we need to truncate the size, if not the file
                            // will contain 50kb of garbage which uses space unnecessarily and
                            // can cause other issues.
                            output.channel.truncate(bytes.size.toLong())
                        }
                        output.write(bytes)
                    }
                }

                PlatformFile(it, context)
            }
            continuation.resume(platformFile)
        }

        // Launch
        launcher.launch("$baseName.$extension")
    }
}

public actual suspend fun FileKit.pickDirectory(
    title: String?,
    initialDirectory: String?,
    platformSettings: FileKitPlatformSettings?,
): PlatformFile? = withContext(Dispatchers.IO) {
    // Throw exception if registry is not initialized
    val registry = registry ?: throw FileKitNotInitializedException()
    val context = context ?: throw FileKitNotInitializedException()

    // It doesn't really matter what the key is, just that it is unique
    val key = UUID.randomUUID().toString()

    suspendCoroutine { continuation ->
        val contract = ActivityResultContracts.OpenDocumentTree()
        val launcher = registry.register(key, contract) { uri ->
            val platformDirectory = uri?.let { PlatformFile(it, context) }
            continuation.resume(platformDirectory)
        }
        val initialUri = initialDirectory?.let { Uri.parse(it) }
        launcher.launch(initialUri)
    }
}

private fun getMimeTypes(fileExtensions: List<String>?): Array<String> {
    val mimeTypeMap = MimeTypeMap.getSingleton()
    return fileExtensions
        ?.takeIf { it.isNotEmpty() }
        ?.mapNotNull { mimeTypeMap.getMimeTypeFromExtension(it) }
        ?.toTypedArray()
        ?.ifEmpty { arrayOf("*/*") }
        ?: arrayOf("*/*")
}

private fun getMimeType(fileExtension: String): String {
    val mimeTypeMap = MimeTypeMap.getSingleton()
    return mimeTypeMap.getMimeTypeFromExtension(fileExtension) ?: "*/*"
}
