package com.themoon.y1

import android.content.Context
import android.net.wifi.WifiManager
import com.themoon.y1.io.SafeFiles
import com.themoon.y1.managers.LastFmScrobbler
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import java.util.HashSet
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class Y1WebServer @JvmOverloads constructor(
    private val context: Context?,
    rootFolder: File? = null,
    private val port: Int = 8080
) : Thread() {

    @Volatile
    private var serverSocket: ServerSocket? = null
    private val clients: MutableSet<Socket> = Collections.synchronizedSet(HashSet<Socket>())
    private val workers: ThreadPoolExecutor = ThreadPoolExecutor(
        2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8)
    )
    @Volatile
    private var running = true
    private val rootFolder: File = (rootFolder ?: StoragePaths.getWebServerRoot()).absoluteFile

    fun getListeningPort(): Int {
        val socket = serverSocket
        return socket?.localPort ?: -1
    }

    override fun run() {
        try {
            val socket = ServerSocket(port)
            serverSocket = socket
            while (running) {
                val client = socket.accept()
                clients.add(client)
                try {
                    workers.execute(RequestHandler(client))
                } catch (e: RejectedExecutionException) {
                    clients.remove(client)
                    client.close()
                }
            }
        } catch (ignored: Exception) {
        } finally {
            stopServer()
        }
    }

    fun stopServer() {
        running = false
        workers.shutdownNow()
        synchronized(clients) {
            for (client in clients) {
                try {
                    client.close()
                } catch (ignored: Exception) {}
            }
            clients.clear()
        }
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
    }

    fun getLocalIpAddress(): String {
        return try {
            val wm = context?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return "127.0.0.1"
            val ipAddress = wm.connectionInfo.ipAddress
            String.format(
                Locale.US,
                "%d.%d.%d.%d",
                (ipAddress and 0xff),
                (ipAddress shr 8 and 0xff),
                (ipAddress shr 16 and 0xff),
                (ipAddress shr 24 and 0xff)
            )
        } catch (ex: Exception) {
            "Unknown IP"
        }
    }

    @Throws(IOException::class)
    private fun deleteFileOrFolder(file: File) {
        val base = rootFolder.canonicalFile
        val canonical = file.canonicalFile
        if (canonical == base || !canonical.path.startsWith(base.path + File.separator)) {
            throw IOException("Cannot delete outside shared folder")
        }
        if (file.absoluteFile != canonical) {
            throw IOException("Cannot delete symbolic link")
        }
        if (file.isDirectory) {
            val children = file.listFiles() ?: throw IOException("Cannot read folder")
            for (child in children) {
                deleteFileOrFolder(child)
            }
        }
        if (!file.delete()) {
            throw IOException("Cannot delete file")
        }
    }

    private inner class RequestHandler(private val socket: Socket) : Runnable {

        @Throws(IOException::class)
        private fun readHeaderLine(inputStream: InputStream): String {
            val sb = StringBuilder()
            var c: Int
            while (inputStream.read().also { c = it } != -1) {
                if (c == '\r'.code) continue
                if (c == '\n'.code) break
                if (sb.length >= 8192) throw IOException("Header too long")
                sb.append(c.toChar())
            }
            return sb.toString()
        }

        @Throws(IOException::class)
        private fun readBody(inputStream: InputStream, contentLength: Int): String {
            if (contentLength < 0 || contentLength > 65536) throw IOException("Body too large")
            val bos = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var totalRead = 0
            while (totalRead < contentLength) {
                val toRead = Math.min(buffer.size, contentLength - totalRead)
                val bytesRead = inputStream.read(buffer, 0, toRead)
                if (bytesRead == -1) break
                bos.write(buffer, 0, bytesRead)
                totalRead += bytesRead
            }
            if (totalRead != contentLength) throw EOFException("Incomplete body")
            return String(bos.toByteArray(), Charsets.UTF_8)
        }

        @Throws(IOException::class)
        private fun formParam(body: String, key: String): String {
            for (pair in body.split("&")) {
                val eq = pair.indexOf('=')
                if (eq < 0) continue
                if (pair.substring(0, eq) == key) {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                }
            }
            return ""
        }

        @Throws(IOException::class)
        private fun writeJson(os: OutputStream, json: String) {
            os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + json).toByteArray(Charsets.UTF_8))
        }

        override fun run() {
            try {
                socket.soTimeout = 15000
                val inputStream = socket.getInputStream()
                val os = socket.getOutputStream()

                val requestLine = readHeaderLine(inputStream)
                if (requestLine.isEmpty()) return

                val parts = requestLine.split(" ")
                if (parts.size != 3) throw IOException("Invalid request")
                val method = parts[0]
                val path = parts[1]

                var contentLength = 0
                var line: String
                var headerCount = 0
                var origin: String? = null
                var host: String? = null
                while (readHeaderLine(inputStream).also { line = it }.isNotEmpty()) {
                    if (++headerCount > 100) throw IOException("Too many headers")
                    if (line.lowercase(Locale.US).startsWith("host:")) {
                        host = line.substring(5).trim()
                    }
                    if (line.lowercase(Locale.US).startsWith("origin:")) {
                        origin = line.substring(7).trim()
                    }
                    if (line.lowercase(Locale.US).startsWith("content-length:")) {
                        contentLength = line.split(":")[1].trim().toInt()
                    }
                }

                if ("POST" == method && origin != null && origin != "http://$host") {
                    throw IOException("Cross-origin write rejected")
                }
                if (contentLength < 0) throw IOException("Invalid length")

                if (method == "GET" && path == "/") {
                    val html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                            "<title>Y1 File Manager</title><style>" +
                            "body{font-family:'Roboto', 'Segoe UI', sans-serif; background:#1E1E24; color:#E0E0E0; padding:20px; text-align:center; max-width:800px; margin:0 auto; padding-bottom:120px;} " +
                            "input, select, button{font-size:14px; padding:10px 16px; margin:5px; border-radius:20px; border:none; outline:none; transition:0.2s;} " +
                            "input[type=text]{width:calc(100% - 120px); background:#2A2A35; color:#E0E0E0; border-radius:12px; padding:12px;} " +
                            "button{background:#B39DDB; color:#121212; font-weight:600; cursor:pointer;} " +
                            "button:hover{background:#9575CD;} " +
                            "button.danger{background:#424250; color:#E57373;} " +
                            "button.danger:hover{background:#EF5350; color:#fff;} " +
                            "button.action{background:#383848; color:#B39DDB;} " +
                            "button.action:hover{background:#454555;} " +
                            ".box{background:#252530; padding:20px; border-radius:16px; margin:15px 0; text-align:left; box-shadow:0 4px 6px rgba(0,0,0,0.3);} " +
                            ".item{display:flex; justify-content:space-between; align-items:center; padding:12px; border-bottom:1px solid #333340; cursor:pointer; transition:0.2s;} " +
                            ".item:last-child{border-bottom:none;} " +
                            ".item:hover{background:#2D2D3A; border-radius:8px;} " +
                            ".item-left{display:flex; align-items:center; flex-grow:1; overflow:hidden; gap:12px;} " +
                            ".item-name{white-space:nowrap; overflow:hidden; text-overflow:ellipsis; font-weight:500;} " +
                            ".thumb{width:40px; height:40px; object-fit:cover; border-radius:8px; background:#1A1A20;} " +
                            ".icon{font-size:22px; width:40px; text-align:center; color:#B39DDB;} " +
                            ".btn-group{display:flex; gap:6px;} " +
                            "#audioBox{position:fixed; bottom:0; left:0; right:0; background:#252530; border-top:1px solid #454555; padding:15px; display:none; z-index:100; box-shadow:0 -2px 10px rgba(0,0,0,0.5);} " +
                            "audio{width:100%; max-width:800px; margin:0 auto; display:block; outline:none; border-radius:24px;} " +
                            "#uploadBox{transition:0.3s; border:2px dashed #454555;} " +
                            "#uploadBox.dragover{background:#2D2D3A; border:2px dashed #B39DDB;} " +
                            "#editorBox{position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(18,18,22,0.95); z-index:200; padding:20px; box-sizing:border-box; display:none;} " +
                            "#editorArea{width:100%; height:calc(100% - 120px); background:#1E1E24; color:#E0E0E0; font-family:'Courier New', monospace; font-size:15px; border:1px solid #454555; border-radius:12px; padding:15px; resize:none; box-shadow:inset 0 2px 5px rgba(0,0,0,0.3);} " +
                            "#editorTitle{color:#B39DDB; margin-top:0; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; font-weight:600;}" +
                            "</style></head><body>" +
                            "<h2 style='color:#E0E0E0; font-weight:600; letter-spacing:0.5px;'>📁 Y1 File Manager " +
                            "<a href='/lastfm' style='font-size:14px; color:#B39DDB; text-decoration:none; vertical-align:middle;'>🎧 Last.fm Settings</a></h2>" +
                            "<div class='box' id='uploadBox'>" +
                            "<div style='font-size:16px; margin-bottom:12px; font-weight:500; color:#B39DDB;'>📍 <span id='currentPathText'>/</span></div>" +
                            "<div style='text-align:center; color:#9E9E9E; font-size:13px; margin-bottom:20px; padding-bottom:15px; border-bottom:1px solid #333340;'>💡 <b>Drag & Drop</b> files anywhere in this box to upload instantly!</div>" +
                            "<div style='display:flex; gap:8px; margin-bottom:15px;'>" +
                            "<input type='text' id='fName' placeholder='New folder name...'>" +
                            "<button onclick='createFolder()'>Create</button></div>" +
                            "<div style='display:flex; gap:8px; align-items:center;'>" +
                            "<input type='file' id='fInput' multiple accept='*/*' style='flex-grow:1; color:#9E9E9E;'>" +
                            "<button onclick='uploadAll()' class='action'>Upload Here</button></div>" +
                            "<div id='status' style='margin-top:12px; color:#81C784; font-weight:600; font-size:14px;'></div>" +
                            "</div>" +
                            "<div class='box' id='fileList'>Loading...</div>" +
                            "<div id='audioBox'>" +
                            "<div id='audioTitle' style='max-width:800px; margin:0 auto 12px; font-weight:600; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; color:#B39DDB;'></div>" +
                            "<audio id='audioPlayer' controls controlsList='nodownload'></audio>" +
                            "</div>" +
                            "<div id='editorBox'>" +
                            "<h3 id='editorTitle'>📝 Edit File</h3>" +
                            "<textarea id='editorArea' spellcheck='false' wrap='off'></textarea>" +
                            "<div style='display:flex; gap:12px; margin-top:15px;'>" +
                            "<button class='action' style='flex:1; background:#B39DDB; color:#121212;' onclick='saveFile()'>💾 Save Settings</button>" +
                            "<button class='danger' style='flex:1;' onclick='closeEditor()'>Cancel</button>" +
                            "</div></div>" +
                            "<script>" +
                            "const nativeFetch = window.fetch.bind(window); window.fetch = (url, options) => nativeFetch(url, options).then(r => { if (!r.ok) { document.getElementById('status').textContent = 'Request failed (' + r.status + ')'; throw Error('Request failed'); } return r; });" +
                            "let currentPath = '';" +
                            "function loadList() {" +
                            " fetch('/api/list?dir=' + encodeURIComponent(currentPath)).then(r=>{if(!r.ok) throw Error('Cannot load folder'); return r.json();}).then(data=>{" +
                            " document.getElementById('currentPathText').textContent='/' + currentPath;" +
                            " const list=document.getElementById('fileList'); list.textContent='';" +
                            " function button(row,label,action) {const b=document.createElement('button');b.className='action';b.textContent=label;b.onclick=e=>{e.stopPropagation();action(e);};row.appendChild(b);}" +
                            " if(currentPath) {const back=document.createElement('div');back.className='item';back.textContent='\\u2190 Go Back';back.onclick=goUp;list.appendChild(back);}" +
                            " data.forEach(f=>{" +
                            " const row=document.createElement('div');row.className='item';" +
                            " const label=document.createElement('span');label.className='item-name';label.textContent=(f.isDir?'\\ud83d\\udcc1 ':'')+f.name;row.appendChild(label);" +
                            " const path=encodeURIComponent(currentPath?currentPath+'/'+f.name:f.name);" +
                            " const ext=f.name.split('.').pop().toLowerCase();" +
                            " if(f.isDir) row.onclick=()=>goInto(f.name);" +
                            " else {" +
                            " if(['jpg','jpeg','png','webp','gif'].includes(ext)) {const image=document.createElement('img');image.className='thumb';image.src='/api/file?path='+path;image.loading='lazy';row.insertBefore(image,label);row.onclick=()=>window.open('/api/file?path='+path,'_blank');}" +
                            " if(['mp3','flac','wav','ogg','opus','m4a','aac'].includes(ext)) row.onclick=()=>playAudio(path,f.name);" +
                            " if(['json','txt','xml','ini','md','m3u','m3u8','eq'].includes(ext)) button(row,'Edit',e=>openEditor(e,path,f.name));" +
                            " button(row,'Download',()=>{window.location.href='/api/download?path='+path;});" +
                            " }" +
                            " button(row,'Rename',e=>renameItem(e,f.name));" +
                            " button(row,'Delete',e=>deleteItem(e,f.name));" +
                            " list.appendChild(row);" +
                            " });" +
                            " }).catch(e=>{document.getElementById('status').textContent=e.message;});" +
                            "}" +
                            "function goInto(dirName) { currentPath = currentPath ? currentPath + '/' + dirName : dirName; loadList(); }" +
                            "function goUp() { let parts = currentPath.split('/'); parts.pop(); currentPath = parts.join('/'); loadList(); }" +
                            "function playAudio(path, name) {" +
                            "  document.getElementById('audioBox').style.display = 'block';" +
                            "  document.getElementById('audioTitle').innerText = '▶ ' + name;" +
                            "  let player = document.getElementById('audioPlayer');" +
                            "  player.src = '/api/file?path=' + path;" +
                            "  player.play();" +
                            "}" +
                            "let editingPath = '';" +
                            "function openEditor(e, path, name) {" +
                            "  if(e) e.stopPropagation();" +
                            "  editingPath = path;" +
                            "  document.getElementById('editorTitle').innerText = '📝 Editing: ' + name;" +
                            "  document.getElementById('editorArea').value = 'Loading content...';" +
                            "  document.getElementById('editorBox').style.display = 'block';" +
                            "  fetch('/api/file?path=' + path).then(r => r.text()).then(txt => {" +
                            "    document.getElementById('editorArea').value = txt;" +
                            "  });" +
                            "}" +
                            "function closeEditor() { document.getElementById('editorBox').style.display = 'none'; editingPath=''; }" +
                            "function saveFile() {" +
                            "  let content = document.getElementById('editorArea').value;" +
                            "  fetch('/api/save?path=' + editingPath, { method: 'POST', body: content }).then(() => {" +
                            "    alert('✅ File saved successfully!'); closeEditor();" +
                            "  }).catch(e => alert('Failed to save.'));" +
                            "}" +
                            "function createFolder() { " +
                            "  var n = document.getElementById('fName').value; if(!n) return;" +
                            "  fetch('/api/create?dir=' + encodeURIComponent(currentPath) + '&name=' + encodeURIComponent(n), {method:'POST'}).then(()=>{ document.getElementById('fName').value=''; loadList();});" +
                            "}" +
                            "function renameItem(e, oldName) { " +
                            "  e.stopPropagation();" +
                            "  var newName = prompt('Enter new name for: ' + oldName, oldName);" +
                            "  if(!newName || newName === oldName) return;" +
                            "  fetch('/api/rename?dir=' + encodeURIComponent(currentPath) + '&old=' + encodeURIComponent(oldName) + '&new=' + encodeURIComponent(newName), {method:'POST'}).then(()=>loadList());" +
                            "}" +
                            "function deleteItem(e, name) { " +
                            "  e.stopPropagation();" +
                            "  if(!confirm('Delete ' + name + '?')) return;" +
                            "  fetch('/api/delete?path=' + encodeURIComponent(currentPath ? currentPath + '/' + name : name), {method:'POST'}).then(()=>loadList());" +
                            "}" +
                            "async function uploadAll(droppedFiles) { " +
                            "  var files = droppedFiles || document.getElementById('fInput').files; var st = document.getElementById('status'); " +
                            "  if(files.length === 0) return;" +
                            "  for(var i=0; i<files.length; i++) { " +
                            "    st.innerText = 'Uploading: ' + files[i].name + ' (' + (i+1) + '/' + files.length + ')'; " +
                            "    await fetch('/api/upload?dir=' + encodeURIComponent(currentPath) + '&name=' + encodeURIComponent(files[i].name), {method:'POST', body:files[i]}); " +
                            "  } " +
                            "  st.innerText = '✅ Upload Complete!'; document.getElementById('fInput').value=''; loadList();" +
                            "}" +
                            "async function uploadFolderItems(fileList) { " +
                            "  var st = document.getElementById('status'); " +
                            "  for(var i=0; i<fileList.length; i++) { " +
                            "    let item = fileList[i]; " +
                            "    let displayPath = item.path ? item.path + item.file.name : item.file.name; " +
                            "    st.innerText = 'Uploading: ' + displayPath + ' (' + (i+1) + '/' + fileList.length + ')'; " +
                            "    let targetDir = currentPath; " +
                            "    if(item.path) { " +
                            "       let subDir = item.path.replace(/\\/$/, ''); " +
                            "       targetDir = currentPath ? currentPath + '/' + subDir : subDir; " +
                            "    } " +
                            "    await fetch('/api/upload?dir=' + encodeURIComponent(targetDir) + '&name=' + encodeURIComponent(item.file.name), {method:'POST', body:item.file}); " +
                            "  } " +
                            "  st.innerText = '✅ Folder Upload Complete!'; loadList();" +
                            "}" +
                            "let dropZone = document.getElementById('uploadBox');" +
                            "dropZone.addEventListener('dragover', function(e) { e.preventDefault(); dropZone.classList.add('dragover'); });" +
                            "dropZone.addEventListener('dragleave', function(e) { e.preventDefault(); dropZone.classList.remove('dragover'); });" +
                            "dropZone.addEventListener('drop', function(e) { " +
                            "  e.preventDefault(); dropZone.classList.remove('dragover'); " +
                            "  let items = e.dataTransfer.items; " +
                            "  if(!items) { if(e.dataTransfer.files.length > 0) uploadAll(e.dataTransfer.files); return; } " +
                            "  document.getElementById('status').innerText = 'Scanning dropped items...'; " +
                            "  let filesToUpload = []; " +
                            "  let pending = 0; " +
                            "  function scanEntry(item, path) { " +
                            "    if(item.isFile) { " +
                            "      pending++; " +
                            "      item.file(f => { filesToUpload.push({file: f, path: path}); pending--; checkDone(); }); " +
                            "    } else if(item.isDirectory) { " +
                            "      let dirReader = item.createReader(); " +
                            "      pending++; " +
                            "      function readBatch() { dirReader.readEntries(entries => { " +
                            "        if(!entries.length) { pending--; checkDone(); return; } " +
                            "        entries.forEach(entry => scanEntry(entry, path + item.name + '/')); " +
                            "        readBatch(); " +
                            "      }, () => { pending--; document.getElementById('status').textContent='Cannot read dropped folder'; }); } readBatch(); " +
                            "    } " +
                            "  } " +
                            "  function checkDone() { " +
                            "    if(pending === 0) { " +
                            "      if(filesToUpload.length > 0) uploadFolderItems(filesToUpload); " +
                            "      else document.getElementById('status').innerText = 'No files found.'; " +
                            "    } " +
                            "  } " +
                            "  for(let i=0; i<items.length; i++) { " +
                            "    let entry = items[i].webkitGetAsEntry(); " +
                            "    if(entry) scanEntry(entry, ''); " +
                            "  } " +
                            "});" +
                            "window.onload = loadList;" +
                            "</script></body></html>"

                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html).toByteArray(Charsets.UTF_8))
                } else if (method == "GET" && path.startsWith("/api/list")) {
                    val q = if (path.contains("?")) path.split("\\?".toRegex())[1] else ""
                    var dirStr = ""
                    if (q.startsWith("dir=")) dirStr = URLDecoder.decode(q.substring(4), "UTF-8")

                    val targetDir = if (dirStr.isEmpty()) rootFolder else SafeFiles.resolve(rootFolder, dirStr)
                    val json = StringBuilder("[")

                    if (targetDir.exists() && targetDir.isDirectory) {
                        val files = targetDir.listFiles()
                        if (files != null) {
                            for (i in 0..1) {
                                for (f in files) {
                                    val isDir = f.isDirectory
                                    if ((i == 0 && isDir) || (i == 1 && !isDir)) {
                                        if (json.length > 1) json.append(",")
                                        json.append("{\"name\":").append(JSONObject.quote(f.name)).append(",\"isDir\":").append(isDir).append("}")
                                    }
                                }
                            }
                        }
                    }
                    json.append("]")
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + json.toString()).toByteArray(Charsets.UTF_8))
                } else if (method == "POST" && path.startsWith("/api/create")) {
                    val q = path.split("\\?".toRegex())[1]
                    val params = q.split("&")
                    var dirStr = ""
                    var name = ""
                    for (p in params) {
                        if (p.startsWith("dir=")) dirStr = URLDecoder.decode(p.substring(4), "UTF-8")
                        if (p.startsWith("name=")) name = URLDecoder.decode(p.substring(5), "UTF-8")
                    }
                    val targetDir = if (dirStr.isEmpty()) rootFolder else SafeFiles.resolve(rootFolder, dirStr)
                    val newDir = SafeFiles.child(targetDir, name)
                    if (!newDir.isDirectory && !newDir.mkdirs()) throw IOException("Cannot create folder")
                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".toByteArray(Charsets.UTF_8))
                } else if (method == "POST" && path.startsWith("/api/delete")) {
                    val q = path.split("\\?".toRegex())[1]
                    val targetPath = URLDecoder.decode(q.substring(5), "UTF-8")
                    val targetFile = SafeFiles.resolve(rootFolder, targetPath)
                    if (targetFile == rootFolder.canonicalFile) throw IOException("Cannot delete root")
                    if (targetFile.exists()) {
                        deleteFileOrFolder(targetFile)
                    }
                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".toByteArray(Charsets.UTF_8))
                } else if (method == "POST" && path.startsWith("/api/rename")) {
                    val q = path.split("\\?".toRegex())[1]
                    val params = q.split("&")
                    var dirStr = ""
                    var oldName = ""
                    var newName = ""
                    for (p in params) {
                        if (p.startsWith("dir=")) dirStr = URLDecoder.decode(p.substring(4), "UTF-8")
                        if (p.startsWith("old=")) oldName = URLDecoder.decode(p.substring(4), "UTF-8")
                        if (p.startsWith("new=")) newName = URLDecoder.decode(p.substring(4), "UTF-8")
                    }

                    val targetDir = if (dirStr.isEmpty()) rootFolder else SafeFiles.resolve(rootFolder, dirStr)
                    val oldFile = SafeFiles.child(targetDir, oldName)
                    val newFile = SafeFiles.child(targetDir, newName)

                    if (!oldFile.exists() || newFile.exists()) throw IOException("Invalid rename")
                    if (oldFile.exists() && !newFile.exists()) {
                        if (!oldFile.renameTo(newFile)) throw IOException("Cannot rename file")
                    }
                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".toByteArray(Charsets.UTF_8))
                } else if (method == "GET" && path.startsWith("/api/file")) {
                    val q = path.split("\\?".toRegex())[1]
                    val targetPath = URLDecoder.decode(q.substring(5), "UTF-8")
                    val targetFile = SafeFiles.resolve(rootFolder, targetPath)

                    if (!targetFile.exists() || targetFile.isDirectory) {
                        os.write("HTTP/1.1 404 Not Found\r\n\r\nNot Found".toByteArray(Charsets.UTF_8))
                    } else {
                        var mimeType = "application/octet-stream"
                        val lowerName = targetFile.name.lowercase()
                        if (lowerName.endsWith(".mp3")) mimeType = "audio/mpeg"
                        else if (lowerName.endsWith(".flac")) mimeType = "audio/flac"
                        else if (lowerName.endsWith(".wav")) mimeType = "audio/wav"
                        else if (lowerName.endsWith(".ogg")) mimeType = "audio/ogg"
                        else if (lowerName.endsWith(".m4a") || lowerName.endsWith(".aac")) mimeType = "audio/mp4"
                        else if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) mimeType = "image/jpeg"
                        else if (lowerName.endsWith(".png")) mimeType = "image/png"
                        else if (lowerName.endsWith(".json")) mimeType = "application/json"
                        else if (lowerName.endsWith(".txt") || lowerName.endsWith(".m3u") || lowerName.endsWith(".m3u8") || lowerName.endsWith(".eq")) mimeType = "text/plain"

                        val header = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: $mimeType\r\n" +
                                "Content-Length: " + targetFile.length() + "\r\n" +
                                "Connection: close\r\n\r\n"
                        os.write(header.toByteArray(Charsets.UTF_8))

                        FileInputStream(targetFile).use { fis ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (fis.read(buffer).also { bytesRead = it } != -1) {
                                os.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                } else if (method == "POST" && path.startsWith("/api/upload")) {
                    val q = path.split("\\?".toRegex())[1]
                    val params = q.split("&")
                    var dirStr = ""
                    var name = "unnamed.file"
                    for (p in params) {
                        if (p.startsWith("dir=")) dirStr = URLDecoder.decode(p.substring(4), "UTF-8")
                        if (p.startsWith("name=")) name = URLDecoder.decode(p.substring(5), "UTF-8")
                    }

                    val targetDir = if (dirStr.isEmpty()) rootFolder else SafeFiles.resolve(rootFolder, dirStr)
                    if (!targetDir.isDirectory && !targetDir.mkdirs()) throw IOException("Cannot create folder")
                    val outFile = SafeFiles.child(targetDir, name)

                    SafeFiles.replace(outFile, inputStream, contentLength)

                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".toByteArray(Charsets.UTF_8))
                } else if (method == "POST" && path.startsWith("/api/save")) {
                    val q = path.split("\\?".toRegex())[1]
                    val targetPath = URLDecoder.decode(q.substring(5), "UTF-8")
                    val targetFile = SafeFiles.resolve(rootFolder, targetPath)

                    SafeFiles.replace(targetFile, inputStream, contentLength)

                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".toByteArray(Charsets.UTF_8))
                } else if (method == "GET" && path.startsWith("/api/download")) {
                    val q = path.split("\\?".toRegex())[1]
                    val targetPath = URLDecoder.decode(q.substring(5), "UTF-8")
                    val targetFile = SafeFiles.resolve(rootFolder, targetPath)

                    if (!targetFile.exists() || targetFile.isDirectory) {
                        os.write("HTTP/1.1 404 Not Found\r\n\r\nNot Found".toByteArray(Charsets.UTF_8))
                    } else {
                        val header = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/octet-stream\r\n" +
                                "Content-Disposition: attachment; filename=\"" + targetFile.name.replace("[\\r\\n\"\\\\]".toRegex(), "_") + "\"\r\n" +
                                "Content-Length: " + targetFile.length() + "\r\n" +
                                "Connection: close\r\n\r\n"
                        os.write(header.toByteArray(Charsets.UTF_8))

                        FileInputStream(targetFile).use { fis ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (fis.read(buffer).also { bytesRead = it } != -1) {
                                os.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                } else if (method == "GET" && path == "/lastfm") {
                    val html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                            "<title>Y1 Last.fm Settings</title><style>" +
                            "body{font-family:'Roboto','Segoe UI',sans-serif; background:#1E1E24; color:#E0E0E0; padding:20px; max-width:480px; margin:0 auto;} " +
                            "a{color:#B39DDB;} " +
                            "input{width:100%; box-sizing:border-box; font-size:15px; padding:12px; margin:6px 0; border-radius:12px; border:none; background:#2A2A35; color:#E0E0E0; outline:none;} " +
                            "button{width:100%; font-size:15px; padding:12px; margin:6px 0; border-radius:20px; border:none; background:#B39DDB; color:#121212; font-weight:600; cursor:pointer;} " +
                            "button:hover{background:#9575CD;} " +
                            "button.danger{background:#424250; color:#E57373;} " +
                            ".box{background:#252530; padding:20px; border-radius:16px; margin:15px 0;} " +
                            "#msg{margin-top:10px; font-weight:600;}" +
                            "</style></head><body>" +
                            "<h2>🎧 Last.fm Scrobbling</h2>" +
                            "<p><a href='/'>&larr; Back to File Manager</a></p>" +
                            "<div class='box' id='statusBox'>Loading...</div>" +
                            "<div class='box' id='loginBox' style='display:none;'>" +
                            "<input id='u' type='text' placeholder='Last.fm username' autocapitalize='off' autocorrect='off'>" +
                            "<input id='p' type='password' placeholder='Last.fm password'>" +
                            "<button onclick='doLogin()'>Log In</button>" +
                            "<div id='msg'></div>" +
                            "</div>" +
                            "<script>" +
                            "function refreshStatus() {" +
                            "  fetch('/api/lastfm/status').then(r=>r.json()).then(s => {" +
                            "    let box = document.getElementById('statusBox');" +
                            "    if (s.loggedIn) {" +
                            "      box.innerHTML = `<p>Logged in as <b id='lastfmUsername'></b></p>` +" +
                            "        `<button onclick='setEnabled(\${!s.enabled})'>Turn scrobbling \${s.enabled ? 'off' : 'on'}</button>` +" +
                            "        `<p>Scrobbling is <b>\${s.enabled ? 'on' : 'off'}</b></p>` +" +
                            "        `<button class='danger' onclick='doLogout()'>Log Out</button>`;" +
                            "      document.getElementById('lastfmUsername').textContent = '@' + s.username;" +
                            "      document.getElementById('loginBox').style.display = 'none';" +
                            "    } else {" +
                            "      box.innerHTML = '<p>Not logged in to Last.fm.</p>';" +
                            "      document.getElementById('loginBox').style.display = 'block';" +
                            "    }" +
                            "  });" +
                            "}" +
                            "function doLogin() {" +
                            "  let u = document.getElementById('u').value.trim();" +
                            "  let p = document.getElementById('p').value;" +
                            "  let msg = document.getElementById('msg');" +
                            "  if (!u || !p) { msg.style.color='#E57373'; msg.innerText='Please fill in both fields.'; return; }" +
                            "  msg.style.color='#B39DDB'; msg.innerText='Logging in...';" +
                            "  fetch('/api/lastfm/login', {method:'POST', body:'username='+encodeURIComponent(u)+'&password='+encodeURIComponent(p)})" +
                            "    .then(r=>r.json()).then(res => {" +
                            "      if (res.success) { msg.style.color='#81C784'; msg.innerText='✅ Logged in as @' + res.message; refreshStatus(); }" +
                            "      else { msg.style.color='#E57373'; msg.innerText='❌ ' + res.message; }" +
                            "    });" +
                            "}" +
                            "function doLogout() { fetch('/api/lastfm/logout', {method:'POST'}).then(refreshStatus); }" +
                            "function setEnabled(v) { fetch('/api/lastfm/enabled?value=' + v, {method:'POST'}).then(refreshStatus); }" +
                            "refreshStatus();" +
                            "</script></body></html>"
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html).toByteArray(Charsets.UTF_8))
                } else if (method == "GET" && path.startsWith("/api/lastfm/status")) {
                    val s = LastFmScrobbler.getInstance(context)
                    val json = "{\"loggedIn\":" + s.isLoggedIn() + ",\"username\":\"" + s.getUsername().replace("\"", "\\\"") +
                            "\",\"enabled\":" + s.isEnabled() + "}"
                    writeJson(os, json)
                } else if (method == "POST" && path.startsWith("/api/lastfm/login")) {
                    val body = readBody(inputStream, contentLength)
                    val username = formParam(body, "username")
                    val password = formParam(body, "password")

                    val latch = CountDownLatch(1)
                    val success = AtomicBoolean(false)
                    val message = AtomicReference("Login failed")

                    LastFmScrobbler.getInstance(context).login(username, password, object : LastFmScrobbler.LoginCallback {
                        override fun onResult(ok: Boolean, msg: String?) {
                            success.set(ok)
                            message.set(msg ?: "Login failed")
                            latch.countDown()
                        }
                    })
                    latch.await(15, TimeUnit.SECONDS)

                    val json = "{\"success\":" + success.get() + ",\"message\":\"" +
                            message.get().replace("\"", "\\\"") + "\"}"
                    writeJson(os, json)
                } else if (method == "POST" && path.startsWith("/api/lastfm/logout")) {
                    LastFmScrobbler.getInstance(context).logout()
                    writeJson(os, "{\"ok\":true}")
                } else if (method == "POST" && path.startsWith("/api/lastfm/enabled")) {
                    val q = if (path.contains("?")) path.split("\\?".toRegex())[1] else ""
                    val value = q.contains("value=true")
                    LastFmScrobbler.getInstance(context).setEnabled(value)
                    writeJson(os, "{\"ok\":true}")
                } else {
                    os.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\nNot Found".toByteArray(Charsets.UTF_8))
                }
                os.flush()
            } catch (e: Exception) {
                try {
                    socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\nRequest failed".toByteArray(Charsets.UTF_8))
                } catch (ignored: Exception) {}
            } finally {
                clients.remove(socket)
                try {
                    socket.close()
                } catch (ignored: Exception) {}
            }
        }
    }
}
