package io.github.tieo.hexkeys

import android.content.Intent
import java.util.concurrent.Executors
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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

    /** The field typed in, with the text before the cursor kept here. */
    private val editor = Editor(this)

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editor.reset(attribute.initialSelStart, attribute.initialSelEnd)
    }

    /** The word swiped right before the delete swipe under way, which it takes first, whole. */
    private var swipedBeforeDelete: String? = null

    /** The word a swipe just put in, while its alternatives show in the strip. */
    private var swiped: String? = null

    /** Reloads the dictionaries when the languages change in settings. */
    private val settingsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Dictionaries.ENABLED -> loadDictionaries()
            Theme.KEY -> keyboard?.theme = Theme.current(this)
            KeyboardView.LAYOUT, KeyboardView.DELETE_FROM_LEFT, KeyboardView.CAPITAL, KeyboardView.EDIT_KEYS ->
                keyboard?.requestLayout()
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
        voice.release()
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
            if (currentInputConnection == null) return@KeyboardView
            val connection = editor
            val started = SystemClock.uptimeMillis()
            // Text put in while a word is marked would replace it.
            if (kind != "redraw" && kind != "nothing") connection.finishComposing()
            val undo = lastCorrection
            lastCorrection = null
            val justSwiped = swiped
            swiped = null
            when (kind) {
                "delete" -> {
                    val (pieces, words, started) = text.split(" ").map { it.toInt() }
                    var before = connection.textBefore()
                    if (started == 1) {
                        deleted.clear()
                        swipedBeforeDelete = justSwiped?.takeIf { before.endsWith(it) }
                    }
                    val word = swipedBeforeDelete
                    connection.beginBatch()
                    // A selection goes first, as one piece, as backspace takes it; so
                    // does a word just swiped.
                    val selected = if (deleted.isEmpty() && pieces > 0) connection.selectedText() else ""
                    if (selected.isNotEmpty()) {
                        connection.commit("")
                        deleted.addLast(selected)
                    } else if (deleted.isEmpty() && pieces > 0 && word != null && before.endsWith(word)) {
                        connection.deleteBefore(word.length)
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
                        connection.deleteBefore(piece.length)
                        before = before.dropLast(piece.length)
                        // The copy ran short of the field's text: read on.
                        if (before.isEmpty()) before = connection.textBefore()
                        deleted.addLast(piece)
                    }
                    while (deleted.size > pieces) connection.commit(deleted.removeLast())
                    connection.endBatch()
                }
                "swipe" -> {
                    val view = keyboard ?: return@KeyboardView
                    if (!suggesting) return@KeyboardView
                    val before = connection.textBefore()
                    val words = view.swipe(lexicon, before)
                    if (words.isEmpty()) return@KeyboardView
                    // Swiped words are put in with a space between them, as on other keyboards.
                    val last = before.lastOrNull()
                    val space = last != null && !last.isWhitespace() && last !in OPENING
                    swiped = words[0]
                    view.note("words", words.joinToString(", "))
                    connection.commit((if (space) " " else "") + words[0])
                    view.showSwipe(words)
                }
                "commit" -> {
                    val correction = keyboard?.correction
                    if (text in SEPARATORS && correction != null &&
                        Dictionaries.preferences(this).getBoolean(Dictionaries.AUTOCORRECT, false)
                    ) {
                        val (word, replaces) = correction
                        val typed = connection.textBefore()
                            .let { it.substring(it.offsetByCodePoints(it.length, -replaces)) }
                        connection.beginBatch()
                        connection.deleteCodePointsBefore(replaces)
                        connection.commit(word + text)
                        connection.endBatch()
                        lastCorrection = Correction(typed, word + text)
                        keyboard?.note("corrected", "$typed -> $word")
                    } else {
                        if (text in SEPARATORS) keyboard?.unknown?.let { learn(it, 1) }
                        // A letter right after a swiped word starts the next word, as on
                        // other keyboards; punctuation stays attached to the word.
                        val before = connection.textBefore()
                        val space = justSwiped != null && before.endsWith(justSwiped) && text.firstOrNull()?.isLetterOrDigit() == true
                        connection.commit(if (space) " $text" else text)
                    }
                }
                "backspace" -> {
                    val before = connection.textBefore()
                    when {
                        connection.hasSelection -> connection.commit("")
                        // Right after a swipe, backspace takes the whole word back.
                        justSwiped != null && before.endsWith(justSwiped) ->
                            connection.deleteBefore(justSwiped.length)
                        // Right after a correction, backspace puts back what was typed.
                        undo != null && before.endsWith(undo.inserted) -> {
                            connection.beginBatch()
                            connection.deleteBefore(undo.inserted.length)
                            connection.commit(undo.typed + undo.inserted.substring(undo.inserted.length - 1))
                            connection.endBatch()
                            learn(undo.typed, 1)
                        }
                        else -> deleteCharacter()
                    }
                }
                "replace" -> {
                    // A held letter's capital, typed already, swapped for an accent.
                    val (old, new) = text.split("\t")
                    val before = connection.textBefore()
                    connection.beginBatch()
                    if (before.endsWith(old)) connection.deleteBefore(old.length)
                    connection.commit(new)
                    connection.endBatch()
                }
                "enter" -> connection.sendKey(KeyEvent.KEYCODE_ENTER)
            }
            keyboard?.slow(kind, SystemClock.uptimeMillis() - started)
        },
        onPick = { word, replaces ->
            if (currentInputConnection == null) return@KeyboardView
            val connection = editor
            connection.finishComposing()
            lastCorrection = null
            swiped = null
            // Choosing the word as typed over the suggestions keeps it on purpose.
            if (word == keyboard?.unknown) learn(word, LEARNED)
            connection.beginBatch()
            connection.deleteCodePointsBefore(replaces)
            connection.commit("$word ")
            connection.endBatch()
        },
        onSettings = {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        onEmoji = { frame?.let { it.showEmoji(!it.emojiShown) } },
        onPasswords = { passwordManager()?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        onVoice = { toggleVoice() },
    ).let { view ->
        keyboard = view
        view.accents = accents
        view.logContext = { if (suggesting && currentInputConnection != null) editor.textBefore() else null }
        val emoji = EmojiPanel(
            this,
            onEmoji = { editor.finishComposing(); editor.commit(it) },
            onSpace = { editor.finishComposing(); editor.commit(" ") },
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
        val started = SystemClock.uptimeMillis()
        editor.selectionChanged(newSelStart, newSelEnd)
        refreshStrip()
        markWord(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        keyboard?.slow("selection", SystemClock.uptimeMillis() - started)
    }

    /**
     * Marks the word before the cursor as the one being written, which the
     * app underlines, as other keyboards do; nothing is marked inside a
     * selection, after a space, or where suggestions are off. Called with
     * the selection and the marked range the app reports, so a mark already
     * in place is left alone.
     */
    private fun markWord(start: Int, end: Int, markedStart: Int, markedEnd: Int) {
        if (currentInputConnection == null) return
        val before = if (suggesting && start == end) editor.textBefore() else null
        val length = before?.reversed()?.takeWhile { it.isLetterOrDigit() || it == '\'' || it == '’' || it == '-' }?.length ?: 0
        if (length == 0) {
            if (markedStart != -1) editor.finishComposing()
        } else if (markedStart != start - length || markedEnd != start) {
            editor.markComposing(start - length, start)
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
        if (currentInputConnection == null) return
        editor.finishComposing()
        if (editor.hasSelection) {
            editor.commit("")
            return
        }
        val before = editor.textBefore()
        if (before.isNotEmpty()) editor.deleteBefore(lastCharacter(before).length)
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

    private val voice by lazy { Voice(this) }

    /**
     * Starts listening, or stops and puts in what was said, with a space
     * before it where it follows a word. Without a downloaded model or the
     * microphone allowed, settings open, where both are set up.
     */
    private fun toggleVoice() {
        val view = keyboard ?: return
        if (voice.listening) {
            voice.stop()
            return
        }
        if (!Voice.ready(this)) {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.SECTION, SettingsActivity.VOICE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            return
        }
        val started = voice.start(voiceLanguages(), Dictation(
            level = { view.voiceLevel(it) },
            text = { text ->
                if (currentInputConnection == null) return@Dictation
                editor.finishComposing()
                val last = editor.textBefore().lastOrNull()
                val space = last != null && !last.isWhitespace() && last !in OPENING
                editor.commit((if (space) " " else "") + text)
            },
            changed = {
                view.listening = voice.listening
                view.recognising = voice.pending
            },
        ))
        if (started) view.listening = true
    }

    /** The languages of the enabled dictionaries as ISO codes, for recognising speech. */
    private fun voiceLanguages(): String {
        val enabled = Dictionaries.enabled(this)
        return Dictionaries.catalog(this).filter { it.file in enabled }
            .map { it.locale.substringBefore('_').lowercase() }
            .distinct()
            .joinToString(",")
    }

    /** Counts a word no dictionary has as kept, when learning is switched on. */
    private fun learn(word: String, times: Int) {
        if (!Dictionaries.preferences(this).getBoolean(Dictionaries.LEARNING, false)) return
        learned[word] = Core.lexiconLearn(lexicon, word, times)
        Dictionaries.saveLearned(this, learned)
    }

    private fun refreshStrip() {
        val view = keyboard ?: return
        val reading = SystemClock.uptimeMillis()
        val before = if (currentInputConnection == null) null else editor.textBefore()
        view.slow("text before", SystemClock.uptimeMillis() - reading)
        // A swipe's alternatives stay until the word is changed or left.
        val word = swiped
        if (word != null && before?.endsWith(word) == true) return
        val suggesting = SystemClock.uptimeMillis()
        if (!this.suggesting || before == null) view.clearSuggestions() else view.suggest(lexicon, before)
        view.slow("suggest", SystemClock.uptimeMillis() - suggesting)
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
