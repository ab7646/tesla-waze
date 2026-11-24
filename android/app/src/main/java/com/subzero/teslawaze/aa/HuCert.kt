package com.subzero.teslawaze.aa

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory

/**
 * The head-unit identity (certificate + private key) that Android Auto checks during the TLS
 * handshake. It is NOT shipped with the app: the user imports a PEM file once, and it is kept
 * in the app's private storage.
 */
object HuCert {
    private const val FILE = "headunit.pem"
    private val PASSWORD = "headunit".toCharArray()

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun isInstalled(context: Context) = file(context).exists()

    /** Validates and stores the PEM file; returns the certificate subject. */
    fun import(context: Context, uri: Uri): String {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("לא ניתן לקרוא את הקובץ")
        return importText(context, text)
    }

    /**
     * Downloads the PEM from a link the user gives (kept only on this phone, never in the app or
     * the repository, which are public). Google Drive "view" links are turned into direct downloads.
     * Blocking; call off the main thread.
     */
    fun download(context: Context, link: String): String {
        val driveId = Regex("""drive\.google\.com/(?:file/d/|open\?id=|uc\?(?:.*&)?id=)([\w-]+)""").find(link)?.groupValues?.get(1)
        val url = if (driveId != null) "https://drive.google.com/uc?export=download&id=$driveId" else link
        val client = okhttp3.OkHttpClient()
        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("ההורדה נכשלה (HTTP ${response.code})")
            val text = response.body?.string().orEmpty()
            if (!text.contains("-----BEGIN")) error("הקישור לא החזיר קובץ PEM. ודא שהשיתוף ב-Drive הוא \"כל מי שיש לו את הקישור\"")
            return importText(context, text)
        }
    }

    private fun importText(context: Context, text: String): String {
        val (chain, _) = parse(text)
        file(context).writeText(text)
        return chain.first().subjectX500Principal.name
    }

    fun describe(context: Context): String? = try {
        if (!isInstalled(context)) null else parse(file(context).readText()).first.first().subjectX500Principal.name
    } catch (e: Exception) {
        "קובץ פגום: ${e.message}"
    }

    fun keyManagers(context: Context): Array<KeyManager> {
        val (chain, key) = parse(file(context).readText())
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null)
        ks.setKeyEntry("headunit", key, PASSWORD, chain.toTypedArray())
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASSWORD)
        return kmf.keyManagers
    }

    private fun parse(text: String): Pair<List<X509Certificate>, PrivateKey> {
        val cf = CertificateFactory.getInstance("X.509")
        val chain = pemBlocks(text, "CERTIFICATE").map {
            cf.generateCertificate(ByteArrayInputStream(it)) as X509Certificate
        }
        if (chain.isEmpty()) error("לא נמצאה תעודה (BEGIN CERTIFICATE) בקובץ")
        val pkcs8 = pemBlocks(text, "PRIVATE KEY").firstOrNull()
            ?: pemBlocks(text, "RSA PRIVATE KEY").firstOrNull()?.let(::pkcs1ToPkcs8)
            ?: error("לא נמצא מפתח פרטי (BEGIN PRIVATE KEY / BEGIN RSA PRIVATE KEY) בקובץ")
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        return chain to key
    }

    private fun pemBlocks(text: String, label: String): List<ByteArray> =
        Regex("-----BEGIN $label-----(.*?)-----END $label-----", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .map { Base64.decode(it.groupValues[1], Base64.DEFAULT) }
            .toList()

    /** Wraps a PKCS#1 RSAPrivateKey in a PKCS#8 PrivateKeyInfo so Java's KeyFactory accepts it. */
    private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val rsaAlgorithm = byteArrayOf(
            0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(),
            0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
        )
        return der(0x30, version + rsaAlgorithm + der(0x04, pkcs1))
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> { out.write(0x81); out.write(len) }
            len < 0x10000 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xFF) }
            else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xFF); out.write(len and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }
}
