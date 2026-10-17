//! Swipe typing: which words a path drawn across the letters spells.
//!
//! Every dictionary word has an ideal path, the straight lines through the
//! centres of its letters' keys. The drawn path is compared with the ideal
//! path of every word that starts near where the finger went down and ends
//! near where it lifted: both are resampled to the same number of points
//! spaced evenly along their length, and the mean distance between
//! corresponding points says how well they agree. A word whose path agrees
//! still has to pass close to each of its letters, which keeps a straight
//! stroke from spelling a word with a detour in it. Candidates are ranked by
//! word frequency against those distances, with the previous word's
//! next-word list raising the words that usually follow it.
//!
//! Only key centres enter the comparison, so the decoder works on any
//! arrangement of keys, hexagonal or not.

use crate::Point;
use crate::dictionary::{Dictionary, Error, Node};
use crate::suggest::{Lexicon, next_words};
use std::collections::HashMap;
use unicode_normalization::UnicodeNormalization;
use unicode_normalization::char::is_combining_mark;

/// Points both paths are resampled to.
const SAMPLES: usize = 32;
/// Points the drawn path is resampled to when checking that it passes each letter.
const PASS_SAMPLES: usize = 128;
/// How candidates are found and weighed. Distances are in key widths;
/// weights are in frequency points, which run 0 to 255 on a logarithmic
/// scale.
#[derive(Clone, Copy, Debug)]
pub struct Tuning {
    /// How far from the start or end of the path a word's first or last
    /// letter may be.
    pub end_radius: f32,
    /// Mean distance beyond which a word is not considered.
    pub max_distance: f32,
    /// What a squared key width of mean distance costs.
    pub distance_weight: f32,
    /// How close the path may pass a letter without cost.
    pub pass_free: f32,
    /// What a squared key width of missing a letter costs.
    pub pass_weight: f32,
    /// What a squared key width between the path's ends and the word's
    /// first and last letter costs.
    pub end_weight: f32,
    /// What a word in capitals costs: abbreviations are rarely meant.
    pub capitals: f32,
    /// Lets points of the drawn path pair with ideal points up to this many
    /// samples earlier or later (dynamic time warping), so a finger that
    /// slows down or overshoots is not counted off; 0 pairs them in order.
    pub warp: usize,
}

/// Weights fitted to real swipes: those in FUTO's swipe dataset
/// (huggingface.co/datasets/futo-org/swipe.futo.org), measured with
/// `examples/futo.rs`. On swipes left out of the fitting, 87.5% of
/// dictionary words come first and 96% among the first three.
impl Default for Tuning {
    fn default() -> Self {
        Self {
            end_radius: 1.5,
            max_distance: 1.2,
            distance_weight: 200.0,
            pass_free: 0.5,
            pass_weight: 50.0,
            end_weight: 30.0,
            capitals: 30.0,
            warp: 3,
        }
    }
}

/// The letter keys of a layout: where each letter's key centre is.
#[derive(Clone, Debug)]
pub struct Keys {
    pub letters: Vec<(char, Point)>,
    /// Distance between neighbouring key centres in a row.
    pub width: f32,
}

impl Keys {
    fn centre(&self, c: char) -> Option<Point> {
        self.letters.iter().find(|(letter, _)| *letter == c).map(|(_, centre)| *centre)
    }

    /// Letters whose key centre is within `radius` key widths of `at`.
    fn near(&self, at: Point, radius: f32) -> Vec<char> {
        self.letters.iter().filter(|(_, centre)| distance(*centre, at) < radius * self.width).map(|(c, _)| *c).collect()
    }
}

/// One dictionary word as the swipe decoder sees it.
#[derive(Clone, Debug)]
struct Entry {
    word: String,
    /// The keys the word's path runs through: folded letters with repeats
    /// merged, since a doubled letter is one point on the path.
    keys: Vec<char>,
    frequency: u8,
}

/// Every word of the dictionaries, grouped by first and last key.
#[derive(Debug, Default)]
pub struct Index {
    entries: Vec<Entry>,
    by_ends: HashMap<(char, char), Vec<usize>>,
}

impl Index {
    pub fn new(dictionaries: &[Dictionary]) -> Result<Self, Error> {
        let mut index = Index::default();
        for dictionary in dictionaries {
            collect(dictionary, dictionary.root()?, &mut String::new(), &mut index)?;
        }
        Ok(index)
    }

    fn add(&mut self, word: &str, frequency: u8) {
        let Some(keys) = path_keys(word) else { return };
        let ends = (keys[0], keys[keys.len() - 1]);
        self.by_ends.entry(ends).or_default().push(self.entries.len());
        self.entries.push(Entry { word: word.to_owned(), keys, frequency });
    }
}

fn collect(dictionary: &Dictionary, nodes: Vec<Node>, word: &mut String, index: &mut Index) -> Result<(), Error> {
    for node in nodes {
        let length = word.len();
        word.extend(&node.chars);
        if let Some(frequency) = node.frequency
            && !node.hidden
        {
            index.add(word, frequency);
        }
        if let Some(children) = node.children {
            collect(dictionary, dictionary.array(children)?, word, index)?;
        }
        word.truncate(length);
    }
    Ok(())
}

/// The keys a word's path runs through, or None when it has a character no
/// key types or fewer than two keys, which no swipe can spell. Case and
/// accents fold away and ß is an s, as the keyboard types them; apostrophes
/// and hyphens are left out.
fn path_keys(word: &str) -> Option<Vec<char>> {
    let mut keys: Vec<char> = Vec::new();
    for c in word.nfd().filter(|&c| !is_combining_mark(c)) {
        if matches!(c, '\'' | '’' | '-') {
            continue;
        }
        if !c.is_alphabetic() {
            return None;
        }
        let c = if c == 'ß' { 's' } else { c.to_lowercase().next()? };
        if keys.last() != Some(&c) {
            keys.push(c);
        }
    }
    (keys.len() >= 2).then_some(keys)
}

fn distance(a: Point, b: Point) -> f32 {
    (a.x - b.x).hypot(a.y - b.y)
}

/// `n` points spaced evenly along the polyline through `points`.
fn resample(points: &[Point], n: usize) -> Vec<Point> {
    let total: f32 = points.windows(2).map(|pair| distance(pair[0], pair[1])).sum();
    if total == 0.0 {
        return vec![points[0]; n];
    }
    let step = total / (n - 1) as f32;
    let mut result = Vec::with_capacity(n);
    result.push(points[0]);
    let mut segment = 0;
    let mut walked = 0.0;
    for i in 1..n - 1 {
        let target = i as f32 * step;
        while segment < points.len() - 2 && walked + distance(points[segment], points[segment + 1]) < target {
            walked += distance(points[segment], points[segment + 1]);
            segment += 1;
        }
        let (a, b) = (points[segment], points[segment + 1]);
        let length = distance(a, b);
        let t = if length == 0.0 { 0.0 } else { ((target - walked) / length).clamp(0.0, 1.0) };
        result.push(Point { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });
    }
    result.push(points[points.len() - 1]);
    result
}

/// The mean distance between two paths of equal length with each point
/// paired to one at most `band` samples away, the pairing chosen to make
/// the total least, both ends paired with each other.
fn warped(a: &[Point], b: &[Point], band: usize) -> f32 {
    let n = a.len();
    // Two rows of (total distance, pairs) suffice: each cell looks back one row.
    let mut previous = vec![(f32::INFINITY, 0u32); n];
    let mut row = vec![(f32::INFINITY, 0u32); n];
    for i in 0..n {
        row.fill((f32::INFINITY, 0));
        for j in i.saturating_sub(band)..(i + band + 1).min(n) {
            let (before, count) = if i == 0 && j == 0 {
                (0.0, 0)
            } else {
                let mut best = (f32::INFINITY, 0);
                for candidate in [
                    (i > 0 && j > 0).then(|| previous[j - 1]),
                    (i > 0).then(|| previous[j]),
                    (j > 0).then(|| row[j - 1]),
                ]
                .into_iter()
                .flatten()
                {
                    if candidate.0 < best.0 {
                        best = candidate;
                    }
                }
                best
            };
            row[j] = (before + distance(a[i], b[j]), count + 1);
        }
        std::mem::swap(&mut previous, &mut row);
    }
    previous[n - 1].0 / previous[n - 1].1 as f32
}

/// The words `path` spells best first, at most `limit`, after the word
/// `previous`.
pub fn decode(
    lexicon: &Lexicon,
    keys: &Keys,
    path: &[Point],
    previous: Option<&str>,
    limit: usize,
) -> Result<Vec<String>, Error> {
    decode_tuned(lexicon, keys, path, previous, limit, &Tuning::default())
}

/// [`decode`] with other weights, for tuning them.
pub fn decode_tuned(
    lexicon: &Lexicon,
    keys: &Keys,
    path: &[Point],
    previous: Option<&str>,
    limit: usize,
    tuning: &Tuning,
) -> Result<Vec<String>, Error> {
    if path.len() < 2 {
        return Ok(Vec::new());
    }
    let index = lexicon.swipe_index()?;
    let drawn = resample(path, SAMPLES);
    let dense = resample(path, PASS_SAMPLES);
    let width = keys.width;
    let (first, last) = (path[0], path[path.len() - 1]);
    let starts = keys.near(first, tuning.end_radius);
    let ends = keys.near(last, tuning.end_radius);

    // The score of a word whose frequency less end cost is `bound`, or None
    // when it cannot beat `floor`.
    let score = |letters: &[char], bound: f32, floor: f32| -> Option<f32> {
        let centres: Vec<Point> = letters.iter().map(|&c| keys.centre(c)).collect::<Option<_>>()?;
        let ideal = resample(&centres, SAMPLES);
        let mean = if tuning.warp == 0 {
            drawn.iter().zip(&ideal).map(|(a, b)| distance(*a, *b)).sum::<f32>() / SAMPLES as f32 / width
        } else {
            warped(&drawn, &ideal, tuning.warp) / width
        };
        let score = bound - tuning.distance_weight * mean * mean;
        if mean > tuning.max_distance || score <= floor {
            return None;
        }
        let missed: f32 = centres[1..centres.len() - 1]
            .iter()
            .map(|&centre| {
                let closest = dense.iter().map(|p| distance(*p, centre)).fold(f32::MAX, f32::min) / width;
                (closest - tuning.pass_free).max(0.0).powi(2)
            })
            .sum();
        Some(score - tuning.pass_weight * missed)
    };

    // How much more often a word follows `previous` than it occurs at all.
    let mut boost: HashMap<String, f32> = HashMap::new();
    if let Some(previous) = previous {
        for dictionary in &lexicon.dictionaries {
            for (word, frequency) in next_words(dictionary, previous)? {
                let unigram = dictionary.find(&word)?.and_then(|node| node.frequency).unwrap_or(0);
                let gain = frequency.saturating_sub(unigram) as f32;
                let entry = boost.entry(word).or_insert(0.0);
                *entry = entry.max(gain);
            }
        }
    }

    // Every candidate with the most it can score: its frequency less what
    // its ends cost. Scoring them best bound first, the search stops once no
    // bound left can beat the words already found.
    let end_cost = |letters: &[char]| -> Option<f32> {
        let (a, b) = (keys.centre(letters[0])?, keys.centre(letters[letters.len() - 1])?);
        Some(tuning.end_weight * ((distance(first, a) / width).powi(2) + (distance(last, b) / width).powi(2)))
    };
    let mut candidates: Vec<(&str, std::borrow::Cow<[char]>, f32)> = Vec::new();
    for &start in &starts {
        for &end in &ends {
            for &i in index.by_ends.get(&(start, end)).into_iter().flatten() {
                let entry = &index.entries[i];
                let capitals = entry.word.chars().filter(|c| c.is_alphabetic()).all(char::is_uppercase);
                let prior = entry.frequency as f32 - if capitals { tuning.capitals } else { 0.0 }
                    + boost.get(&entry.word).copied().unwrap_or(0.0);
                if let Some(cost) = end_cost(&entry.keys) {
                    candidates.push((&entry.word, (&entry.keys[..]).into(), prior - cost));
                }
            }
        }
    }
    for (word, frequency) in lexicon.learned_words() {
        if let Some(letters) = path_keys(word)
            && starts.contains(&letters[0])
            && ends.contains(&letters[letters.len() - 1])
            && let Some(cost) = end_cost(&letters)
        {
            candidates.push((word, letters.into(), frequency - cost));
        }
    }
    candidates.sort_by(|a, b| b.2.total_cmp(&a.2).then_with(|| a.0.cmp(b.0)));

    // The best `limit` words so far, a word in several dictionaries or in
    // two cases only once.
    let mut best: Vec<(String, &str, f32)> = Vec::new();
    for (word, letters, bound) in candidates {
        if best.len() == limit && bound <= best[limit - 1].2 {
            break;
        }
        let floor = if best.len() == limit { best[limit - 1].2 } else { f32::NEG_INFINITY };
        let Some(score) = score(&letters, bound, floor) else { continue };
        let key = word.to_lowercase();
        match best.iter().position(|(other, _, _)| *other == key) {
            Some(i) if best[i].2 >= score => continue,
            Some(i) => {
                best.remove(i);
            }
            None => {}
        }
        let at = best.partition_point(|(_, _, other)| *other >= score);
        if at < limit {
            best.insert(at, (key, word, score));
            best.truncate(limit);
        }
    }
    let words = best.into_iter().map(|(_, word, _)| word.to_owned()).collect();
    Ok(words)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dictionary::tests::encode;
    use crate::{Keyboard, honeycomb};

    fn lexicon(words: &[(&str, u8)]) -> Lexicon {
        let mut lexicon = Lexicon::default();
        lexicon.add(Dictionary::new(encode(words, &[])).unwrap());
        lexicon
    }

    /// The path a careful finger draws: through each letter's centre, with
    /// points every few pixels and a little wobble.
    fn trace(keys: &Keys, word: &str, wobble: f32) -> Vec<Point> {
        let centres: Vec<Point> = word.chars().map(|c| keys.centre(c).unwrap()).collect();
        let mut path = Vec::new();
        for pair in centres.windows(2) {
            for step in 0..10 {
                let t = step as f32 / 10.0;
                let side = if step % 2 == 0 { wobble } else { -wobble } * keys.width;
                path.push(Point {
                    x: pair[0].x + (pair[1].x - pair[0].x) * t + side,
                    y: pair[0].y + (pair[1].y - pair[0].y) * t,
                });
            }
        }
        path.push(*centres.last().unwrap());
        path
    }

    #[test]
    fn resampling_keeps_the_ends_and_spaces_evenly() {
        let points = [Point { x: 0.0, y: 0.0 }, Point { x: 10.0, y: 0.0 }, Point { x: 10.0, y: 10.0 }];
        let resampled = resample(&points, 5);
        assert_eq!(resampled.len(), 5);
        assert_eq!(resampled[0], points[0]);
        assert_eq!(resampled[4], points[2]);
        assert!((resampled[2].x - 10.0).abs() < 1e-4 && resampled[2].y.abs() < 1e-4);
    }

    #[test]
    fn keys_fold_case_accents_and_doubles() {
        assert_eq!(path_keys("Hello"), Some(vec!['h', 'e', 'l', 'o']));
        assert_eq!(path_keys("Straße"), Some(vec!['s', 't', 'r', 'a', 's', 'e']));
        assert_eq!(path_keys("don't"), Some(vec!['d', 'o', 'n', 't']));
        assert_eq!(path_keys("über"), Some(vec!['u', 'b', 'e', 'r']));
        assert_eq!(path_keys("a"), None);
        assert_eq!(path_keys("4you"), None);
    }

    #[test]
    fn a_traced_word_decodes_to_itself_over_its_neighbours() {
        let keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let keys = keyboard.swipe_keys();
        let lexicon = lexicon(&[("hello", 150), ("hallo", 150), ("help", 150), ("hell", 120), ("jello", 90)]);
        for word in ["hello", "hallo", "help"] {
            let path = trace(&keys, word, 0.1);
            assert_eq!(decode(&lexicon, &keys, &path, None, 3).unwrap()[0], word);
        }
    }

    #[test]
    fn umlauts_and_sharp_s_come_from_their_base_letters() {
        let keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let keys = keyboard.swipe_keys();
        let lexicon = lexicon(&[("über", 150), ("Straße", 150), ("uber", 60)]);
        assert_eq!(decode(&lexicon, &keys, &trace(&keys, "uber", 0.05), None, 3).unwrap()[0], "über");
        assert_eq!(decode(&lexicon, &keys, &trace(&keys, "strase", 0.05), None, 3).unwrap()[0], "Straße");
    }

    #[test]
    fn learned_words_are_swiped_too() {
        let keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let keys = keyboard.swipe_keys();
        let mut lexicon = lexicon(&[("glory", 100)]);
        lexicon.learn("glorb", 2);
        assert_eq!(decode(&lexicon, &keys, &trace(&keys, "glorb", 0.05), None, 3).unwrap()[0], "glorb");
    }
}
