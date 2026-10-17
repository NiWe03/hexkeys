//! Words whose swipe could be taken for a delete slide: they start on a key
//! within the delete edge and their path runs leftwards, nearly straight,
//! for the first stretch a slide is judged on. Prints the most frequent:
//! `cargo run --release --example delete_conflicts -- FILE[,FILE...] bottom|middle`.

use hexkeys::dictionary::Dictionary;
use hexkeys::{Keyboard, Point, space_middle, space_bottom};

fn main() {
    let mut args = std::env::args().skip(1);
    let files = args.next().expect("dictionaries");
    let letters = if args.next().as_deref() == Some("middle") { space_middle() } else { space_bottom() };
    let keyboard = Keyboard::new(letters, 1008.0, 1.0, 400);
    let keys = keyboard.swipe_keys();
    let w = keys.width;
    let centre = |c: char| keys.letters.iter().find(|(l, _)| *l == c).map(|(_, p)| *p);
    let mut found: Vec<(u8, String)> = Vec::new();
    for path in files.split(',') {
        let dictionary = Dictionary::new(std::fs::read(path).expect("read")).expect("parse");
        dictionary
            .for_each_word(|word, frequency| {
                let letters: Vec<char> = word.to_lowercase().chars().filter(|c| c.is_alphabetic()).collect();
                let points: Option<Vec<Point>> = letters.iter().map(|&c| centre(c)).collect();
                let Some(mut points) = points else { return };
                points.dedup();
                if points.len() < 2 || points[0].x < 1008.0 - 1.1 * w {
                    return;
                }
                // The path's first 2.5 key widths, which a finger covers in about the
                // 400 ms a slide can still turn into swiping.
                let start = points[0];
                let mut along = 0.0;
                let mut reach = start;
                let mut straight = true;
                for pair in points.windows(2) {
                    let (a, b) = (pair[0], pair[1]);
                    let length = (b.x - a.x).hypot(b.y - a.y);
                    let t = ((2.5 * w - along) / length).min(1.0);
                    let p = Point { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t };
                    if (p.y - start.y).abs() > 0.5 * w {
                        straight = false;
                    }
                    reach = p;
                    along += length * t;
                    if t < 1.0 {
                        break;
                    }
                }
                let leftwards = start.x - reach.x >= (reach.y - start.y).abs() && start.x - reach.x > 0.35 * w;
                if straight && leftwards {
                    found.push((frequency, word.to_owned()));
                }
            })
            .expect("walk");
    }
    found.sort_by(|a, b| b.0.cmp(&a.0));
    found.dedup_by(|a, b| a.1 == b.1);
    for (frequency, word) in found.iter().take(60) {
        print!("{word}({frequency}) ");
    }
    println!("\n{} words", found.len());
}
