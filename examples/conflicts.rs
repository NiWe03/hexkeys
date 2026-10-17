//! Which short words a swipe could be taken for a capital gesture:
//! `cargo run --example conflicts -- FILE[,FILE...]`.
//!
//! A two-letter word whose second key touches the first in the direction
//! of a flick is swiped as a slide of about one key width that way, the
//! same movement as that flick a little longer. Up and back collides with
//! three-letter words that go to an upper neighbour and return. Words are
//! listed most frequent first, for both layouts.

use hexkeys::dictionary::{Dictionary, Node};
use hexkeys::{Keyboard, Point, honeycomb, space_middle};

fn collect(dictionary: &Dictionary, nodes: Vec<Node>, word: &mut String, words: &mut Vec<(String, u8)>) {
    for node in nodes {
        let length = word.len();
        word.extend(&node.chars);
        if let Some(frequency) = node.frequency
            && !node.hidden
            && word.chars().count() <= 3
        {
            words.push((word.to_lowercase(), frequency));
        }
        if let Some(children) = node.children
            && word.chars().count() < 3
        {
            collect(dictionary, dictionary.array(children).expect("read"), word, words);
        }
        word.truncate(length);
    }
}

fn main() {
    let paths = std::env::args().nth(1).expect("usage: conflicts FILE[,FILE...]");
    let mut words = Vec::new();
    for path in paths.split(',') {
        let dictionary = Dictionary::new(std::fs::read(path).expect("read")).expect("parse");
        collect(&dictionary, dictionary.root().expect("read"), &mut String::new(), &mut words);
    }
    // One entry per word, with its highest frequency, most frequent first.
    words.sort_by(|a, b| a.0.cmp(&b.0).then(b.1.cmp(&a.1)));
    words.dedup_by(|a, b| a.0 == b.0);
    words.sort_by(|a, b| b.1.cmp(&a.1));
    for (name, spec) in [("hexkeys", honeycomb()), ("space_middle", space_middle())] {
        let keys = Keyboard::new(spec, 1080.0, 1.0, 400).swipe_keys();
        let centre = |c: char| keys.letters.iter().find(|(l, _)| *l == c).map(|(_, p)| *p);
        // Direction from a to b when b is a neighbouring key: "up", "down", or other.
        let direction = |a: Point, b: Point| -> Option<&str> {
            let (dx, dy) = (b.x - a.x, b.y - a.y);
            if dx.hypot(dy) > 1.1 * keys.width {
                return None;
            }
            if dy.abs() <= dx.abs() {
                None
            } else if dy < 0.0 {
                Some("up")
            } else {
                Some("down")
            }
        };
        println!("== {name}");
        for gesture in ["up", "down", "up and back"] {
            let mut hits = Vec::new();
            for (word, frequency) in &words {
                let letters: Vec<char> = word.chars().collect();
                let points: Option<Vec<Point>> = letters.iter().map(|&c| centre(c)).collect();
                let Some(points) = points else { continue };
                let hit = match (gesture, points.len()) {
                    ("up", 2) => direction(points[0], points[1]) == Some("up"),
                    ("down", 2) => direction(points[0], points[1]) == Some("down"),
                    ("up and back", 3) => letters[0] == letters[2] && direction(points[0], points[1]) == Some("up"),
                    _ => false,
                };
                if hit {
                    hits.push(format!("{word} {frequency}"));
                }
            }
            println!("{gesture:>12}: {} words: {}", hits.len(), hits.iter().take(25).cloned().collect::<Vec<_>>().join(", "));
        }
    }
}
