//! Reader for AOSP binary dictionaries (format version 202), the `.dict`
//! files the AOSP keyboard and its descendants (HeliBoard, OpenBoard) use.
//!
//! A file is a header of key/value attributes followed by a Patricia trie.
//! The trie is a tree of node arrays; each node carries one or more
//! characters, a frequency if a word ends there, a relative address of its
//! children, and for words with next-word data a list of relative addresses
//! of the nodes where those next words end. Frequencies are 0 to 255 on a
//! logarithmic scale.

use std::collections::HashMap;

const MAGIC: u32 = 0x9BC1_3AFE;
const VERSION: u16 = 202;

const MASK_CHILDREN_ADDRESS: u8 = 0xC0;
const FLAG_MULTIPLE_CHARS: u8 = 0x20;
const FLAG_TERMINAL: u8 = 0x10;
const FLAG_SHORTCUTS: u8 = 0x08;
const FLAG_BIGRAMS: u8 = 0x04;
const FLAG_NOT_A_WORD: u8 = 0x02;
const FLAG_OFFENSIVE: u8 = 0x01;

const ATTRIBUTE_HAS_NEXT: u8 = 0x80;
const ATTRIBUTE_NEGATIVE: u8 = 0x40;
const MASK_ATTRIBUTE_ADDRESS: u8 = 0x30;
const MASK_ATTRIBUTE_FREQUENCY: u8 = 0x0F;

const CHARACTERS_TERMINATOR: u8 = 0x1F;

#[derive(Debug, PartialEq)]
pub enum Error {
    NotADictionary,
    UnsupportedVersion(u16),
    Truncated,
}

/// One node of the trie as stored.
#[derive(Clone, Debug)]
pub struct Node {
    pub address: usize,
    pub chars: Vec<char>,
    /// Set when a word ends at this node.
    pub frequency: Option<u8>,
    /// A word that should not be offered: not a word, or possibly offensive.
    pub hidden: bool,
    pub children: Option<usize>,
    /// Where the next-word list starts, if the node has one.
    bigrams: Option<usize>,
    /// The address just past this node, where its sibling starts.
    end: usize,
}

/// A loaded dictionary file.
pub struct Dictionary {
    bytes: Vec<u8>,
    pub attributes: HashMap<String, String>,
    /// One-byte codes from 0x20 on stand for these characters instead of
    /// Latin-1, when the file has a code point table.
    code_points: Vec<char>,
    root: usize,
}

impl Dictionary {
    pub fn new(bytes: Vec<u8>) -> Result<Self, Error> {
        if bytes.len() < 12 {
            return Err(Error::NotADictionary);
        }
        if u32::from_be_bytes(bytes[0..4].try_into().unwrap()) != MAGIC {
            return Err(Error::NotADictionary);
        }
        let version = u16::from_be_bytes([bytes[4], bytes[5]]);
        if version != VERSION {
            return Err(Error::UnsupportedVersion(version));
        }
        let header_size = u32::from_be_bytes(bytes[8..12].try_into().unwrap()) as usize;
        if header_size > bytes.len() {
            return Err(Error::Truncated);
        }
        let mut dictionary = Self { bytes, attributes: HashMap::new(), code_points: Vec::new(), root: header_size };
        // Header strings always use plain Latin-1 one-byte characters.
        let mut at = 12;
        while at < header_size {
            let (key, next) = dictionary.read_string(at, false)?;
            let (value, next) = dictionary.read_string(next, false)?;
            at = next;
            dictionary.attributes.insert(key, value);
        }
        if let Some(table) = dictionary.attributes.get("codePointTable") {
            dictionary.code_points = table.chars().collect();
        }
        Ok(dictionary)
    }

    fn byte(&self, at: usize) -> Result<u8, Error> {
        self.bytes.get(at).copied().ok_or(Error::Truncated)
    }

    fn unsigned(&self, at: usize, size: usize) -> Result<usize, Error> {
        (0..size).try_fold(0, |value, i| Ok((value << 8) | self.byte(at + i)? as usize))
    }

    /// One character and the address after it. A byte below 0x20 starts a
    /// three-byte code point; 0x1F alone is the terminator, returned as None.
    fn read_char(&self, at: usize, table: bool) -> Result<(Option<char>, usize), Error> {
        let first = self.byte(at)?;
        if first == CHARACTERS_TERMINATOR {
            return Ok((None, at + 1));
        }
        if first < 0x20 {
            let code = self.unsigned(at, 3)? as u32;
            return Ok((Some(char::from_u32(code).unwrap_or('\u{FFFD}')), at + 3));
        }
        let c = match self.code_points.get((first - 0x20) as usize) {
            Some(&c) if table => c,
            _ => first as char,
        };
        Ok((Some(c), at + 1))
    }

    fn read_string(&self, mut at: usize, table: bool) -> Result<(String, usize), Error> {
        let mut text = String::new();
        loop {
            let (c, next) = self.read_char(at, table)?;
            at = next;
            match c {
                Some(c) => text.push(c),
                None => return Ok((text, at)),
            }
        }
    }

    /// The nodes of the array at `address`.
    pub fn array(&self, address: usize) -> Result<Vec<Node>, Error> {
        let first = self.byte(address)?;
        let (count, mut at) = if first & 0x80 != 0 {
            ((((first & 0x7F) as usize) << 8) | self.byte(address + 1)? as usize, address + 2)
        } else {
            (first as usize, address + 1)
        };
        let mut nodes = Vec::with_capacity(count);
        for _ in 0..count {
            let node = self.node(at)?;
            at = node.end;
            nodes.push(node);
        }
        Ok(nodes)
    }

    pub fn root(&self) -> Result<Vec<Node>, Error> {
        self.array(self.root)
    }

    fn node(&self, address: usize) -> Result<Node, Error> {
        let flags = self.byte(address)?;
        let mut at = address + 1;
        let mut chars = Vec::new();
        if flags & FLAG_MULTIPLE_CHARS != 0 {
            loop {
                let (c, next) = self.read_char(at, true)?;
                at = next;
                match c {
                    Some(c) => chars.push(c),
                    None => break,
                }
            }
        } else {
            let (c, next) = self.read_char(at, true)?;
            chars.push(c.ok_or(Error::Truncated)?);
            at = next;
        }
        let frequency = if flags & FLAG_TERMINAL != 0 {
            at += 1;
            Some(self.byte(at - 1)?)
        } else {
            None
        };
        let size = match flags & MASK_CHILDREN_ADDRESS {
            0x40 => 1,
            0x80 => 2,
            0xC0 => 3,
            _ => 0,
        };
        let children = if size > 0 { Some(at + self.unsigned(at, size)?) } else { None };
        at += size;
        if flags & FLAG_TERMINAL != 0 && flags & FLAG_SHORTCUTS != 0 {
            // The list's size field counts itself.
            at += self.unsigned(at, 2)?;
        }
        let bigrams = if flags & FLAG_TERMINAL != 0 && flags & FLAG_BIGRAMS != 0 {
            let start = at;
            loop {
                let attribute = self.byte(at)?;
                at += 1 + attribute_size(attribute);
                if attribute & ATTRIBUTE_HAS_NEXT == 0 {
                    break;
                }
            }
            Some(start)
        } else {
            None
        };
        Ok(Node {
            address,
            chars,
            frequency,
            hidden: flags & (FLAG_NOT_A_WORD | FLAG_OFFENSIVE) != 0,
            children,
            bigrams,
            end: at,
        })
    }

    /// The node where `word` ends exactly, matching case and accents.
    /// Calls `visit` with every word that may be offered and its frequency.
    pub fn for_each_word(&self, mut visit: impl FnMut(&str, u8)) -> Result<(), Error> {
        self.walk(self.root()?, &mut String::new(), &mut visit)
    }

    fn walk(&self, nodes: Vec<Node>, word: &mut String, visit: &mut impl FnMut(&str, u8)) -> Result<(), Error> {
        for node in nodes {
            let length = word.len();
            word.extend(&node.chars);
            if let Some(frequency) = node.frequency
                && !node.hidden
            {
                visit(word, frequency);
            }
            if let Some(children) = node.children {
                self.walk(self.array(children)?, word, visit)?;
            }
            word.truncate(length);
        }
        Ok(())
    }

    pub fn find(&self, word: &str) -> Result<Option<Node>, Error> {
        let word: Vec<char> = word.chars().collect();
        let mut nodes = self.root()?;
        let mut matched = 0;
        loop {
            let Some(node) = nodes.into_iter().find(|node| word[matched..].starts_with(&node.chars)) else {
                return Ok(None);
            };
            matched += node.chars.len();
            if matched == word.len() {
                return Ok(node.frequency.is_some().then_some(node));
            }
            let Some(children) = node.children else { return Ok(None) };
            nodes = self.array(children)?;
        }
    }

    /// The word ending at the node at `address`, and its frequency.
    ///
    /// Nodes do not point back at their parents. Arrays are written so that
    /// every node's descendants lie after its children address and before
    /// the next sibling's, so the way down is through the last node whose
    /// children start at or before the address.
    pub fn word_at(&self, address: usize) -> Result<Option<(String, u8)>, Error> {
        let mut word = String::new();
        let mut nodes = self.root()?;
        loop {
            if let Some(node) = nodes.iter().find(|node| node.address == address) {
                word.extend(&node.chars);
                return Ok(node.frequency.map(|frequency| (word, frequency)));
            }
            let Some(parent) = nodes.iter().filter(|node| node.children.is_some_and(|c| c <= address)).last()
            else {
                return Ok(None);
            };
            word.extend(&parent.chars);
            nodes = self.array(parent.children.expect("filtered on children"))?;
        }
    }

    /// The words recorded as following the word at `node`, with their
    /// frequency in that context, most likely first.
    pub fn next_words(&self, node: &Node) -> Result<Vec<(String, u8)>, Error> {
        let (Some(mut at), Some(unigram)) = (node.bigrams, node.frequency) else { return Ok(Vec::new()) };
        let mut words = Vec::new();
        loop {
            let attribute = self.byte(at)?;
            let size = attribute_size(attribute);
            let offset = self.unsigned(at + 1, size)? as isize;
            let target = (at + 1) as isize + if attribute & ATTRIBUTE_NEGATIVE != 0 { -offset } else { offset };
            if let Some((word, _)) = self.word_at(target as usize)? {
                words.push((word, bigram_frequency(unigram, attribute & MASK_ATTRIBUTE_FREQUENCY)));
            }
            at += 1 + size;
            if attribute & ATTRIBUTE_HAS_NEXT == 0 {
                break;
            }
        }
        words.sort_by(|a, b| b.1.cmp(&a.1));
        Ok(words)
    }
}

fn attribute_size(attribute: u8) -> usize {
    match attribute & MASK_ATTRIBUTE_ADDRESS {
        0x10 => 1,
        0x20 => 2,
        _ => 3,
    }
}

/// A next-word frequency is stored as one of 16 steps between the word's
/// own frequency and 255; this is the middle of the step.
fn bigram_frequency(unigram: u8, step: u8) -> u8 {
    let step_size = (255.0 - unigram as f32) / 16.5;
    (unigram as f32 + (step as f32 + 1.0) * step_size).round().min(255.0) as u8
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// Writes a version 202 dictionary: one character per node, three-byte
    /// addresses throughout, arrays in depth-first order as AOSP writes them.
    pub(crate) fn encode(words: &[(&str, u8)], bigrams: &[(&str, &str, u8)]) -> Vec<u8> {
        #[derive(Default)]
        struct Trie {
            children: Vec<(char, Trie)>,
            frequency: Option<u8>,
            word: String,
        }
        let mut root = Trie::default();
        for &(word, frequency) in words {
            let mut node = &mut root;
            for (i, c) in word.chars().enumerate() {
                let index = match node.children.iter().position(|(d, _)| *d == c) {
                    Some(index) => index,
                    None => {
                        node.children.push((c, Trie { word: word.chars().take(i + 1).collect(), ..Default::default() }));
                        node.children.len() - 1
                    }
                };
                node = &mut node.children[index].1;
            }
            node.frequency = Some(frequency);
        }

        let mut header = Vec::new();
        header.extend(MAGIC.to_be_bytes());
        header.extend(VERSION.to_be_bytes());
        header.extend([0, 0, 0, 0, 0, 0]);
        for text in ["locale", "test"] {
            header.extend(text.bytes());
            header.push(CHARACTERS_TERMINATOR);
        }
        let size = header.len() as u32;
        header[8..12].copy_from_slice(&size.to_be_bytes());

        // Arrays breadth first; sizes are fixed, so addresses follow from
        // counting before anything is written.
        fn next_of<'a>(bigrams: &'a [(&'a str, &'a str, u8)], word: &'a str) -> impl Iterator<Item = &'a (&'a str, &'a str, u8)> {
            bigrams.iter().filter(move |b| b.0 == word)
        }
        let node_size = |node: &Trie| {
            let c = node.word.chars().last().unwrap();
            let char_size = if (0x20..=0xFF).contains(&(c as u32)) { 1 } else { 3 };
            let terminal = node.frequency.is_some() as usize;
            let bigram_size = if terminal == 1 { next_of(bigrams, &node.word).count() * 4 } else { 0 };
            1 + char_size + terminal + 3 + bigram_size
        };
        fn depth_first<'a>(array: &'a Vec<(char, Trie)>, arrays: &mut Vec<&'a Vec<(char, Trie)>>) {
            arrays.push(array);
            for (_, child) in array {
                if !child.children.is_empty() {
                    depth_first(&child.children, arrays);
                }
            }
        }
        let mut arrays = Vec::new();
        depth_first(&root.children, &mut arrays);
        let mut array_address = Vec::new();
        let mut node_address: HashMap<String, usize> = HashMap::new();
        let mut at = header.len();
        for array in &arrays {
            array_address.push(at);
            at += 1;
            for (_, node) in array.iter() {
                node_address.insert(node.word.clone(), at);
                at += node_size(node);
            }
        }
        let children_address = |node: &Trie| {
            arrays.iter().position(|array| std::ptr::eq(*array, &node.children)).map(|i| array_address[i])
        };

        let mut bytes = header;
        for array in &arrays {
            bytes.push(array.len() as u8);
            for (_, node) in array.iter() {
                let terminal = node.frequency.is_some();
                let next: Vec<_> = next_of(bigrams, &node.word).collect();
                let mut flags = 0xC0;
                if terminal {
                    flags |= FLAG_TERMINAL;
                }
                if terminal && !next.is_empty() {
                    flags |= FLAG_BIGRAMS;
                }
                bytes.push(flags);
                let c = node.word.chars().last().unwrap() as u32;
                if (0x20..=0xFF).contains(&c) {
                    bytes.push(c as u8);
                } else {
                    bytes.extend(&c.to_be_bytes()[1..]);
                }
                if let Some(frequency) = node.frequency {
                    bytes.push(frequency);
                }
                let field = bytes.len();
                let offset = children_address(node).map_or(0, |address| address - field);
                bytes.extend(&(offset as u32).to_be_bytes()[1..]);
                for (j, &(_, target, step)) in next.iter().enumerate() {
                    let more = if j + 1 < next.len() { ATTRIBUTE_HAS_NEXT } else { 0 };
                    let target = node_address[&target.to_string()] as isize - (bytes.len() + 1) as isize;
                    let sign = if target < 0 { ATTRIBUTE_NEGATIVE } else { 0 };
                    bytes.push(more | sign | 0x30 | step);
                    bytes.extend(&(target.unsigned_abs() as u32).to_be_bytes()[1..]);
                }
            }
        }
        bytes
    }

    fn sample() -> Dictionary {
        let words = [("Haus", 200), ("hat", 220), ("haus", 90), ("über", 230), ("Straße", 180), ("die", 250)];
        Dictionary::new(encode(&words, &[("die", "Straße", 9), ("die", "Haus", 2)])).unwrap()
    }

    #[test]
    fn reads_the_header() {
        assert_eq!(sample().attributes.get("locale").map(String::as_str), Some("test"));
        assert_eq!(Dictionary::new(vec![0; 20]).err(), Some(Error::NotADictionary));
    }

    #[test]
    fn finds_words_exactly() {
        let dictionary = sample();
        assert_eq!(dictionary.find("Haus").unwrap().unwrap().frequency, Some(200));
        assert_eq!(dictionary.find("haus").unwrap().unwrap().frequency, Some(90));
        assert_eq!(dictionary.find("Straße").unwrap().unwrap().frequency, Some(180));
        assert!(dictionary.find("Hau").unwrap().is_none(), "a prefix is not a word");
        assert!(dictionary.find("Hause").unwrap().is_none());
    }

    #[test]
    fn spells_the_word_at_an_address() {
        let dictionary = sample();
        let node = dictionary.find("über").unwrap().unwrap();
        assert_eq!(dictionary.word_at(node.address).unwrap(), Some(("über".into(), 230)));
    }

    #[test]
    fn next_words_most_likely_first() {
        let dictionary = sample();
        let die = dictionary.find("die").unwrap().unwrap();
        let next: Vec<String> = dictionary.next_words(&die).unwrap().into_iter().map(|(w, _)| w).collect();
        assert_eq!(next, ["Straße", "Haus"]);
    }
}
