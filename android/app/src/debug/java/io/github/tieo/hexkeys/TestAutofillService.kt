package io.github.tieo.hexkeys

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.autofill.inline.v1.InlineSuggestionUi

/**
 * A stand-in password manager for testing the keyboard's autofill strip in
 * debug builds: for any text field it offers three made-up accounts and,
 * last and not pinned, a button of its own, as Bitwarden offers its vault.
 */
class TestAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.last().structure
        val field = (0 until structure.windowNodeCount)
            .firstNotNullOfOrNull { focusedText(structure.getWindowNodeAt(it).rootViewNode) }
        val inline = request.inlineSuggestionsRequest
        if (field == null || inline == null) {
            callback.onSuccess(null)
            return
        }
        val specs = inline.inlinePresentationSpecs
        val attribution = PendingIntent.getActivity(this, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        val response = FillResponse.Builder()
        val entries = listOf("anna@example.com", "ben.mueller@example.org", "c@example.net", "Vault")
        for ((index, entry) in entries.withIndex()) {
            val spec = specs[minOf(index, specs.size - 1)]
            val pinned = false
            val content = InlineSuggestionUi.newContentBuilder(attribution).setTitle(entry).build()
            @Suppress("DEPRECATION")
            response.addDataset(
                Dataset.Builder()
                    .setValue(
                        field,
                        AutofillValue.forText(if (entry == "Vault") "" else entry),
                        RemoteViews(packageName, android.R.layout.simple_list_item_1),
                        InlinePresentation(content.slice, spec, pinned),
                    )
                    .build(),
            )
        }
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()

    private fun focusedText(node: AssistStructure.ViewNode): AutofillId? {
        if (node.isFocused && node.autofillType == android.view.View.AUTOFILL_TYPE_TEXT) return node.autofillId
        for (i in 0 until node.childCount) focusedText(node.getChildAt(i))?.let { return it }
        return null
    }
}
