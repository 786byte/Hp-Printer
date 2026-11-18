package com.justprintit.core.ipp

import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal IPP-over-HTTP client.
 *
 * Important for the "never retry on its own" rule: requests with a document body are sent in chunked
 * streaming mode, which makes HttpURLConnection refuse to silently re-send a POST after a dropped connection.
 * Every request is attempted exactly once; deciding to retry is always up to the user.
 */
class IppClient(
    host: String,
    port: Int = 631,
    path: String = "/ipp/print",
    private val userName: String = "android",
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 60_000,
) {
    private val httpUrl = URL("http", host.bracketIfIpv6(), port, path)
    val printerUri = "ipp://${host.bracketIfIpv6()}:$port$path"
    private val ids = AtomicInteger(1)

    fun send(request: IppMessage, document: ((OutputStream) -> Unit)? = null): IppMessage {
        val conn = (httpUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Content-Type", "application/ipp")
            setRequestProperty("Accept", "application/ipp")
        }
        val head = request.encode()
        if (document != null) conn.setChunkedStreamingMode(64 * 1024) else conn.setFixedLengthStreamingMode(head.size)
        try {
            conn.outputStream.use { out ->
                out.write(head)
                document?.invoke(out)
            }
            val status = conn.responseCode
            if (status != 200) throw IppException("Printer answered HTTP $status")
            return conn.inputStream.use { IppMessage.decode(it) }
        } catch (e: IOException) {
            throw IppException(e.message ?: e.javaClass.simpleName, e)
        } finally {
            conn.disconnect()
        }
    }

    private fun newRequest(op: Int) = IppMessage.request(op, ids.getAndIncrement(), printerUri).also {
        it.groups[0].text("requesting-user-name", userName, IppTag.NAME)
    }

    fun getPrinterAttributes(vararg names: String): IppMessage {
        val req = newRequest(IppOp.GET_PRINTER_ATTRIBUTES)
        if (names.isNotEmpty()) req.groups[0].keyword("requested-attributes", *names)
        return send(req).also { it.requireSuccess("Get-Printer-Attributes") }
    }

    fun printJob(
        jobName: String,
        documentFormat: String,
        jobAttributes: IppGroup.() -> Unit,
        document: (OutputStream) -> Unit,
    ): Int {
        val req = newRequest(IppOp.PRINT_JOB)
        req.groups[0].text("job-name", jobName.take(255), IppTag.NAME)
        req.groups[0].text("document-format", documentFormat, IppTag.MIME_TYPE)
        req.groups += IppGroup(IppTag.JOB).apply(jobAttributes)
        val resp = send(req, document)
        resp.requireSuccess("Print-Job")
        return resp["job-id"]?.int ?: throw IppException("Printer did not return a job id")
    }

    fun getJob(jobId: Int): JobStatus {
        val req = newRequest(IppOp.GET_JOB_ATTRIBUTES)
        req.groups[0].integer("job-id", jobId)
        req.groups[0].keyword(
            "requested-attributes", "job-state", "job-state-reasons", "job-state-message",
            "job-impressions-completed", "job-media-sheets-completed",
        )
        val resp = send(req).also { it.requireSuccess("Get-Job-Attributes") }
        return JobStatus(
            state = resp["job-state"]?.int ?: 0,
            reasons = resp["job-state-reasons"]?.strings.orEmpty(),
            message = resp["job-state-message"]?.string,
            impressionsCompleted = resp["job-impressions-completed"]?.int,
            sheetsCompleted = resp["job-media-sheets-completed"]?.int,
        )
    }

    fun cancelJob(jobId: Int) {
        val req = newRequest(IppOp.CANCEL_JOB)
        req.groups[0].integer("job-id", jobId)
        send(req).requireSuccess("Cancel-Job")
    }

    private fun IppMessage.requireSuccess(op: String) {
        if (!isSuccess) {
            val msg = this["status-message"]?.string
            throw IppException("$op failed: ${msg ?: statusName(code)}")
        }
    }

    companion object {
        fun statusName(code: Int): String = when (code) {
            0x0400 -> "bad request"
            0x0401 -> "forbidden"
            0x040A -> "document format not supported"
            0x040B -> "attributes or values not supported"
            0x0500 -> "printer internal error"
            0x0506 -> "printer busy"
            0x0507 -> "job canceled"
            0x0509 -> "printer not accepting jobs"
            else -> "status 0x%04x".format(code)
        }

        private fun String.bracketIfIpv6() = if (contains(':') && !startsWith("[")) "[$this]" else this
    }
}

data class JobStatus(
    val state: Int,
    val reasons: List<String>,
    val message: String?,
    val impressionsCompleted: Int?,
    val sheetsCompleted: Int?,
) {
    val isFinished get() = state >= JOB_CANCELED
    val isCompleted get() = state == JOB_COMPLETED

    companion object {
        const val JOB_PENDING = 3
        const val JOB_HELD = 4
        const val JOB_PROCESSING = 5
        const val JOB_STOPPED = 6
        const val JOB_CANCELED = 7
        const val JOB_ABORTED = 8
        const val JOB_COMPLETED = 9
    }
}
