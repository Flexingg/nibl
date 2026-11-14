package com.bytelog.app.data.receipts

import android.content.Context
import android.net.Uri
import com.bytelog.app.data.repository.SparkyPrice
import com.bytelog.app.data.repository.ShoppingList
import com.bytelog.app.recipes.UnitConverter
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Reads the text on a photo of a receipt. */
fun interface ReceiptReader {
    suspend fun read(photo: Uri): Result<List<OcrLine>>
}

/** On the phone with ML Kit: nothing is sent anywhere, and it works offline. */
class MlKitReceiptReader(private val context: Context) : ReceiptReader {
    override suspend fun read(photo: Uri): Result<List<OcrLine>> = runCatching {
        val image = InputImage.fromFilePath(context, photo)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        val lines = text.textBlocks.flatMap { b -> b.lines }.map { l ->
                            val r = l.boundingBox
                            OcrLine(l.text, r?.left ?: 0, r?.top ?: 0, r?.right ?: 0, r?.bottom ?: 0)
                        }
                        cont.resume(Result.success(lines))
                    }
                    .addOnFailureListener { e -> cont.resume(Result.failure(e)) }
            }.getOrThrow()
        } finally {
            recognizer.close()
        }
    }
}

/** Turning a receipt line into a price in the food's own units. */
object ReceiptPricing {
    private fun key(u: String) = UnitConverter.canonical(u) ?: ShoppingList.normalUnit(u)
    private fun pick(units: List<String>, unit: String) = units.firstOrNull { key(it) == key(unit) }

    /** [quantity] [unit] in one of [units]: as is, or converted to one of the same kind (1 lb → 16 oz). */
    fun inFoodUnits(quantity: Float, unit: String, units: List<String>): Pair<Float, String>? {
        pick(units, unit)?.let { return quantity to it }
        val dim = UnitConverter.dimension(unit) ?: return null
        if (dim == UnitConverter.Dimension.COUNT) return null
        for (u in units) {
            if (UnitConverter.dimension(u) != dim) continue
            val q = UnitConverter.convert(quantity.toDouble(), unit, u) ?: continue
            return (Math.round(q * 100) / 100f) to u
        }
        return null
    }

    /** One of the food's units that counts things ("medium", "piece", "large"), for items bought by the each. */
    private fun countUnit(units: List<String>): String? =
        units.firstOrNull { ShoppingList.normalUnit(it) == "each" }
            ?: units.firstOrNull { UnitConverter.canonical(it) == null && !it.equals("serving", true) }

    /**
     * What the line says the food costs, in the food's own [units]: by weight for weighed items, by the pack size in
     * the name, or each. The price is null-free but the amount is left out when it can't be told.
     */
    fun suggest(item: ReceiptItem, units: List<String>, store: String?): SparkyPrice {
        item.weight?.let { w ->
            val per = item.pricePerWeightUnit
            if (per != null) inFoodUnits(1f, w.unit, units)?.let { (q, u) -> return SparkyPrice(store, per, q, u) }
            inFoodUnits(w.quantity, w.unit, units)?.let { (q, u) -> return SparkyPrice(store, item.price, q, u) }
        }
        item.size?.let { s ->
            when (s.unit) {
                "each", "dozen" -> countUnit(units)?.let { u ->
                    val n = if (s.unit == "dozen") s.quantity * 12f else s.quantity
                    return SparkyPrice(store, item.unitPrice, n, u)
                }
                else -> inFoodUnits(s.quantity, s.unit, units)?.let { (q, u) -> return SparkyPrice(store, item.unitPrice, q, u) }
            }
        }
        if (item.count > 1) countUnit(units)?.let { u -> return SparkyPrice(store, item.unitPrice, 1f, u) }
        return SparkyPrice(store, item.unitPrice)
    }
}
