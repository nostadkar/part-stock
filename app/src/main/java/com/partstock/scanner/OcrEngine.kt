package com.partstock.scanner

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.partstock.scanner.parse.OcrLine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device OCR. Returns one entry per recognized text line, with its box. */
object OcrEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(image: InputImage): List<OcrLine> = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { text -> cont.resume(toLines(text)) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    private fun toLines(text: Text): List<OcrLine> =
        text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val r = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text, r.left, r.top, r.right, r.bottom)
            }
        }
}
