//! Runs touches from a debug build's gesture log through the core again and
//! prints what each does now, to check a fix against what really happened:
//! `cargo run --example replay -- gestures.jsonl [INDEX...]`, all touches
//! when no index is given. Ticks are simulated every 10 ms between points.
//! With `DICTS=a.dict,b.dict` a swipe is also decoded and its words printed,
//! with `START_WEIGHT` in place of the decoder's own if given.

use hexkeys::dictionary::Dictionary;
use hexkeys::suggest::{Lexicon, last_word};
use hexkeys::{Action, Keyboard, Point, swipe, space_middle, space_bottom, with_shift_and_backspace};
use serde_json::Value;

fn main() {
    let mut args = std::env::args().skip(1);
    let file = args.next().expect("usage: replay gestures.jsonl [INDEX...]");
    let wanted: Vec<usize> = args.map(|a| a.parse().expect("index")).collect();
    let text = std::fs::read_to_string(file).expect("read");
    let mut lexicon = Lexicon::default();
    for path in std::env::var("DICTS").unwrap_or_default().split(',').filter(|p| !p.is_empty()) {
        lexicon.add(Dictionary::new(std::fs::read(path).expect("read dictionary")).expect("parse dictionary"));
    }
    for (index, line) in text.lines().enumerate() {
        if !wanted.is_empty() && !wanted.contains(&index) {
            continue;
        }
        let record: Value = serde_json::from_str(line).expect("json");
        let Some(points) = record["points"].as_array() else { continue };
        let mut letters = if record["layout"] == "space_middle" { space_middle() } else { space_bottom() };
        if record["editKeys"].as_bool().unwrap_or(false) {
            letters = with_shift_and_backspace(letters);
        }
        let mut keyboard = Keyboard::new(letters, record["width"].as_f64().unwrap() as f32, 1.0, 400);
        keyboard.gestures.delete_from_left = record["deleteFromLeft"].as_bool().unwrap_or(false);
        let point = |p: &Value| (Point { x: p[0].as_f64().unwrap() as f32, y: p[1].as_f64().unwrap() as f32 }, p[2].as_u64().unwrap());
        let mut actions = Vec::new();
        let mut keep = |action: Action| {
            if action != Action::Nothing {
                actions.push(format!("{action:?}"));
            }
        };
        let (start, mut time) = point(&points[0]);
        keyboard.touch_down(start, time);
        for (i, p) in points.iter().enumerate().skip(1) {
            let (at, when) = point(p);
            while time + 10 < when {
                time += 10;
                keep(keyboard.tick(time));
            }
            time = when;
            if i + 1 == points.len() {
                keep(keyboard.touch_up(at, when));
            } else {
                keep(keyboard.touch_move(at, when));
            }
        }
        println!("{index} key {} then: {}", record["key"], record["actions"]);
        println!("{index}       now: {}", actions.join(", "));
        if let Some(swiped) = keyboard.swiped.as_ref().filter(|_| actions.iter().any(|a| a == "Swipe")) {
            let previous = record["before"].as_str().and_then(last_word);
            lexicon.set_context(&format!("{} ", record["before"].as_str().unwrap_or("")));
            let mut tuning = swipe::Tuning::default();
            if let Ok(weight) = std::env::var("START_WEIGHT") {
                tuning.start_weight = weight.parse().expect("START_WEIGHT");
            }
            let words = swipe::decode_tuned(&lexicon, &keyboard.swipe_keys(), &swiped.path, previous, 5, &tuning).unwrap_or_default();
            println!("{index}     words: {}", words.join(", "));
        }
    }
}
