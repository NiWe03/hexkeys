//! Word suggestions from one or more dictionaries.
//!
//! The typed word is matched against every dictionary word by a weighted
//! edit distance computed down the trie, one row per dictionary character,
//! so a branch is dropped as soon as no word below it can come close
//! enough. Case and accents cost nothing: the keyboard types neither
//! umlauts nor accents directly, so "uber" has to find "über" and "strasse"
//! has to find "Straße". Words that start with what was typed are offered
//! as completions. Candidates are ranked by frequency against cost, with the
//! frequency taken from the previous word's next-word list where it has one.

use crate::dictionary::{Dictionary, Error, Node};
use std::collections::HashMap;
use unicode_normalization::UnicodeNormalization;

/// A letter the dictionary has but the typed word lacks, or the other way round.
const GAP: f32 = 1.0;
/// Two neighbouring letters swapped.
const SWAP: f32 = 0.7;
/// Finishing a word that starts with what was typed.
const COMPLETION: f32 = 0.5;
/// Frequency points one unit of cost is worth. Frequencies run 0 to 255 on
/// a logarithmic scale.
const COST_WEIGHT: f32 = 60.0;

/// How many letters a completion may add to what was typed. Without a limit
/// one typed letter would walk every word starting with it.
const COMPLETION_LETTERS: usize = 5;

/// How much a typed character differs from a dictionary character, both
/// already folded to lower case without accents: 0 for the same letter.
pub type Substitution<'a> = &'a dyn Fn(char, char) -> f32;

#[derive(Clone, Debug, PartialEq)]
pub struct Suggestion {
    pub word: String,
    pub score: f32,
    /// Spelled exactly as typed, up to case.
    pub exact: bool,
    /// Longer than what was typed rather than a correction of it.
    pub completion: bool,
}

/// The best match of one word found so far.
#[derive(Clone, Copy)]
struct Found {
    score: f32,
    exact: bool,
    completion: bool,
}

impl Found {
    /// Keeps the better scoring of two matches of the same word; it is
    /// exact if either was.
    fn merge(&mut self, other: Found) {
        if other.score > self.score {
            self.score = other.score;
            self.completion = other.completion;
        }
        self.exact |= other.exact;
    }
}

/// What a dictionary character may stand for in typed text, folded: lower
/// case, accents dropped, ß as "ss" or "s", an umlaut also as its letter
/// plus e, the usual spelling without umlauts, and apostrophes and hyphens
/// also as nothing, since they sit behind the symbol layer.
fn spellings(c: char) -> Vec<Vec<char>> {
    let lower: Vec<char> = c.to_lowercase().collect();
    if lower.len() != 1 {
        return vec![lower.iter().flat_map(|&c| fold(c)).collect()];
    }
    let c = lower[0];
    match c {
        'ß' => vec![vec!['s', 's'], vec!['s']],
        '\'' | '’' | '-' => vec![vec![], vec![c]],
        'ä' | 'ö' | 'ü' => vec![fold(c), vec![fold(c)[0], 'e']],
        _ => vec![fold(c)],
    }
}

/// A lower-case character without its accents; ligatures as two letters.
fn fold(c: char) -> Vec<char> {
    match c {
        'æ' => vec!['a', 'e'],
        'œ' => vec!['o', 'e'],
        'ø' => vec!['o'],
        'ł' => vec!['l'],
        'đ' => vec!['d'],
        'ı' => vec!['i'],
        _ => {
            let base: Vec<char> = c.to_string().nfd().filter(|c| !is_combining(*c)).collect();
            if base.is_empty() { vec![c] } else { base }
        }
    }
}

fn is_combining(c: char) -> bool {
    matches!(c as u32, 0x0300..=0x036F | 0x1AB0..=0x1AFF | 0x1DC0..=0x1DFF | 0x20D0..=0x20FF | 0xFE20..=0xFE2F)
}

/// The typed word folded the same way as dictionary characters.
fn fold_typed(typed: &str) -> Vec<char> {
    typed.chars().flat_map(|c| c.to_lowercase()).flat_map(fold).collect()
}

/// The largest cost a candidate may have, growing with the typed length:
/// one slip in a short word, two in a long one.
fn budget(length: usize) -> f32 {
    match length {
        0..=2 => 0.7,
        3..=4 => 1.2,
        5..=7 => 1.7,
        _ => 2.2,
    }
}

/// The typed word and what a slip costs: the edit distance side of a search.
struct Matcher<'a> {
    typed: Vec<char>,
    substitution: Substitution<'a>,
    budget: f32,
}

struct Search<'a> {
    dictionary: &'a Dictionary,
    matcher: &'a Matcher<'a>,
    found: HashMap<String, Found>,
}

/// The edit distance rows for the dictionary text so far.
#[derive(Clone)]
struct Rows {
    row: Vec<f32>,
    /// The row and character before the last one, for swaps.
    previous: Option<(Vec<f32>, char)>,
    last: Option<char>,
    /// The lowest cost of having matched all of the typed word against a
    /// prefix of the path, which is what a completion costs.
    prefix: f32,
}

impl Matcher<'_> {
    fn start(&self) -> Rows {
        Rows { row: (0..=self.typed.len()).map(|j| j as f32 * GAP).collect(), previous: None, last: None, prefix: f32::MAX }
    }

    /// The rows after one more folded dictionary character.
    fn step(&self, rows: &Rows, c: char) -> Rows {
        let n = self.typed.len();
        let mut row = vec![0.0; n + 1];
        row[0] = rows.row[0] + GAP;
        for j in 1..=n {
            let typed = self.typed[j - 1];
            let replace = rows.row[j - 1] + (self.substitution)(typed, c);
            let mut cost = replace.min(rows.row[j] + GAP).min(row[j - 1] + GAP);
            if let (Some((before, _)), Some(last)) = (&rows.previous, rows.last)
                && j >= 2
                && typed == last
                && self.typed[j - 2] == c
            {
                cost = cost.min(before[j - 2] + SWAP);
            }
            row[j] = cost;
        }
        let prefix = rows.prefix.min(row[n]);
        Rows { previous: Some((rows.row.clone(), c)), last: Some(c), prefix, row }
    }

    /// The rows after a dictionary character, taking whichever of its
    /// spellings matches best.
    fn advance(&self, rows: &Rows, c: char) -> Rows {
        let mut spellings =
            spellings(c).into_iter().map(|spelling| spelling.iter().fold(rows.clone(), |rows, &c| self.step(&rows, c)));
        let mut best = spellings.next().expect("every character has a spelling");
        // Cell by cell: each prefix of the typed word takes the spelling
        // that suits it, so "uber" and "ueber" both match "über" exactly.
        for other in spellings {
            for (cell, value) in best.row.iter_mut().zip(other.row) {
                *cell = cell.min(value);
            }
            best.prefix = best.prefix.min(other.prefix);
        }
        best
    }

    /// How a word whose rows end in `rows` matches, if within the budget.
    fn judge(&self, rows: &Rows, frequency: f32) -> Option<Found> {
        let exact = rows.row[self.typed.len()];
        let completion = rows.prefix + COMPLETION;
        let cost = exact.min(completion);
        (cost <= self.budget).then_some(Found {
            score: frequency - COST_WEIGHT * cost,
            exact: exact == 0.0,
            completion: completion < exact,
        })
    }

    /// How a whole word matches, for words outside a dictionary.
    fn word(&self, word: &str, frequency: f32) -> Option<Found> {
        let rows = word.chars().fold(self.start(), |rows, c| self.advance(&rows, c));
        self.judge(&rows, frequency)
    }
}

impl Search<'_> {
    fn visit(&mut self, nodes: Vec<Node>, rows: &Rows, word: &mut String) -> Result<(), Error> {
        let matcher = self.matcher;
        let n = matcher.typed.len();
        for node in nodes {
            let mut node_rows = rows.clone();
            for &c in &node.chars {
                node_rows = matcher.advance(&node_rows, c);
            }
            let length = word.len();
            word.extend(&node.chars);
            if let Some(frequency) = node.frequency
                && !node.hidden
                && let Some(found) = matcher.judge(&node_rows, frequency as f32)
            {
                match self.found.get_mut(word.as_str()) {
                    Some(entry) => entry.merge(found),
                    None => {
                        self.found.insert(word.clone(), found);
                    }
                }
            }
            let reachable = node_rows.row.iter().copied().fold(f32::MAX, f32::min);
            let completing = node_rows.prefix + COMPLETION <= matcher.budget
                && word.chars().count() < n + COMPLETION_LETTERS;
            if let Some(children) = node.children
                && (reachable <= matcher.budget || completing)
            {
                let children = self.dictionary.array(children)?;
                self.visit(children, &node_rows, word)?;
            }
            word.truncate(length);
        }
        Ok(())
    }
}

/// How often a word has to be kept before it counts as learned.
const LEARNED: u32 = 2;

/// The frequency a learned word ranks with: that of a common word, since
/// it is one of the user's.
const LEARNED_FREQUENCY: f32 = 180.0;

/// The dictionaries suggestions come from, plus the words learned from what
/// the user keeps typing that no dictionary has.
#[derive(Default)]
pub struct Lexicon {
    pub dictionaries: Vec<Dictionary>,
    learned: HashMap<String, u32>,
}

impl Lexicon {
    pub fn new(dictionaries: Vec<Dictionary>) -> Self {
        Self { dictionaries, learned: HashMap::new() }
    }

    /// Counts `word` as kept `times` more times; returns how often it now has been.
    pub fn learn(&mut self, word: &str, times: u32) -> u32 {
        let count = self.learned.entry(word.to_owned()).or_insert(0);
        *count = count.saturating_add(times);
        *count
    }

    pub fn forget(&mut self) {
        self.learned.clear();
    }

    fn knows(&self, word: &str) -> bool {
        self.learned.get(word).is_some_and(|&times| times >= LEARNED)
    }
}

/// Suggestions for `typed` after the word `previous`, best first.
pub fn suggest(
    lexicon: &Lexicon,
    typed: &str,
    previous: Option<&str>,
    substitution: Substitution,
    limit: usize,
) -> Result<Vec<Suggestion>, Error> {
    let folded = fold_typed(typed);
    if folded.is_empty() {
        return Ok(Vec::new());
    }
    let matcher = Matcher { budget: budget(folded.len()), typed: folded, substitution };
    let mut found: HashMap<String, Found> = HashMap::new();
    for (word, &times) in &lexicon.learned {
        if times >= LEARNED
            && let Some(match_) = matcher.word(word, LEARNED_FREQUENCY)
        {
            found.insert(word.clone(), match_);
        }
    }
    for dictionary in &lexicon.dictionaries {
        let mut search = Search { dictionary, matcher: &matcher, found: HashMap::new() };
        search.visit(dictionary.root()?, &matcher.start(), &mut String::new())?;
        // A word that often follows the previous one ranks by how often it does.
        if let Some(previous) = previous {
            for (word, frequency) in next_words(dictionary, previous)? {
                if let Some(entry) = search.found.get_mut(&word) {
                    let unigram = dictionary.find(&word)?.and_then(|node| node.frequency).unwrap_or(0);
                    entry.score += frequency.saturating_sub(unigram) as f32;
                }
            }
        }
        for (word, other) in search.found {
            match found.get_mut(&word) {
                Some(entry) => entry.merge(other),
                None => {
                    found.insert(word, other);
                }
            }
        }
    }
    let mut suggestions: Vec<Suggestion> = found
        .into_iter()
        .map(|(word, found)| Suggestion {
            word: match_case(typed, &word),
            score: found.score,
            exact: found.exact,
            completion: found.completion,
        })
        .collect();
    suggestions.sort_by(|a, b| b.score.total_cmp(&a.score).then_with(|| a.word.cmp(&b.word)));
    // Case variants of one word ("Haus", "haus") collapse to the best one.
    let mut seen = Vec::new();
    suggestions.retain(|s| {
        let key = s.word.to_lowercase();
        if seen.contains(&key) {
            false
        } else {
            seen.push(key);
            true
        }
    });
    suggestions.truncate(limit);
    Ok(suggestions)
}

/// What usually follows `previous`, best first, before anything is typed.
pub fn predict(lexicon: &Lexicon, previous: &str, limit: usize) -> Result<Vec<String>, Error> {
    let mut found: HashMap<String, u8> = HashMap::new();
    for dictionary in &lexicon.dictionaries {
        for (word, frequency) in next_words(dictionary, previous)? {
            let entry = found.entry(word).or_insert(0);
            *entry = (*entry).max(frequency);
        }
    }
    let mut words: Vec<(String, u8)> = found.into_iter().collect();
    words.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    Ok(words.into_iter().take(limit).map(|(word, _)| word).collect())
}

/// Next words for `previous` as written, or for its lower-case form when a
/// sentence start capitalised it.
fn next_words(dictionary: &Dictionary, previous: &str) -> Result<Vec<(String, u8)>, Error> {
    let node = match dictionary.find(previous)? {
        Some(node) => Some(node),
        None => dictionary.find(&previous.to_lowercase())?,
    };
    match node {
        Some(node) => dictionary.next_words(&node),
        None => Ok(Vec::new()),
    }
}

/// What the suggestion strip shows: three slots left to right, empty where
/// there is nothing, and how many characters before the cursor a choice
/// replaces.
#[derive(Clone, Debug, PartialEq)]
pub struct Strip {
    pub slots: [String; 3],
    pub replaces: usize,
    /// What autocorrect puts in place of the typed word when a space or
    /// punctuation follows it: the best candidate, when the typed word is
    /// not a word and the candidate corrects rather than completes it.
    pub correction: Option<String>,
    /// The word being typed, when it is not a word yet; what learning counts.
    pub unknown: Option<String>,
}

/// Letters and the marks inside words; what the word at the cursor is made of.
fn in_word(c: char) -> bool {
    c.is_alphanumeric() || matches!(c, '\'' | '’' | '-')
}

/// The strip for the text before the cursor. The best candidate takes the
/// middle as on other keyboards. While a word is being typed that no
/// dictionary spells that way, the word as typed stays on the left so it
/// can be kept; otherwise the left and right slots hold the runners-up.
/// After a word and a space, the slots offer what usually comes next.
pub fn strip(lexicon: &Lexicon, before: &str, substitution: Substitution) -> Result<Strip, Error> {
    let start = before.char_indices().rev().take_while(|&(_, c)| in_word(c)).last().map_or(before.len(), |(i, _)| i);
    let (rest, typed) = before.split_at(start);
    let previous = rest.trim_end().rsplit(|c: char| !in_word(c)).next().filter(|word| !word.is_empty());
    let mut slots = [String::new(), String::new(), String::new()];
    if typed.is_empty() {
        if rest.ends_with(' ')
            && let Some(previous) = previous
        {
            let words = predict(lexicon, previous, 3)?;
            for (slot, word) in [1, 0, 2].into_iter().zip(words) {
                slots[slot] = word;
            }
        }
        return Ok(Strip { slots, replaces: 0, correction: None, unknown: None });
    }
    let found = suggest(lexicon, typed, previous, substitution, 3)?;
    let known = spelled(lexicon, typed)?;
    let correction = found.first().filter(|best| !known && !best.completion).map(|best| best.word.clone());
    let mut words: Vec<String> = found.into_iter().map(|s| s.word).filter(|word| word != typed).collect();
    if known {
        words.insert(0, typed.to_owned());
        for (slot, word) in [1, 0, 2].into_iter().zip(words) {
            slots[slot] = word;
        }
    } else {
        slots[0] = typed.to_owned();
        for (slot, word) in [1, 2].into_iter().zip(words) {
            slots[slot] = word;
        }
    }
    let unknown = (!known).then(|| typed.to_owned());
    Ok(Strip { slots, replaces: typed.chars().count(), correction, unknown })
}

/// Whether a dictionary, or what was learned, spells `word` exactly so, or with a lower-case first
/// letter where the word was capitalised to start a sentence.
fn spelled(lexicon: &Lexicon, word: &str) -> Result<bool, Error> {
    let mut chars = word.chars();
    let lowered: String = chars.next().into_iter().flat_map(char::to_lowercase).chain(chars).collect();
    if lexicon.knows(word) || lexicon.knows(&lowered) {
        return Ok(true);
    }
    for dictionary in &lexicon.dictionaries {
        for form in [word, &lowered] {
            if dictionary.find(form)?.is_some_and(|node| !node.hidden) {
                return Ok(true);
            }
        }
    }
    Ok(false)
}

/// Whether any dictionary has `word`, as typed or with its first letter
/// lowered or raised.
pub fn is_word(lexicon: &Lexicon, word: &str) -> Result<bool, Error> {
    let lower = word.to_lowercase();
    let mut capital = String::new();
    let mut chars = lower.chars();
    if let Some(first) = chars.next() {
        capital.extend(first.to_uppercase());
        capital.push_str(chars.as_str());
    }
    if [word, &lower, &capital].iter().any(|form| lexicon.knows(form)) {
        return Ok(true);
    }
    for dictionary in &lexicon.dictionaries {
        for form in [word, &lower, &capital] {
            if dictionary.find(form)?.is_some_and(|node| !node.hidden) {
                return Ok(true);
            }
        }
    }
    Ok(false)
}

/// The dictionary word in the case the user typed: all capitals if they
/// typed more than one letter in capitals, a capital first letter if they
/// started with one, otherwise as the dictionary spells it.
fn match_case(typed: &str, word: &str) -> String {
    let letters: Vec<char> = typed.chars().filter(|c| c.is_alphabetic()).collect();
    if letters.len() > 1 && letters.iter().all(|c| c.is_uppercase()) {
        return word.to_uppercase();
    }
    if letters.first().is_some_and(|c| c.is_uppercase()) {
        let mut chars = word.chars();
        if let Some(first) = chars.next() {
            return first.to_uppercase().chain(chars).collect();
        }
    }
    word.to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dictionary::tests::encode;

    fn plain(a: char, b: char) -> f32 {
        if a == b { 0.0 } else { 1.0 }
    }

    fn words(lexicon: &Lexicon, typed: &str, previous: Option<&str>) -> Vec<String> {
        suggest(lexicon, typed, previous, &plain, 3).unwrap().into_iter().map(|s| s.word).collect()
    }

    fn german() -> Lexicon {
        let words = [
            ("Haus", 200),
            ("Hause", 180),
            ("hat", 220),
            ("über", 230),
            ("Straße", 180),
            ("die", 250),
            ("das", 250),
            ("Polizei", 190),
            ("Stadt", 200),
            ("hallo", 150),
        ];
        Lexicon::new(vec![Dictionary::new(encode(&words, &[("die", "Polizei", 15), ("die", "Stadt", 3)])).unwrap()])
    }

    #[test]
    fn case_and_umlauts_cost_nothing() {
        let german = german();
        assert_eq!(words(&german, "haus", None)[0], "Haus");
        assert_eq!(words(&german, "uber", None)[0], "über");
        assert_eq!(words(&german, "ueber", None)[0], "über");
        assert_eq!(words(&german, "strasse", None)[0], "Straße");
        assert_eq!(words(&german, "strase", None)[0], "Straße");
    }

    #[test]
    fn apostrophes_may_be_left_out() {
        let english = Lexicon::new(vec![Dictionary::new(encode(&[("don't", 200), ("done", 150)], &[])).unwrap()]);
        let found = suggest(&english, "dont", None, &plain, 3).unwrap();
        assert_eq!((found[0].word.as_str(), found[0].exact), ("don't", true));
    }

    #[test]
    fn slips_are_corrected() {
        let german = german();
        assert_eq!(words(&german, "hallp", None)[0], "hallo", "substitution");
        assert_eq!(words(&german, "hlalo", None)[0], "hallo", "swap");
        assert_eq!(words(&german, "halo", None)[0], "hallo", "missing letter");
    }

    #[test]
    fn completions_are_offered() {
        let german = german();
        assert!(words(&german, "Poli", None).contains(&"Polizei".to_owned()));
    }

    #[test]
    fn the_previous_word_ranks_what_follows_it() {
        let german = german();
        assert_eq!(predict(&german, "die", 2).unwrap(), ["Polizei", "Stadt"]);
        assert_eq!(predict(&german, "Die", 2).unwrap(), ["Polizei", "Stadt"], "sentence start");
    }

    #[test]
    fn typed_capitals_carry_over() {
        let german = german();
        assert_eq!(words(&german, "Uber", None)[0], "Über");
        assert_eq!(words(&german, "HAUS", None)[0], "HAUS");
    }

    #[test]
    fn strip_keeps_an_unknown_word_on_the_left() {
        let german = german();
        let strip = strip(&german, "Wir gehen nach haus", &plain).unwrap();
        assert_eq!(strip.slots[1], "Haus");
        assert_eq!(strip.replaces, 4);
        let strip = super::strip(&german, "ich sag hallp", &plain).unwrap();
        assert_eq!(strip.slots[0], "hallp");
        assert_eq!(strip.slots[1], "hallo");
        assert_eq!(strip.correction.as_deref(), Some("hallo"));
    }

    #[test]
    fn autocorrect_leaves_words_and_unfinished_words_alone() {
        let german = german();
        assert_eq!(strip(&german, "das Haus", &plain).unwrap().correction, None, "a word");
        assert_eq!(strip(&german, "die Poli", &plain).unwrap().correction, None, "only completions");
    }

    #[test]
    fn strip_predicts_after_a_space() {
        let german = german();
        let strip = strip(&german, "Heute die ", &plain).unwrap();
        assert_eq!(strip.slots, ["Stadt".to_owned(), "Polizei".to_owned(), String::new()]);
        assert_eq!(strip.replaces, 0);
        assert_eq!(strip.correction, None);
    }

    #[test]
    fn a_word_kept_twice_is_learned() {
        let mut german = german();
        assert_eq!(strip(&german, "beim Blorb", &plain).unwrap().unknown.as_deref(), Some("Blorb"));
        german.learn("Blorb", 1);
        assert!(!is_word(&german, "Blorb").unwrap(), "once is not enough");
        german.learn("Blorb", 1);
        assert!(is_word(&german, "Blorb").unwrap());
        let strip = strip(&german, "beim Blorb", &plain).unwrap();
        assert_eq!((strip.unknown, strip.correction), (None, None));
        assert_eq!(words(&german, "blirb", None)[0], "Blorb", "and suggested");
        german.forget();
        assert!(!is_word(&german, "Blorb").unwrap());
    }

    #[test]
    fn knows_its_words() {
        let german = german();
        assert!(is_word(&german, "haus").unwrap());
        assert!(is_word(&german, "Hallo").unwrap());
        assert!(!is_word(&german, "hauss").unwrap());
    }
}
