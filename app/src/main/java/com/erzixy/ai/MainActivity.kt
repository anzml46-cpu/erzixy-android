package com.erzixy.ai

import android.os.Bundle
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Blue = Color(0xFF246BFD)
private val LightBlue = Color(0xFFEAF1FF)
private val Ink = Color(0xFF17233C)
private val Muted = Color(0xFF7D8799)

private const val API_URL =
    "https://erzixy-ai-backend.vercel.app/api/chat"

private const val MAX_FILE_SIZE = 2_000_000

data class ChatAttachment(
    val filename: String,
    val mimeType: String,
    val type: String,
    val base64: String
)

data class GeneratedFile(
    val filename: String,
    val mimeType: String,
    val base64: String
)

data class ErzixyResponse(
    val reply: String,
    val files: List<GeneratedFile>
)

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val files: List<GeneratedFile> = emptyList()
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ErzixyApp() }
    }
}

private suspend fun loadAttachment(
    context: Context,
    uri: Uri
): ChatAttachment = withContext(Dispatchers.IO) {

    val resolver = context.contentResolver

    val filename = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)
        else "file"
    } ?: "file"

    val mime = resolver.getType(uri) ?: "application/octet-stream"

    val bytes = resolver.openInputStream(uri)?.use {
        it.readBytes()
    } ?: throw Exception("File tidak bisa dibaca")

    if (bytes.size > MAX_FILE_SIZE) {
        throw Exception("Ukuran maksimal file adalah 2 MB")
    }

    val isImage = mime.startsWith("image/")
    val isPdf = mime == "application/pdf" ||
        filename.endsWith(".pdf", true)
    val isTxt = mime == "text/plain" ||
        filename.endsWith(".txt", true)

    if (!isImage && !isPdf && !isTxt) {
        throw Exception("Format yang didukung: gambar, PDF, dan TXT")
    }

    ChatAttachment(
        filename = filename,
        mimeType = when {
            isPdf -> "application/pdf"
            isTxt -> "text/plain"
            else -> mime
        },
        type = if (isImage) "image" else "file",
        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    )
}

private suspend fun askErzixy(
    message: String,
    attachment: ChatAttachment?
): ErzixyResponse = withContext(Dispatchers.IO) {

    val connection =
        URL(API_URL).openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 30000
        connection.readTimeout = 90000
        connection.doOutput = true
        connection.setRequestProperty(
            "Content-Type",
            "application/json"
        )

        val body = JSONObject().put("message", message)

        if (attachment != null) {
            body.put(
                "attachment",
                JSONObject()
                    .put("type", attachment.type)
                    .put("filename", attachment.filename)
                    .put("mimeType", attachment.mimeType)
                    .put("data", attachment.base64)
            )
        }

        connection.outputStream.use {
            it.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        val status = connection.responseCode

        val stream = if (status in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val response = stream.bufferedReader().use {
            it.readText()
        }

        val json = JSONObject(response)

        if (status !in 200..299) {
            throw Exception(
                json.optString("error", "Server bermasalah")
            )
        }

        val generatedFiles = mutableListOf<GeneratedFile>()
        val fileArray = json.optJSONArray("files")

        if (fileArray != null) {
            for (i in 0 until fileArray.length()) {
                val file = fileArray.optJSONObject(i) ?: continue
                val filename = file.optString("filename")
                val data = file.optString("data")

                if (filename.isNotBlank() && data.isNotBlank()) {
                    generatedFiles.add(
                        GeneratedFile(
                            filename = filename,
                            mimeType = file.optString(
                                "mimeType",
                                "application/octet-stream"
                            ),
                            base64 = data
                        )
                    )
                }
            }
        }

        ErzixyResponse(
            reply = json.optString(
                "reply",
                "AI belum memberikan jawaban."
            ),
            files = generatedFiles
        )

    } finally {
        connection.disconnect()
    }
}

@Composable
fun ErzixyApp() {

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var message by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var attachment by remember { mutableStateOf<ChatAttachment?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val messages = remember {
        mutableStateListOf<ChatMessage>()
    }

    var pendingFile by remember {
        mutableStateOf<GeneratedFile?>(null)
    }

    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val file = pendingFile
        if (uri != null && file != null) {
            try {
                val bytes = Base64.decode(file.base64, Base64.DEFAULT)
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(bytes)
                } ?: throw Exception("Tidak bisa membuka lokasi penyimpanan")

                messages.add(
                    ChatMessage("File berhasil disimpan: ${file.filename}", false)
                )
            } catch (e: Exception) {
                messages.add(
                    ChatMessage("Gagal menyimpan file: ${e.message}", false)
                )
            }
        }
        pendingFile = null
    }

    fun pickFile(uri: Uri?) {
        if (uri == null) return

        scope.launch {
            try {
                attachment = loadAttachment(context, uri)
            } catch (e: Exception) {
                messages.add(
                    ChatMessage(
                        "Gagal membuka file: ${e.message}",
                        false
                    )
                )
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        pickFile(uri)
    }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        pickFile(uri)
    }

    fun sendMessage() {
        val file = attachment

        val question = message.trim().ifEmpty {
            when (file?.type) {
                "image" -> "Tolong analisis gambar ini."
                "file" -> "Tolong baca dan jelaskan dokumen ini."
                else -> ""
            }
        }

        if (question.isEmpty() || isLoading) return

        val displayText = if (file != null) {
            "$question\n\nLampiran: ${file.filename}"
        } else {
            question
        }

        messages.add(ChatMessage(displayText, true))

        message = ""
        attachment = null
        isLoading = true

        scope.launch {
            try {
                val answer = askErzixy(question, file)
                messages.add(
                    ChatMessage(
                        answer.reply,
                        false,
                        answer.files
                    )
                )
            } catch (e: Exception) {
                messages.add(
                    ChatMessage(
                        "Gagal: ${e.message ?: "Periksa koneksi internet."}",
                        false
                    )
                )
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(messages.size, isLoading) {
        val count = messages.size + if (isLoading) 1 else 0
        if (count > 0) {
            listState.animateScrollToItem(count - 1)
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(primary = Blue)
    ) {
        Scaffold(
            containerColor = Color.White,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Chat, null) },
                        label = { Text("Chat") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.GridView, null) },
                        label = { Text("Fitur") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.History, null) },
                        label = { Text("Riwayat") }
                    )
                }
            }
        ) { padding ->

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .background(Color.White)
            ) {

                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        null,
                        tint = Blue,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Erzixy",
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                        Text(
                            "Your AI Assistant",
                            fontSize = 12.sp,
                            color = Muted
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFFF0F2F6))

                when (selectedTab) {
                    0 -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            contentPadding = PaddingValues(vertical = 16.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(12.dp)
                        ) {
                            if (messages.isEmpty()) {
                                item {
                                    Column(
                                        Modifier.fillMaxWidth().padding(35.dp),
                                        horizontalAlignment =
                                            Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            null,
                                            tint = Blue,
                                            modifier = Modifier.size(55.dp)
                                        )
                                        Spacer(Modifier.height(16.dp))
                                        Text(
                                            "Halo, ada yang bisa dibantu?",
                                            fontSize = 21.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Ink
                                        )
                                        Text(
                                            "Mulai percakapan dengan Erzixy AI",
                                            color = Muted
                                        )
                                    }
                                }
                            }

                            items(messages) { item ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        if (item.isUser) Arrangement.End
                                        else Arrangement.Start
                                ) {
                                    Column(
                                        Modifier
                                            .widthIn(max = 310.dp)
                                            .background(
                                                if (item.isUser) Blue
                                                else LightBlue,
                                                RoundedCornerShape(18.dp)
                                            )
                                            .padding(14.dp)
                                    ) {
                                        SelectionContainer {
                                            Text(
                                                item.text,
                                                color = if (item.isUser)
                                                    Color.White else Ink,
                                                fontSize = 14.sp,
                                                lineHeight = 21.sp
                                            )
                                        }

                                        item.files.forEach { generatedFile ->
                                            Row(
                                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.InsertDriveFile,
                                                    contentDescription = null,
                                                    tint = Blue
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    generatedFile.filename,
                                                    modifier = Modifier.weight(1f),
                                                    color = Ink,
                                                    fontSize = 12.sp
                                                )
                                                TextButton(
                                                    onClick = {
                                                        pendingFile = generatedFile
                                                        downloadLauncher.launch(
                                                            generatedFile.filename
                                                        )
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.Download,
                                                        contentDescription = null
                                                    )
                                                    Text("Download")
                                                }
                                            }
                                        }

                                        if (!item.isUser) {
                                            TextButton(
                                                onClick = {
                                                    clipboard.setText(
                                                        AnnotatedString(item.text)
                                                    )
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.ContentCopy,
                                                    null
                                                )
                                                Spacer(Modifier.width(5.dp))
                                                Text("Salin")
                                            }
                                        }
                                    }
                                }
                            }

                            if (isLoading) {
                                item {
                                    Text(
                                        "Erzixy sedang berpikir...",
                                        color = Muted
                                    )
                                }
                            }
                        }

                        Column(
                            Modifier.fillMaxWidth().padding(12.dp)
                        ) {

                            if (attachment != null) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(
                                            LightBlue,
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(8.dp),
                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (attachment!!.type == "image")
                                            Icons.Default.Image
                                        else Icons.Default.Description,
                                        null,
                                        tint = Blue
                                    )

                                    Spacer(Modifier.width(8.dp))

                                    Text(
                                        attachment!!.filename,
                                        modifier = Modifier.weight(1f),
                                        color = Ink,
                                        maxLines = 1
                                    )

                                    IconButton(
                                        onClick = { attachment = null }
                                    ) {
                                        Icon(Icons.Default.Close, "Hapus")
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                            }

                            Row(
                                Modifier.fillMaxWidth()
                                    .background(
                                        Color(0xFFF5F7FB),
                                        RoundedCornerShape(24.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {
                                Box {
                                    IconButton(
                                        onClick = { showMenu = true }
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            "Lampiran",
                                            tint = Blue
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = {
                                            showMenu = false
                                        }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Pilih gambar") },
                                            onClick = {
                                                showMenu = false
                                                imagePicker.launch("image/*")
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Image, null)
                                            }
                                        )

                                        DropdownMenuItem(
                                            text = { Text("Pilih PDF atau TXT") },
                                            onClick = {
                                                showMenu = false
                                                documentPicker.launch(
                                                    arrayOf(
                                                        "application/pdf",
                                                        "text/plain"
                                                    )
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Description,
                                                    null
                                                )
                                            }
                                        )
                                    }
                                }

                                BasicTextField(
                                    value = message,
                                    onValueChange = { message = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                    singleLine = false,
                                    maxLines = 4,
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = Ink,
                                        fontSize = 14.sp
                                    ),
                                    decorationBox = { innerTextField ->
                                        Box {
                                            if (message.isEmpty()) {
                                                Text("Tulis pesan...", color = Muted)
                                            }
                                            innerTextField()
                                        }
                                    }
                                )

                                IconButton(
                                    enabled = !isLoading,
                                    onClick = { sendMessage() },
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(Blue, CircleShape)
                                ) {
                                    Icon(Icons.Default.Send, null, tint = Color.White)
                                }
                            }
                        }
                    }

                    1 -> {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("Fitur Erzixy", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("Chat AI — Tanya apa saja kepada AI.")
                            Text("AI Coding — Bantuan membuat dan memperbaiki kode.")
                            Text("Analisis Gambar — Kirim gambar melalui tombol lampiran.")
                            Text("Dokumen — Unggah file PDF atau TXT.")
                            Text("AI Voice dan Buat Gambar — Belum tersedia.")
                        }
                    }

                    2 -> {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text("Riwayat Chat", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            if (messages.isEmpty()) {
                                Text("Belum ada percakapan.", color = Muted)
                            } else {
                                LazyColumn {
                                    items(messages) { item ->
                                        Text(
                                            (if (item.isUser) "Kamu: " else "Erzixy: ") + item.text,
                                            modifier = Modifier.padding(vertical = 8.dp),
                                            color = Ink
                                        )
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
