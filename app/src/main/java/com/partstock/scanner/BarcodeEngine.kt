package com.partstock.scanner

import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device barcode reading (ML Kit, bundled model). Returns the raw value of every barcode on the photo:
 * DigiKey's DataMatrix (MH10.8.2 text with its RS/GS separators) and Mouser's 1D codes. Decoding into fields is
 * done by [com.partstock.scanner.parse.LabelBarcode]. Never fails a scan: errors give an empty list.
 */
object BarcodeEngine {
    private val scanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_PDF417,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93,
                )
                .build()
        )
    }

    suspend fun scan(image: InputImage): List<String> = suspendCancellableCoroutine { cont ->
        try {
            scanner.process(image)
                .addOnSuccessListener { codes ->
                    if (cont.isActive) cont.resume(codes.mapNotNull { rawText(it) })
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(emptyList())
        }
    }

    /** The barcode's text with control characters kept; falls back to the raw bytes when there's no text. */
    private fun rawText(b: Barcode): String? {
        val v = b.rawValue
        if (!v.isNullOrEmpty()) return v
        val bytes = b.rawBytes ?: return null
        return String(bytes, Charsets.ISO_8859_1).takeIf { it.isNotBlank() }
    }
}
