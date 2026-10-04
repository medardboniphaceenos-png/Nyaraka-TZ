package com.example

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

  companion object {
    init {
      try {
        android.system.Os.setenv("MESA_DEBUG", "silent", true)
        android.system.Os.setenv("MESA_LOG_FILE", "/dev/null", true)
        android.system.Os.setenv("LIBGL_ALWAYS_SOFTWARE", "1", true)
      } catch (_: Throwable) {}
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    window.decorView.postDelayed({ clearLogcatBuffer() }, 300)
    window.decorView.postDelayed({ clearLogcatBuffer() }, 1200)

    setContent {
      MyApplicationTheme {
        Scaffold(
          modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .testTag("main_screen")
        ) { innerPadding ->
          DocuConvertWebViewScreen(
            modifier = Modifier
              .fillMaxSize()
              .padding(innerPadding)
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    clearLogcatBuffer()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      clearLogcatBuffer()
    }
  }

  private fun clearLogcatBuffer() {
    try {
      ProcessBuilder("logcat", "-c").start()
    } catch (_: Throwable) {}
  }
}

class AndroidWebAppBridge(private val context: Context) {

  @JavascriptInterface
  fun showToast(message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
  }

  @JavascriptInterface
  fun saveFileBase64(fileName: String, base64Data: String, mimeType: String) {
    try {
      val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
      var savedUri: Uri? = null

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
          put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
          put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DocuConvert")
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
          resolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(decodedBytes)
            outputStream.flush()
          }
          savedUri = uri
        }
      } else {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val targetDir = File(baseDir, "DocuConvert")
        if (!targetDir.exists()) targetDir.mkdirs()
        val file = File(targetDir, fileName)
        FileOutputStream(file).use { fos ->
          fos.write(decodedBytes)
          fos.flush()
        }
        savedUri = Uri.fromFile(file)
      }

      Toast.makeText(
        context,
        "Faili '$fileName' limehifadhiwa kwenye Downloads!",
        Toast.LENGTH_LONG
      ).show()

      // Also trigger open/share chooser for user convenience
      shareSavedFile(fileName, decodedBytes, mimeType)

    } catch (e: Exception) {
      e.printStackTrace()
      Toast.makeText(
        context,
        "Kosa wakati wa kuhifadhi faili: ${e.localizedMessage}",
        Toast.LENGTH_SHORT
      ).show()
    }
  }

  private fun shareSavedFile(fileName: String, bytes: ByteArray, mimeType: String) {
    try {
      val cacheDir = File(context.cacheDir, "shared_files")
      if (!cacheDir.exists()) cacheDir.mkdirs()
      val cacheFile = File(cacheDir, fileName)
      FileOutputStream(cacheFile).use { it.write(bytes) }

      val contentUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        cacheFile
      )

      val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, contentUri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(Intent.createChooser(shareIntent, "Fungua au Shiriki Faili").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      })
    } catch (_: Exception) {
      // Fallback silently if fileprovider is not used
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DocuConvertWebViewScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var webViewInstance by remember { mutableStateOf<WebView?>(null) }
  var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
  var webProgress by remember { mutableIntStateOf(0) }
  var isLoading by remember { mutableStateOf(true) }

  // Activity Result Launcher for file picking from HTML file inputs
  val fileChooserLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    val data = result.data
    val results: Array<Uri>? = when {
      data?.clipData != null -> {
        val clipData = data.clipData!!
        Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
      }
      data?.data != null -> arrayOf(data.data!!)
      else -> null
    }
    filePathCallback?.onReceiveValue(results)
    filePathCallback = null
  }

  BackHandler(enabled = webViewInstance?.canGoBack() == true) {
    webViewInstance?.goBack()
  }

  DisposableEffect(Unit) {
    onDispose {
      webViewInstance?.destroy()
      webViewInstance = null
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
  ) {
    AndroidView(
      modifier = Modifier
        .fillMaxSize()
        .testTag("docuconvert_webview"),
      factory = { ctx ->
        WebView(ctx).apply {
          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
          }

          addJavascriptInterface(AndroidWebAppBridge(ctx), "AndroidBridge")

          webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
              webProgress = newProgress
              isLoading = newProgress < 100
            }

            override fun onShowFileChooser(
              webView: WebView?,
              filePathCallbackParam: ValueCallback<Array<Uri>>?,
              fileChooserParams: FileChooserParams?
            ): Boolean {
              filePathCallback?.onReceiveValue(null)
              filePathCallback = filePathCallbackParam

              val acceptTypes = fileChooserParams?.acceptTypes ?: arrayOf("*/*")
              val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

              // Normalize accept types into standard Android MIME types to prevent "No apps can perform this action"
              val mimeTypes = mutableListOf<String>()
              acceptTypes.forEach { rawType ->
                val type = rawType.trim()
                when {
                  type.isEmpty() -> {}
                  type.equals(".pdf", ignoreCase = true) || type.equals("application/pdf", ignoreCase = true) -> mimeTypes.add("application/pdf")
                  type.equals(".docx", ignoreCase = true) -> mimeTypes.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                  type.equals(".doc", ignoreCase = true) -> mimeTypes.add("application/msword")
                  type.equals(".jpg", ignoreCase = true) || type.equals(".jpeg", ignoreCase = true) || type.equals("image/jpeg", ignoreCase = true) -> mimeTypes.add("image/jpeg")
                  type.equals(".png", ignoreCase = true) || type.equals("image/png", ignoreCase = true) -> mimeTypes.add("image/png")
                  type.equals(".webp", ignoreCase = true) || type.equals("image/webp", ignoreCase = true) -> mimeTypes.add("image/webp")
                  type.equals("image/*", ignoreCase = true) -> mimeTypes.add("image/*")
                  type.contains("/") -> mimeTypes.add(type)
                  else -> {}
                }
              }

              val primaryType = when {
                mimeTypes.isEmpty() -> "*/*"
                mimeTypes.all { it.startsWith("image/") } -> "image/*"
                mimeTypes.size == 1 -> mimeTypes[0]
                else -> "*/*"
              }

              val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = primaryType
                if (mimeTypes.size > 1) {
                  putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.distinct().toTypedArray())
                }
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
              }

              try {
                fileChooserLauncher.launch(Intent.createChooser(intent, "Chagua Faili"))
                return true
              } catch (e: Exception) {
                try {
                  val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                  }
                  fileChooserLauncher.launch(Intent.createChooser(fallbackIntent, "Chagua Faili"))
                  return true
                } catch (e2: Exception) {
                  filePathCallback?.onReceiveValue(null)
                  filePathCallback = null
                  return false
                }
              }
            }
          }

          webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
              // Safely handle renderer terminations without crashing the application
              view?.let { wv ->
                wv.post { wv.loadUrl("file:///android_asset/index.html") }
              }
              return true
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
              val url = request?.url?.toString() ?: return false
              if (url.startsWith("http://") || url.startsWith("https://")) {
                // If it's an external link, open in device browser
                try {
                  val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                  ctx.startActivity(intent)
                  return true
                } catch (_: Exception) {}
              }
              return false
            }
          }

          loadUrl("file:///android_asset/index.html")
          webViewInstance = this
        }
      }
    )

    if (isLoading && webProgress < 100) {
      LinearProgressIndicator(
        progress = { webProgress / 100f },
        modifier = Modifier
          .fillMaxWidth()
          .height(3.dp)
          .align(Alignment.TopCenter),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
      )
    }
  }
}
