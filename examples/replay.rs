//! Runs touches from a debug build's gesture log through the core again and
//! prints what each does now, to check a fix against what really happened:
//! `cargo run --example replay -- gestures.jsonl [INDEX...]`, all touches
//! when no index is given. Ticks are simulated every 10 ms between points.

use hexkeys::{Action, Capital, Keyboard, Point, honeycomb, space_middle};
use serde_json::Value;

fn main() {
    let mut args = std::env::args().skip(1);
    let file = args.next().expect("usage: replay gestures.jsonl [INDEX...]");
    let wanted: Vec<usize> = args.map(|a| a.parse().expect("index")).collect();
    let text = std::fs::read_to_string(file).expect("read");
    for (index, line) in text.lines().enumerate() {
        if !wanted.is_empty() && !wanted.contains(&index) {
            continue;
        }
        let record: Value = serde_json::from_str(line).expect("json");
        let Some(points) = record["points"].as_array() else { continue };
        let letters = if record["layout"] == "space_middle" { space_middle() } else { honeycomb() };
        let mut keyboard = Keyboard::new(letters, record["width"].as_f64().unwrap() as f32, 1.0, 400);
        keyboard.gestures.delete_from_left = record["deleteFromLeft"].as_bool().unwrap_or(false);
        keyboard.gestures.capital = match record["capital"].as_str() {
            Some("up_and_back") => Capital::UpAndBack,
            Some("flick_down") => Capital::FlickDown,
            Some("hold") => Capital::Hold,
            _ => Capital::FlickUp,
        };
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
    }
}
