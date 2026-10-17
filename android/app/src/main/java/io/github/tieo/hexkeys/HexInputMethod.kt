package io.github.tieo.hexkeys

import android.content.Intent
import java.util.concurrent.Executors
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlinePresentationSpec
import android.annotation.TargetApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

/** The input method: hands the keyboard view the app it types into. */
class HexInputMethod : InputMethodService() {

    private var keyboard: KeyboardView? = null
    private var frame: KeyboardFrame? = null
    private var lexicon = 0L

    /** What holding a letter offers in the languages loaded, for keyboard views made later. */
    private var accents = ""

    /** Reads dictionaries one set at a time, away from the main thread. */
    private val loader = Executors.newSingleThreadExecutor()
    /** Off in password fields and where the app asks for no suggestions. */
    private var suggesting = false

    /** A correction autocorrect just made: what was typed, and the word and separator put in its place. */
    private data class Correction(val typed: String, val inserted: String)
    private var lastCorrection: Correction? = null

    /** What the delete swipe under way has deleted, last piece last, to put back when it slides back. */
    private val deleted = ArrayDeque<String>()

    /** The word swiped right before the delete swipe under way, which it takes first, whole. */
    private var swipedBeforeDelete: String? = null

    /** The word a swipe just put in, while its alternatives show in the strip. */
    private var swiped: String? = null

    /** Reloads the dictionaries when the languages change in settings. */
    private val settingsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Dictionaries.ENABLED -> loadDictionaries()
            Theme.KEY -> keyboard?.theme = Theme.current(this)
            KeyboardView.LAYOUT, KeyboardView.DELETE_FROM_LEFT, KeyboardView.CAPITAL -> keyboard?.requestLayout()
            Dictionaries.LEARNED_RESET -> {
                Core.lexiconForget(lexicon)
                learned.clear()
            }
        }
    }

    /** The learned words as last saved, mirrored in the lexicon. */
    private val learned = mutableMapOf<String, Int>()

    override fun onCreate() {
        super.onCreate()
        lexicon = Core.lexiconCreate()
        learned.putAll(Dictionaries.learned(this))
        loadDictionaries()
        Dictionaries.preferences(this).registerOnSharedPreferenceChangeListener(settingsChanged)
    }

    override fun onDestroy() {
        super.onDestroy()
        Dictionaries.preferences(this).unregisterOnSharedPreferenceChangeListener(settingsChanged)
        loader.shutdown()
        Core.lexiconDestroy(lexicon)
        lexicon = 0L
    }

    /**
     * Builds a lexicon from the enabled dictionaries and the learned words
     * off the main thread, works out the accents its languages use, and
     * then puts it in place of the one in use. Large dictionaries take
     * seconds to read through; typing goes on with the old lexicon meanwhile.
     */
    private fun loadDictionaries() {
        val words = learned.toMap()
        loader.execute {
            // On the first start the phone's languages are downloaded and switched on.
            // Offline, the ones missing are tried again on the next start.
            var files = Dictionaries.enabled(this)
            if (!Dictionaries.chosen(this)) {
                val languages = Dictionaries.deviceLanguages(this)
                files = languages.filter { file ->
                    Dictionaries.isAvailable(this, file) ||
                        runCatching { Dictionaries.download(this, file) {} }.isSuccess
                }.toSet()
                if (files == languages) Dictionaries.setEnabled(this, files)
            }
            val fresh = Core.lexiconCreate()
            for (file in files) {
                Dictionaries.read(this, file)?.let { Core.lexiconAdd(fresh, it) }
            }
            for ((word, times) in words) Core.lexiconLearn(fresh, word, times)
            val accents = Core.lexiconAccents(fresh)
            mainExecutor.execute {
                if (lexicon == 0L) {
                    Core.lexiconDestroy(fresh)
                    return@execute
                }
                Core.lexiconDestroy(lexicon)
                lexicon = fresh
                this.accents = accents
                keyboard?.accents = accents
                refreshStrip()
            }
        }
    }

    override fun onCreateInputView(): View = KeyboardView(
        this,
        onAction = { kind, text ->
            val connection = currentInputConnection ?: return@KeyboardView
            // Text put in while a word is marked would replace it.
            if (kind != "redraw" && kind != "nothing") connection.finishComposingText()
            val undo = lastCorrection
            lastCorrection = null
            val justSwiped = swiped
            swiped = null
            when (kind) {
                "delete" -> {
                    val (pieces, words, started) = text.split(" ").map { it.toInt() }
                    var before = connection.getTextBeforeCursor(CONTEXT_LENGTH, 0)?.toString().orEmpty()
                    if (started == 1) {
                        deleted.clear()
                        swipedBeforeDelete = justSwiped?.takeIf { before.endsWith(it) }
                    }
                    val word = swipedBeforeDelete
                    connection.beginBatchEdit()
                    // A selection goes first, as one piece, as backspace takes it; so
                    // does a word just swiped.
                    val selected = connection.getSelectedText(0)?.toString().orEmpty()
                    if (deleted.isEmpty() && pieces > 0 && selected.isNotEmpty()) {
                        connection.commitText("", 1)
                        deleted.addLast(selected)
                    } else if (deleted.isEmpty() && pieces > 0 && word != null && before.endsWith(word)) {
                        connection.deleteSurroundingText(word.length, 0)
                        before = before.dropLast(word.length)
                        deleted.addLast(word)
                    }
                    while (deleted.size < pieces && before.isNotEmpty()) {
                        // A word goes with the spaces after it, as ctrl and backspace take it.
                        val piece = if (words == 1) {
                            WORD_AT_END.find(before)?.value?.takeIf { it.isNotEmpty() } ?: before.takeLast(1)
                        } else {
                            lastCharacter(before)
                        }
                        connection.deleteSurroundingText(piece.length, 0)
                        before = before.dropLast(piece.length)
                        deleted.addLast(piece)
                    }
                    while (deleted.size > pieces) connection.commitText(deleted.removeLast(), 1)
                    connection.endBatchEdit()
                }
                "swipe" -> {
                    val view = keyboard ?: return@KeyboardView
                    if (!suggesting) return@KeyboardView
                    val before = connection.getTextBeforeCursor(CONTEXT_LENGTH, 0)?.toString().orEmpty()
                    val words = view.swipe(lexicon, before)
                    if (words.isEmpty()) return@KeyboardView
                    // Swiped words are put in with a space between them, as on other keyboards.
                    val last = before.lastOrNull()
                    val space = last != null && !last.isWhitespace() && last !in OPENING
                    swiped = words[0]
                    view.note("words", words.joinToString(", "))
                    connection.commitText((if (space) " " else "") + words[0], 1)
                    view.showSwipe(words)
                }
                "commit" -> {
                    val correction = keyboard?.correction
                    if (text in SEPARATORS && correction != null &&
                        Dictionaries.preferences(this).getBoolean(Dictionaries.AUTOCORRECT, false)
                    ) {
                        val (word, replaces) = correction
                        val typed = connection.getTextBeforeCursor(CONTEXT_LENGTH, 0).toString()
                            .let { it.substring(it.offsetByCodePoints(it.length, -replaces)) }
                        connection.beginBatchEdit()
                        connection.deleteSurroundingTextInCodePoints(replaces, 0)
                        connection.commitText(word + text, 1)
                        connection.endBatchEdit()
                        lastCorrection = Correction(typed, word + text)
                        keyboard?.note("corrected", "$typed -> $word")
                    } else {
                        if (text in SEPARATORS) keyboard?.unknown?.let { learn(it, 1) }
                        // A letter right after a swiped word starts the next word, as on
                        // other keyboards; punctuation stays attached to the word.
                        val before = connection.getTextBeforeCursor(CONTEXT_LENGTH, 0)?.toString().orEmpty()
                        val space = justSwiped != null && before.endsWith(justSwiped) && text.firstOrNull()?.isLetterOrDigit() == true
                        connection.commitText(if (space) " $text" else text, 1)
                    }
                }
                "backspace" -> {
                    val selected = connection.getSelectedText(0)
                    val before = connection.getTextBeforeCursor(CONTEXT_LENGTH, 0)?.toString().orEmpty()
                    when {
                        !selected.isNullOrEmpty() -> connection.commitText("", 1)
                        // Right after a swipe, backspace takes the whole word back.
                        justSwiped != null && before.endsWith(justSwiped) ->
                            connection.deleteSurroundingText(justSwiped.length, 0)
                        // Right after a correction, backspace puts back what was typed.
                        undo != null && before.endsWith(undo.inserted) -> {
                            connection.beginBatchEdit()
                            connection.deleteSurroundingText(undo.inserted.length, 0)
                            connection.commitText(undo.typed + undo.inserted.substring(undo.inserted.length - 1), 1)
                            connection.endBatchEdit()
                            learn(undo.typed, 1)
                        }
                        else -> deleteCharacter()
                    }
                }
                "enter" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        },
        onPick = { word, replaces ->
            val connection = currentInputConnection ?: return@KeyboardView
            connection.finishComposingText()
            lastCorrection = null
            swiped = null
            // Choosing the word as typed over the suggestions keeps it on purpose.
            if (word == keyboard?.unknown) learn(word, LEARNED)
            connection.beginBatchEdit()
            connection.deleteSurroundingTextInCodePoints(replaces, 0)
            connection.commitText("$word ", 1)
            connection.endBatchEdit()
        },
        onSettings = {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        onEmoji = { frame?.let { it.showEmoji(!it.emojiShown) } },
        onPasswords = { passwordManager()?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
    ).let { view ->
        keyboard = view
        view.accents = accents
        view.logContext = {
            if (suggesting) currentInputConnection?.getTextBeforeCursor(CONTEXT_LENGTH, 0)?.toString() else null
        }
        val emoji = EmojiPanel(
            this,
            onEmoji = { currentInputConnection?.run { finishComposingText(); commitText(it, 1) } },
            onSpace = { currentInputConnection?.run { finishComposingText(); commitText(" ", 1) } },
            onBackspace = { deleteCharacter() },
            onClose = { frame?.showEmoji(false) },
        )
        KeyboardFrame(this, view, emoji).also { frame = it }
    }

    /**
     * Asks the autofill service for suggestions shaped to fit the strip:
     * as tall as it, from a square up to most of its width.
     */
    @TargetApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        // Asked before the keyboard view exists, so sized from the strip's dp height.
        val height = ((KeyboardView.STRIP_DP - 8) * resources.displayMetrics.density).toInt()
        val styles = UiVersions.newStylesBuilder().addStyle(InlineSuggestionUi.newStyleBuilder().build()).build()
        val spec = InlinePresentationSpec.Builder(Size(height, height), Size(resources.displayMetrics.widthPixels * 2 / 3, height))
            .setStyle(styles)
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            .build()
    }

    /** Shows what the autofill service offers; an empty answer clears it. */
    @TargetApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val frame = frame ?: return false
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            frame.show(emptyList())
            return true
        }
        val views = arrayOfNulls<Pair<View, Boolean>>(suggestions.size)
        var pending = suggestions.size
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        for ((index, suggestion) in suggestions.withIndex()) {
            suggestion.inflate(this, size, mainExecutor) { view ->
                if (view != null) views[index] = view to suggestion.info.isPinned
                pending -= 1
                if (pending == 0) frame.show(views.filterNotNull())
            }
        }
        return true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        frame?.show(emptyList())
        frame?.showEmoji(false)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ) || info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val noSuggestions = info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        suggesting = !password && !noSuggestions
        swiped = null
        // Device colours follow the wallpaper and dark mode, which may have changed.
        keyboard?.theme = Theme.current(this)
        keyboard?.passwordsAvailable = passwordManager() != null
        refreshStrip()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshStrip()
        markWord(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    /**
     * Marks the word before the cursor as the one being written, which the
     * app underlines, as other keyboards do; nothing is marked inside a
     * selection, after a space, or where suggestions are off. Called with
     * the selection and the marked range the app reports, so a mark already
     * in place is left alone.
     */
    private fun markWord(start: Int, end: Int, markedStart: Int, markedEnd: Int) {
        val connection = currentInputConnection ?: return
        val before = if (suggesting && start == end) connection.getTextBeforeCursor(CONTEXT_LENGTH, 0) else null
        val length = before?.reversed()?.takeWhile { it.isLetterOrDigit() || it == '\'' || it == '’' || it == '-' }?.length ?: 0
        if (length == 0) {
            if (markedStart != -1) connection.finishComposingText()
        } else if (markedStart != start - length || markedEnd != start) {
            connection.setComposingRegion(start - length, start)
        }
    }

    /** The last character of `text` as the reader sees it, an emoji or a letter with its marks. */
    private fun lastCharacter(text: String): String {
        val breaks = java.text.BreakIterator.getCharacterInstance()
        breaks.setText(text)
        return text.substring(breaks.preceding(text.length).coerceAtLeast(0))
    }

    /**
     * Deletes the character before the cursor as the reader sees it: a whole
     * emoji, flag or letter with its marks, however many code points it is.
     */
    private fun deleteCharacter() {
        val connection = currentInputConnection ?: return
        connection.finishComposingText()
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            connection.commitText("", 1)
            return
        }
        val before = connection.getTextBeforeCursor(CONTEXT_LENGTH, 0)?.toString().orEmpty()
        if (before.isNotEmpty()) connection.deleteSurroundingText(lastCharacter(before).length, 0)
    }

    /**
     * The app of the autofill service the phone uses, if one is set up. A
     * keyboard cannot ask it to fill a field, only the app being typed into
     * can; opening it lets the user look up and copy what the field needs.
     */
    private fun passwordManager(): Intent? {
        val service = android.provider.Settings.Secure.getString(contentResolver, "autofill_service") ?: return null
        val component = android.content.ComponentName.unflattenFromString(service) ?: return null
        if (component.packageName == packageName) return null
        return packageManager.getLaunchIntentForPackage(component.packageName)
    }

    /** Counts a word no dictionary has as kept, when learning is switched on. */
    private fun learn(word: String, times: Int) {
        if (!Dictionaries.preferences(this).getBoolean(Dictionaries.LEARNING, false)) return
        learned[word] = Core.lexiconLearn(lexicon, word, times)
        Dictionaries.saveLearned(this, learned)
    }

    private fun refreshStrip() {
        val view = keyboard ?: return
        val before = currentInputConnection?.getTextBeforeCursor(CONTEXT_LENGTH, 0)
        // A swipe's alternatives stay until the word is changed or left.
        val word = swiped
        if (word != null && before?.endsWith(word) == true) return
        if (!suggesting || before == null) view.clearSuggestions() else view.suggest(lexicon, before.toString())
    }

    /**
     * The band above the keys is for popups only: the app is laid out as if
     * the keyboard started at the strip, and touches on the band reach the app.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = keyboard ?: return
        if (!view.isShown) return
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val contentTop = location[1] + view.headroom
        outInsets.contentTopInsets = contentTop
        outInsets.visibleTopInsets = contentTop
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, contentTop, view.width, location[1] + view.height)
    }

    private companion object {
        /** Enough text before the cursor for the word being typed and the one before it. */
        const val CONTEXT_LENGTH = 64

        /** How often a word has to be kept to count as learned, as the core counts. */
        const val LEARNED = 2

        /** What ends a word and so triggers autocorrect. */
        val SEPARATORS = setOf(" ", ".", ",", "!", "?", ";", ":")

        /** The last word before the cursor with the spaces after it. */
        val WORD_AT_END = Regex("\\S*\\s*$")

        /** What a swiped word follows without a space. */
        const val OPENING = "([{\"'¿¡"
    }
}
