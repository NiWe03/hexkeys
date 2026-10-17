//! Telex, the way Vietnamese is typed on letter keys: a vowel typed twice
//! takes a hat (aa â, ee ê, oo ô), w hooks or curves one (aw ă, ow ơ, uw ư,
//! uow ươ), dd is đ, and s f r x j after a syllable give it a tone (sắc,
//! huyền, hỏi, ngã, nặng) and z takes it off. The tone sits on the vowel the
//! spelling puts it on, and moves there as the syllable grows. A mark typed
//! a second time is undone and the key typed as a letter ("ass" is "as"),
//! and a word that is no Vietnamese syllable is left as typed.

/// The vowels with each tone: none, sắc, huyền, hỏi, ngã, nặng.
const VOWELS: [[char; 6]; 12] = [
    ['a', 'á', 'à', 'ả', 'ã', 'ạ'],
    ['ă', 'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ'],
    ['â', 'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ'],
    ['e', 'é', 'è', 'ẻ', 'ẽ', 'ẹ'],
    ['ê', 'ế', 'ề', 'ể', 'ễ', 'ệ'],
    ['i', 'í', 'ì', 'ỉ', 'ĩ', 'ị'],
    ['o', 'ó', 'ò', 'ỏ', 'õ', 'ọ'],
    ['ô', 'ố', 'ồ', 'ổ', 'ỗ', 'ộ'],
    ['ơ', 'ớ', 'ờ', 'ở', 'ỡ', 'ợ'],
    ['u', 'ú', 'ù', 'ủ', 'ũ', 'ụ'],
    ['ư', 'ứ', 'ừ', 'ử', 'ữ', 'ự'],
    ['y', 'ý', 'ỳ', 'ỷ', 'ỹ', 'ỵ'],
];

/// The keys that set a tone, in the order of [`VOWELS`]' columns from 1.
const TONES: [char; 5] = ['s', 'f', 'r', 'x', 'j'];

/// The consonants a syllable may start with and end with.
const INITIALS: [&str; 27] = [
    "", "b", "c", "ch", "d", "đ", "g", "gh", "gi", "h", "k", "kh", "l", "m", "n", "ng", "ngh", "nh", "p", "ph", "qu", "r",
    "s", "t", "th", "tr", "v",
];
const FINALS: [&str; 9] = ["", "c", "ch", "m", "n", "ng", "nh", "p", "t"];

/// A vowel as its base letter and tone.
fn split(c: char) -> Option<(char, usize)> {
    VOWELS.iter().find_map(|row| row.iter().position(|&v| v == c).map(|tone| (row[0], tone)))
}

fn with_tone(base: char, tone: usize) -> char {
    VOWELS.iter().find(|row| row[0] == base).map_or(base, |row| row[tone])
}

/// A lower-case syllable as initial consonants, vowels (base letters) with
/// the tone found on them, and final consonants; None when it is not one.
fn parse(word: &[char]) -> Option<(usize, Vec<char>, usize, usize)> {
    let mut start = word.iter().position(|&c| split(c).is_some()).unwrap_or(word.len());
    let initial: String = word[..start].iter().collect();
    // In "qu" and in "gi" before another vowel the vowel belongs to the consonant.
    if (initial == "q" && word.get(start).and_then(|&c| split(c)).is_some_and(|(b, _)| b == 'u'))
        || (initial == "g"
            && word.get(start).and_then(|&c| split(c)).is_some_and(|(b, _)| b == 'i')
            && word.get(start + 1).is_some_and(|&c| split(c).is_some()))
    {
        start += 1;
    }
    let initial: String = word[..start].iter().map(|&c| split(c).map_or(c, |(b, _)| b)).collect();
    if !INITIALS.contains(&initial.as_str()) {
        return None;
    }
    let end = start + word[start..].iter().take_while(|&&c| split(c).is_some()).count();
    let final_: String = word[end..].iter().collect();
    if !FINALS.contains(&final_.as_str()) || end - start > 3 {
        return None;
    }
    let mut tone = 0;
    let mut vowels = Vec::new();
    for &c in &word[start..end] {
        let (base, t) = split(c)?;
        if t != 0 {
            tone = t;
        }
        vowels.push(base);
    }
    Some((start, vowels, end - start, tone))
}

/// Which of a syllable's vowels takes the tone: one with a mark (ơ in ươ),
/// else the last before a final consonant, else in oa oe uy the second, in
/// two others the first and in three the middle.
fn tone_place(vowels: &[char], has_final: bool) -> usize {
    let marked: Vec<usize> = (0..vowels.len()).filter(|&i| "ăâêôơư".contains(vowels[i])).collect();
    if let Some(&last) = marked.last() {
        return last;
    }
    if vowels.is_empty() {
        return 0;
    }
    let plain: String = vowels.iter().collect();
    match vowels.len() {
        1 => 0,
        _ if has_final => vowels.len() - 1,
        2 if matches!(plain.as_str(), "oa" | "oe" | "uy") => 1,
        2 => 0,
        _ => 1,
    }
}

/// The syllable written out with `tone` on the vowel the spelling puts it on.
fn write(word: &[char], start: usize, vowels: &[char], count: usize, tone: usize) -> Vec<char> {
    let has_final = start + count < word.len();
    let place = tone_place(vowels, has_final);
    let mut out: Vec<char> = word[..start].to_vec();
    for (i, &base) in vowels.iter().enumerate() {
        out.push(if i == place { with_tone(base, tone) } else { base });
    }
    out.extend_from_slice(&word[start + count..]);
    out
}

/// The word typed so far with `key` typed after it, when Telex makes more of
/// the key than a letter; None when it is just appended.
pub fn telex(word: &str, key: char) -> Option<String> {
    let caps: Vec<bool> = word.chars().map(char::is_uppercase).collect();
    let lower: Vec<char> = word.chars().flat_map(char::to_lowercase).collect();
    if lower.len() != caps.len() || lower.is_empty() {
        return None;
    }
    let k = key.to_lowercase().next()?;
    let (start, mut vowels, count, tone) = parse(&lower)?;
    let mut out = None;
    if let Some(index) = TONES.iter().position(|&t| t == k).map(|i| i + 1).filter(|_| !vowels.is_empty()) {
        out = Some(if tone == index {
            // The same tone again takes it off, and the key is a letter after all.
            let mut plain = write(&lower, start, &vowels, count, 0);
            plain.push(key);
            return Some(recase(&plain, &caps, plain.len() - 1));
        } else {
            write(&lower, start, &vowels, count, index)
        });
    } else if k == 'z' && tone != 0 {
        out = Some(write(&lower, start, &vowels, count, 0));
    } else if k == 'd' && lower.first() == Some(&'d') && start >= 1 {
        let mut word = lower.clone();
        word[0] = 'đ';
        out = Some(word);
    } else if let Some((plain, marked)) = [('a', 'â'), ('e', 'ê'), ('o', 'ô')].into_iter().find(|&(p, _)| p == k) {
        if let Some(i) = vowels.iter().rposition(|&v| v == plain || v == 'ă' && plain == 'a' || v == 'ơ' && plain == 'o') {
            vowels[i] = marked;
            out = Some(write(&lower, start, &vowels, count, tone));
        } else if let Some(i) = vowels.iter().rposition(|&v| v == marked) {
            // A hat typed again goes, and the letter is typed: "aaa" is "aa".
            vowels[i] = plain;
            let mut word = write(&lower, start, &vowels, count, tone);
            word.push(key);
            return Some(recase(&word, &caps, word.len() - 1));
        }
    } else if k == 'w' {
        let text: String = vowels.iter().collect();
        if let Some(i) = text.find("uo").map(|byte| text[..byte].chars().count()) {
            vowels[i] = 'ư';
            vowels[i + 1] = 'ơ';
        } else if let Some(i) = vowels.iter().rposition(|&v| matches!(v, 'a' | 'o' | 'u' | 'â' | 'ô')) {
            vowels[i] = match vowels[i] {
                'a' | 'â' => 'ă',
                'o' | 'ô' => 'ơ',
                _ => 'ư',
            };
        } else if let Some(i) = vowels.iter().rposition(|&v| matches!(v, 'ă' | 'ơ' | 'ư')) {
            vowels[i] = match vowels[i] {
                'ă' => 'a',
                'ơ' => 'o',
                _ => 'u',
            };
            let mut word = write(&lower, start, &vowels, count, tone);
            word.push(key);
            return Some(recase(&word, &caps, word.len() - 1));
        } else {
            return None;
        }
        out = Some(write(&lower, start, &vowels, count, tone));
    }
    out.map(|word| recase(&word, &caps, word.len()))
}

/// Puts back the capitals the typed letters had; letters from `from` on keep their case.
fn recase(word: &[char], caps: &[bool], from: usize) -> String {
    word.iter()
        .enumerate()
        .flat_map(|(i, &c)| {
            let upper = i < from && caps.get(i).copied().unwrap_or(false);
            if upper { c.to_uppercase().collect::<Vec<_>>() } else { vec![c] }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::telex;

    /// Types `keys` a key at a time as the keyboard would, each word from empty.
    fn typed(keys: &str) -> String {
        let words: Vec<String> = keys
            .split(' ')
            .map(|keys| {
                let mut word = String::new();
                for key in keys.chars() {
                    word = telex(&word, key).unwrap_or_else(|| format!("{word}{key}"));
                }
                word
            })
            .collect();
        words.join(" ")
    }

    #[test]
    fn vowels_take_hats_hooks_and_curves() {
        assert_eq!(typed("aa"), "â");
        assert_eq!(typed("tieengs"), "tiếng");
        assert_eq!(typed("aw"), "ă");
        assert_eq!(typed("nguowif"), "người");
        assert_eq!(typed("ddi"), "đi");
        assert_eq!(typed("tuw"), "tư");
    }

    #[test]
    fn tones_sit_where_the_spelling_puts_them() {
        assert_eq!(typed("vieetj"), "việt");
        assert_eq!(typed("camr own"), "cảm ơn");
        assert_eq!(typed("hoaf"), "hoà");
        assert_eq!(typed("muaf"), "mùa");
        assert_eq!(typed("quas"), "quá");
        assert_eq!(typed("gias"), "giá");
        assert_eq!(typed("Nams"), "Nám");
        assert_eq!(typed("toans"), "toán");
        assert_eq!(typed("tuyeetj"), "tuyệt");
    }

    #[test]
    fn the_tone_moves_as_the_syllable_grows() {
        // The tone typed before the hat ends up on the hatted vowel.
        assert_eq!(typed("viejet"), "việt");
        assert_eq!(typed("hoasn"), "hoán");
    }

    #[test]
    fn a_mark_typed_again_is_undone() {
        assert_eq!(typed("ass"), "as");
        assert_eq!(typed("aaa"), "aa");
        assert_eq!(typed("asz"), "a");
    }

    #[test]
    fn words_that_are_no_syllable_stay_as_typed() {
        assert_eq!(typed("street"), "street");
        assert_eq!(typed("xs"), "xs");
    }
}
