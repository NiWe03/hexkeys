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
    /** The lexicon in use; read and replaced on the [Core.words] thread only, once created. */
    @Volatile
    private var lexicon = 0L

    /** What holding a letter offers in the languages loaded, for keyboard views made later. */
    private var accents = ""

    /** Reads dictionaries one set at a time, away from the main thread. */
    private val loader = Executors.newSingleThreadExecutor()
    /** Off in password fields and where the app asks for no suggestions. */
    private var suggesting = false

    /**
     * A correction autocorrect just made: the end of the text as typed and
     * as put in its place, and the word typed when it was one no dictionary
     * has, to learn when it is put back.
     */
    private data class Correction(val typed: String, val inserted: String, val unknown: String? = null)
    private var lastCorrection: Correction? = null

    /**
     * Where in the field a word ends that was put back by hand after a
     * correction, which autocorrect leaves as it is from then on; -1 for none.
     */
    private var keptEnd = -1

    /** What the delete swipe under way has deleted, last piece last, to put back when it slides back. */
    private val deleted = ArrayDeque<String>()

    /** The field typed in, with the text before the cursor kept here. */
    private val editor = Editor(this)

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editor.reset(attribute.initialSelStart, attribute.initialSelEnd)
        keptEnd = -1
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
                Core.words.execute { Core.lexiconForget(lexicon) }
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
        Core.words.execute {
            Core.lexiconDestroy(lexicon)
            lexicon = 0L
        }
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
            val added = files.filter { file -> Dictionaries.read(this, file)?.let { Core.lexiconAdd(fresh, it) } == true }
            for ((word, times) in words) Core.lexiconLearn(fresh, word, times)
            // Spellings told apart by context, for the languages that have counts.
            val counted = assets.list("contexts").orEmpty().toSet()
            for ((index, file) in added.withIndex()) {
                val name = "${languageCode(file)}.tsv"
                if (name in counted) Core.lexiconContexts(fresh, index, assets.open("contexts/$name").bufferedReader().readText())
            }
            val accents = Core.lexiconAccents(fresh, -1)
            Core.words.execute {
                if (lexicon == 0L) {
                    Core.lexiconDestroy(fresh)
                    return@execute
                }
                Core.lexiconDestroy(lexicon)
                lexicon = fresh
                mainExecutor.execute {
                    lexiconFiles = added
                    pinLanguage(null)
                    this.accents = accents
                    keyboard?.accents = accents
                    refreshStrip()
                }
            }
        }
    }

    /**
     * Respells a word before the cursor where the words now after it show
     * another spelling was meant ("its" before "good" is "it's"), and keeps
     * the change for backspace to undo. `typed` is the text before the
     * cursor as the user typed it, before any correction.
     */
    private fun amend(typed: String) {
        if (!suggesting || lexicon == 0L) return
        val before = editor.textBefore()
        val kept = if (keptEnd >= 0) editor.selectionStart - keptEnd else -1
        val result = runCatching {
            Core.words.submit<Array<String>> { Core.amend(lexicon, before, kept) }
                .get(STRIP_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()
        if (result == null || result.size < 2) return
        val replaced = result[0].toInt()
        editor.beginBatch()
        editor.deleteBefore(replaced)
        editor.commit(result[1])
        editor.endBatch()
        keyboard?.note("amended", "${before.takeLast(replaced)} -> ${result[1]}")
        // Backspace puts back everything as typed, from the first change on.
        val after = editor.textBefore()
        val same = typed.commonPrefixWith(after).length
        val unknown = lastCorrection?.unknown
        lastCorrection = Correction(typed.substring(same), after.substring(same), unknown)
    }

    /** A swipe being decoded; actions arriving meanwhile wait in [waiting]. */
    private var decoding = false
    private val waiting = ArrayDeque<Pair<String, String>>()

    /** What a key, gesture or hold asks for, in the order they came. */
    private fun act(kind: String, text: String) {
        if (decoding) {
            waiting.addLast(kind to text)
            return
        }
        perform(kind, text)
    }

    /** The emoji search's query, which the keys type while it is open. */
    private val query = StringBuilder()
    private val emojiSearch by lazy { EmojiSearch(this) }

    /** Opens the search over the strip, the letters below it typing the query. */
    private fun openEmojiSearch() {
        query.clear()
        frame?.showSearch(true) {
            frame?.showSearch(false)
            frame?.showEmoji(true)
        }
        showEmojiResults()
    }

    /** Keys while searching emoji go to the query; enter or the emoji found end it. */
    private fun searchKey(kind: String, text: String) {
        when (kind) {
            "commit" -> query.append(text)
            "backspace" -> if (query.isNotEmpty()) query.setLength(query.length - 1)
            "delete" -> query.setLength(maxOf(0, query.length - (text.substringBefore(' ').toIntOrNull() ?: 1)))
            "enter", "enter_action" -> {
                frame?.showSearch(false)
                return
            }
            else -> return
        }
        showEmojiResults()
    }

    private fun showEmojiResults() {
        // Until something is typed, the recently used are at hand.
        val found = if (query.isBlank()) frame?.emoji?.recent().orEmpty() else emojiSearch.find(query.toString())
        frame?.showResults(query.toString(), found) { emoji ->
            editor.finishComposing()
            editor.commit(emoji)
            frame?.emoji?.remember(emoji)
        }
    }

    /** Puts in the word a swipe spelled, then whatever was typed while it was decoded. */
    private fun swiped(words: Array<String>) {
        decoding = false
        val view = keyboard
        if (view != null && words.isNotEmpty() && currentInputConnection != null) {
            editor.finishComposing()
            // Swiped words are put in with a space between them, as on other keyboards.
            val last = editor.textBefore().lastOrNull()
            val space = last != null && !last.isWhitespace() && last !in OPENING
            swiped = words[0]
            view.note("words", words.joinToString(", "))
            val typed = editor.textBefore() + (if (space) " " else "") + words[0]
            editor.commit((if (space) " " else "") + words[0])
            if (Dictionaries.autocorrectMode(this) != "off") amend(typed)
            lastCorrection = null
            view.showSwipe(words)
        }
        while (!decoding && waiting.isNotEmpty()) {
            val (kind, text) = waiting.removeFirst()
            perform(kind, text)
        }
    }

    private fun perform(kind: String, text: String) {
        if (currentInputConnection == null) return
        if (frame?.searching == true) {
            searchKey(kind, text)
            return
        }
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
                keyboard?.deletedText = deleted.reversed().joinToString("")
            }
            "swipe" -> {
                val view = keyboard ?: return
                if (!suggesting) return
                // Decoded on the words thread; what is typed meanwhile waits its turn.
                val path = view.swiped()
                val letters = view.letters
                val before = connection.textBefore()
                decoding = true
                Core.words.execute {
                    val words = if (lexicon == 0L || letters == 0L) emptyArray() else Core.decodeSwipe(lexicon, letters, path, before)
                    mainExecutor.execute { swiped(words) }
                }
            }
            "commit" -> {
                val mode = Dictionaries.autocorrectMode(this)
                // A space typed before the strip caught up waits for it, briefly.
                if (text in SEPARATORS && mode != "off") currentStrip()
                val correction = when (mode) {
                    "all" -> keyboard?.correction ?: keyboard?.fix
                    "obvious" -> keyboard?.fix
                    else -> null
                }
                val typedBefore = connection.textBefore()
                if (text in SEPARATORS && correction != null) {
                    val (word, replaces) = correction
                    val typed = typedBefore.substring(typedBefore.offsetByCodePoints(typedBefore.length, -replaces))
                    connection.beginBatch()
                    connection.deleteCodePointsBefore(replaces)
                    connection.commit(word + text)
                    connection.endBatch()
                    lastCorrection = Correction(typed + text, word + text, typed)
                    keyboard?.note("corrected", "$typed -> $word")
                    if (mode != "off") amend(typedBefore + text)
                } else {
                    if (text in SEPARATORS) keyboard?.unknown?.let { learn(it, 1) }
                    // A letter right after a swiped word starts the next word, as on
                    // other keyboards; punctuation stays attached to the word.
                    val before = connection.textBefore()
                    val space = justSwiped != null && before.endsWith(justSwiped) && text.firstOrNull()?.isLetterOrDigit() == true
                    connection.commit(if (space) " $text" else text)
                    if (text in SEPARATORS && mode != "off") amend(typedBefore + text)
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
                        val start = connection.selectionStart
                        connection.commit(undo.typed)
                        connection.endBatch()
                        // The first word put back stays as typed.
                        keptEnd = start + (WORD_AT_START.find(undo.typed)?.value?.length ?: 0)
                        undo.unknown?.let { learn(it, 1) }
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
            // In a field of several lines enter starts a new one, written as text so an
            // app that sends on the enter key does not; holding it does the field's action.
            "enter" -> if (multiLine) connection.commit("\n") else performAction()
            "enter_action" -> performAction()
            "language" -> {
                // Guessing, then each language in turn, then guessing again.
                val count = lexiconFiles.size
                if (count == 0) return
                val order = listOf<Int?>(null) + (0 until count)
                val at = order.indexOf(pinned)
                pinLanguage(order[(at + text.toInt() + order.size) % order.size])
            }
            "cursor" -> {
                val (dx, dy) = text.split(" ").map { it.toInt() }
                connection.moveCursor(dx, dy)
            }
        }
        keyboard?.slow(kind, SystemClock.uptimeMillis() - started)
    }

    override fun onCreateInputView(): View = KeyboardView(
        this,
        onAction = { kind, text -> act(kind, text) },
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
            if (Dictionaries.autocorrectMode(this) != "off") amend(connection.textBefore())
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
            onSearch = { openEmojiSearch() },
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
        // Hidden, the keyboard stops listening; what was said is still put in.
        voice.stop()
        // A language picked by hand holds for the field it was picked in.
        if (pinned != null) pinLanguage(null)
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
        keyboard?.listening = voice.listening
        keyboard?.recognising = voice.pending
        // The field's own action for enter, such as send or search, and whether it takes new lines.
        // A field may name an action yet ask that enter not do it (Paseo: done, which
        // closes the keyboard); enter's key event then goes to the app instead.
        val noEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        action = when {
            info.actionLabel != null -> info.actionId
            noEnterAction -> null
            else -> (info.imeOptions and EditorInfo.IME_MASK_ACTION).takeIf { it in ACTION_ICONS }
        }
        multiLine = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (GestureLog.enabled(this)) {
            GestureLog.write(this, org.json.JSONObject()
                .put("time", System.currentTimeMillis())
                .put("field", info.packageName)
                .put("inputType", Integer.toHexString(info.inputType))
                .put("imeOptions", Integer.toHexString(info.imeOptions))
                .put("actionLabel", info.actionLabel?.toString())
                .put("actionId", info.actionId))
        }
        keyboard?.enterIcon = if (multiLine) R.drawable.icon_keyboard_return else actionIcon()
        keyboard?.enterHint = if (multiLine) action?.let { actionIcon() } else null
        refreshStrip()
    }

    /** The field's action for enter, if it has one, and whether enter starts a new line in it. */
    private var action: Int? = null
    private var multiLine = false

    private fun actionIcon() = action?.let { ACTION_ICONS[it] } ?: R.drawable.icon_send

    /** Performs the field's action, or where it has none sends the enter key, which apps take as their own. */
    private fun performAction() {
        val id = action
        if (id != null) currentInputConnection?.performEditorAction(id) else editor.sendKey(KeyEvent.KEYCODE_ENTER)
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
        // The language being written first: models told one language take it.
        val current = language?.let { lexiconFiles.getOrNull(it) }?.let(::languageCode)
        val enabled = Dictionaries.enabled(this)
        val all = Dictionaries.catalog(this).filter { it.file in enabled }.map { languageCode(it.file) }
        return (listOfNotNull(current) + all).distinct().joinToString(",")
    }

    /** Counts a word no dictionary has as kept, when learning is switched on. */
    private fun learn(word: String, times: Int) {
        if (!Dictionaries.preferences(this).getBoolean(Dictionaries.LEARNING, false)) return
        Core.words.execute {
            val count = Core.lexiconLearn(lexicon, word, times)
            mainExecutor.execute {
                learned[word] = count
                Dictionaries.saveLearned(this, learned)
            }
        }
    }

    /** The dictionaries in the lexicon, in its order. */
    private var lexiconFiles = emptyList<String>()

    /** The dictionary the language is taken from, as the last strip had it, and whether the user picked it. */
    private var language: Int? = null
    private var pinned: Int? = null

    /**
     * Picks a language for this field, or goes back to guessing it from the
     * words written with null.
     */
    private fun pinLanguage(index: Int?) {
        pinned = index
        Core.words.execute { if (lexicon != 0L) Core.lexiconPin(lexicon, index ?: -1) }
        showLanguage(index ?: language)
        refreshStrip()
    }

    /** Shows the language on the space bar, dim while it is only a guess. */
    private fun showLanguage(index: Int?) {
        // Holding a letter offers the language in use's accents first.
        if (index != language) {
            Core.words.execute {
                if (lexicon == 0L) return@execute
                val accents = Core.lexiconAccents(lexicon, index ?: -1)
                mainExecutor.execute {
                    this.accents = accents
                    keyboard?.accents = accents
                }
            }
        }
        language = index
        val file = index?.let { lexiconFiles.getOrNull(it) }
        val name = file?.let { f -> Dictionaries.catalog(this).firstOrNull { it.file == f }?.name } ?: if (file == Dictionaries.BUILT_IN) "English" else null
        keyboard?.spaceLabel = name?.substringBefore(" (").orEmpty()
        keyboard?.spacePinned = pinned != null
    }

    /** The ISO code of a dictionary's language. */
    private fun languageCode(file: String) =
        (Dictionaries.catalog(this).firstOrNull { it.file == file }?.locale ?: "en").substringBefore('_').lowercase()

    /** Counts strips asked for, so only the newest one is shown. */
    @Volatile
    private var stripRequest = 0

    /**
     * Makes sure the strip shown is the one for the text before the cursor
     * now, working it out here when the words thread has not got to it yet,
     * waiting at most [STRIP_WAIT_MS].
     */
    private fun currentStrip() {
        val view = keyboard ?: return
        val before = editor.textBefore()
        if (!suggesting || view.stripBefore == before) return
        val letters = view.letters
        val strip = runCatching {
            Core.words.submit<Array<String>> { Core.strip(lexicon, letters, before) }
                .get(STRIP_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return
        view.showStrip(before, strip)
    }

    private fun refreshStrip() {
        val view = keyboard ?: return
        val reading = SystemClock.uptimeMillis()
        val before = if (currentInputConnection == null) null else editor.textBefore()
        view.slow("text before", SystemClock.uptimeMillis() - reading)
        // A swipe's alternatives stay until the word is changed or left.
        val word = swiped
        if (word != null && before?.endsWith(word) == true) return
        if (!suggesting || before == null) {
            view.clearSuggestions()
            return
        }
        // Worked out on the words thread; a strip overtaken by newer text is dropped.
        val request = ++stripRequest
        val letters = view.letters
        Core.words.execute {
            if (request != stripRequest || letters == 0L) return@execute
            val started = SystemClock.uptimeMillis()
            val strip = Core.strip(lexicon, letters, before)
            val took = SystemClock.uptimeMillis() - started
            mainExecutor.execute {
                view.slow("suggest (words thread)", took)
                if (request == stripRequest) {
                    view.showStrip(before, strip)
                    showLanguage(strip.getOrNull(7)?.toIntOrNull()?.takeIf { it >= 0 })
                }
            }
        }
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
        /** The longest a space waits for the strip of the word before it, in ms. */
        const val STRIP_WAIT_MS = 300L

        /** Icons for the actions a field can give enter. */
        val ACTION_ICONS = mapOf(
            EditorInfo.IME_ACTION_GO to R.drawable.icon_arrow_forward,
            EditorInfo.IME_ACTION_SEARCH to R.drawable.icon_search,
            EditorInfo.IME_ACTION_SEND to R.drawable.icon_send,
            EditorInfo.IME_ACTION_NEXT to R.drawable.icon_keyboard_tab,
            EditorInfo.IME_ACTION_PREVIOUS to R.drawable.icon_arrow_back,
            EditorInfo.IME_ACTION_DONE to R.drawable.icon_check,
        )

        /** How often a word has to be kept to count as learned, as the core counts. */
        const val LEARNED = 2

        /** What ends a word and so triggers autocorrect. */
        val SEPARATORS = setOf(" ", ".", ",", "!", "?", ";", ":")

        /** The last word before the cursor with the spaces after it. */
        val WORD_AT_END = Regex("\\S*\\s*$")

        /** The word, or the rest of one, a text starts with. */
        val WORD_AT_START = Regex("^[\\p{L}\\p{N}'’-]*")

        /** What a swiped word follows without a space. */
        const val OPENING = "([{\"'¿¡"
    }
}
