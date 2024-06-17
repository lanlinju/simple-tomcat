package tomcat

import java.io.*
import java.lang.StringBuilder
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection
import java.net.URLDecoder
import java.util.Scanner
import java.util.concurrent.Executors

// standard
interface Servlet {
    fun init()
    fun service(req: HttpServletRequest, resp: HttpServletResponse)
    fun destroy()
}

abstract class HttpServlet : Servlet {

    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        when (req.getMethod()) {
            "GET" -> doGet(req, resp)
            "POST" -> doPose(req, resp)
            else -> resp.setStatus(400)
        }
    }

    open fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        resp.setStatus(405)
    }

    open fun doPose(req: HttpServletRequest, resp: HttpServletResponse) {
        resp.setStatus(405)
    }

    override fun init() {}

    override fun destroy() {}
}

interface HttpServletRequest {
    fun getMethod(): String
    fun getPath(): String
    fun getParameter(name: String): String?
    fun getHeader(name: String): String?
}

interface HttpServletResponse {
    fun setStatus(status: Int)
    fun setHeader(name: String, value: String)
    fun setContentType(contentType: String)
    fun getWriter(): PrintWriter
    fun getOutputStream(): OutputStream
}

// tomcat
class HttpServletRequestImpl(private val inputStream: InputStream) : HttpServletRequest {

    private val parameter = HashMap<String, String>()
    private val header = HashMap<String, String>()
    private var method = ""
    private var path = ""

    init {
        readAndParse(inputStream)
    }

    private fun readAndParse(inputStream: InputStream) {
        val scanner = Scanner(inputStream)
        parseRequestLine(scanner.nextLine())
        while (true) {
            val head = scanner.nextLine()
            if (head.isBlank()) break
            parseHeader(head)
        }
    }

    /**
     * Request Line
     * Method Path Version
     * GET /home/img.gif?authorization=bce&name=tom HTTP/1.1
     */
    private fun parseRequestLine(line: String) {
        val group = line.split(" ")
        this.method = group[0]
        val uri = group[1]
        parsePath(uri) // if path has whitespaces it will produce bad result
    }

    /**
     * Request Path
     * /home/img.gif?authorization=bce&name=tom
     */
    private fun parsePath(uri: String) {
        val group = uri.split("?")
        this.path = URLDecoder.decode(group[0], "UTF-8")

        // authorization=bce&name=tom
        if (group.size == 1) return
        val fragments = group[1].split("&")
        fragments.forEach {
            val param = it.split("=")
            this.parameter[param[0]] = param[1]
        }
    }

    /**
     * ----Request Header Parse----
     * Host: static.home.baidu.com
     * Connection: keep-alive
     * Pragma: no-cache
     * Cache-Control: no-cache
     */
    private fun parseHeader(head: String) {
        val group = head.split(":")
        header[group[0]] = group[1].trim()
    }

    override fun getMethod(): String = this.method

    override fun getPath(): String = this.path

    override fun getParameter(name: String): String? = this.parameter[name]

    override fun getHeader(name: String): String? = this.header[name]

}

class HttpServletResponseImpl(private val outputStream: OutputStream) : HttpServletResponse {

    private val bodyOutputStream = ByteArrayOutputStream(8192)
    private val bodyPrintWriter = PrintWriter(OutputStreamWriter(bodyOutputStream, "UTF-8"))

    private var status = 200;
    private val header = HashMap<String, String>()
    private var responseLine = "HTTP/1.1 200 OK\r\n"

    override fun setStatus(status: Int) {
        this.status = status
        responseLine = "HTTP/1.1 $status OK\n" // TODO: to resolve status msg
    }

    override fun setHeader(name: String, value: String) {
        header[name] = value
    }

    override fun setContentType(contentType: String) {
        header["Content-Type"] = contentType
    }

    override fun getWriter(): PrintWriter = this.bodyPrintWriter

    override fun getOutputStream(): OutputStream = this.bodyOutputStream

    fun send() {
        // 1. 强制把所有 body 的内容都刷新到最终的目的 buffer（缓冲区） 中
        bodyPrintWriter.flush();

        // 2. 响应行 响应头
        seadResponseLine(outputStream)

        seadResponseHead(outputStream)
        // 3. 最后写响应体
        sendResponseBody(outputStream);
    }

    private fun seadResponseLine(outputStream: OutputStream) {
        outputStream.write(responseLine.toByteArray())
    }

    private fun seadResponseHead(outputStream: OutputStream) {
        val heads = StringBuilder()
        header.forEach { (name, value) ->
            heads.append("${name}: ").append("${value}\r\n")
        }
        heads.append("\r\n")
        println("seadResponseHead: " + heads.toString())
        outputStream.write(heads.toString().toByteArray())
    }

    private fun sendResponseBody(outputStream: OutputStream) {
        outputStream.write(bodyOutputStream.toByteArray())
    }
}

class NotFoundServlet : Servlet {
    override fun init() {}

    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        resp.getWriter().write("have no servlet")
    }

    override fun destroy() {}
}

class StaticResourceServlet : HttpServlet()

object Server {

    private val PORT = 8080;
    // 主线程只处理前台的事务
    @JvmStatic
    fun main(args: Array<String>) {
        WebXML.initServlet()
        val pool = Executors.newFixedThreadPool(10)

        try {
            val serverSocket = ServerSocket(PORT)
            while (true) {
                val socket = serverSocket.accept()
                val task = TransactionTask(socket)
                pool.execute(task)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }
}

class TransactionTask(val socket: Socket) : Runnable {

    override fun run() {

        val request = HttpServletRequestImpl(socket.getInputStream())
        val response = HttpServletResponseImpl(socket.getOutputStream())
        val servlet = WebXML.map(request.getPath())

        servlet.init()

        servlet.service(request, response)

        response.send()

        servlet.destroy()

        socket.close()
    }
}

// webapps
class HelloServlet : HttpServlet() {
    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        val output = resp.getOutputStream()
        resp.setContentType("text/plain")
        output.write("Hello World!".toByteArray())
    }
}

class ImgServlet : HttpServlet() {
    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {

        val file = File("./img/img.jpg")
        resp.setContentType(URLConnection.guessContentTypeFromName(file.name))

        val output = resp.getOutputStream()
        /*val input = file.inputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var len = 0
        while (input.read(buffer).also { len = it } != -1) {
            output.write(buffer, 0, len)
        }*/

        output.write(file.inputStream().readAllBytes())
    }
}

class LoginServlet : HttpServlet() {
    @Throws(IOException::class)
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val username = req.getParameter("username")
        val password = req.getParameter("password")

        println("username: $username, password: $password")

        // TODO: 验证用户名密码是否正确
        // TODO: 设置 Session 信息
        resp.setContentType("text/plain; charset=utf-8")
        val writer: PrintWriter = resp.getWriter()
        writer.println("登录成功!")
    }
}

object WebXML {
    private val servlet = HashMap<String, Servlet>()
    private val servletMapping = HashMap<String, String>()

    fun map(path: String): Servlet {
        val servletName = servletMapping[path]
        return servlet[servletName] ?: NotFoundServlet()
    }

    fun initServlet() {
        // <servlet>
        servlet["Hello"] = HelloServlet()
        servlet["Login"] = LoginServlet()
        servlet["Img"] = ImgServlet()
        // <servlet-mapping>
        servletMapping["/login"] = "Login"
        servletMapping["/hello"] = "Hello"
        servletMapping["/img"] = "Img"
    }

}

fun main() {
//    val path = "name=li&value=3"
//    val path3 = "name=li" // ?
//    val group = path.split("&")
//    val group3 = path3.split("&")
//    val s1 = " /homebd/egf.gif?authorization=bce&name=glp"
//    val s2 = " /homebd/egf.gif?authorization=bce"
//    val s3 = " /homebd/egf.gif"
//    parsePath(s1)
//    parsePath(s2)
//    parsePath(s3) //error

    val heads = "Host: static.home.baidu.com\r\n" +
            "Connection: keep-alive\r\n" +
            "Pragma: no-cache\r\n" +
            "Cache-Control: no-cache\r\n\r\n"
    val headInput = ByteArrayInputStream(heads.toByteArray())
    val scanner = Scanner(headInput)
    parseHeader(scanner)
}

/*  请求头
    Host: static.home.baidu.com
    Connection: keep-alive
    Pragma: no-cache
    Cache-Control: no-cache
    */
private fun parseHeader(scanner: Scanner) {
    while (scanner.hasNext()) {
        val head = scanner.nextLine()
        if (head.isBlank()) break // blank "\r\n".isblank is true
        val group = head.split(":")
        val key = group[0]
        val value = group[1].trim()
    }
}

private fun parsePath(url: String) {
    // /homebd/egf.gif?authorization=bce&name=glp
    val group = url.split("?")
    val path = URLDecoder.decode(group[0], "UTF-8")

    // authorization=bce&name=glp
    if (group.size == 1) return
    val fragments = group[1].split("&") // TODO: Maybe param is null
    fragments.forEach {
        val param = it.split("=")
        val name = param[0]
        val value = param[1]
//        this.parameter[name] = value
    }
}

