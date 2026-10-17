//! Swipe decoding measured on real swipes from FUTO's swipe dataset
//! (huggingface.co/datasets/futo-org/swipe.futo.org, MIT), drawn on its
//! QWERTY layout: `cargo run --release --example futo -- FILE[,FILE...] SWIPES.jsonl LAYOUT.json COUNT`.
//! The decoder only needs key centres, so the dataset's layout is used as is.

use hexkeys::dictionary::Dictionary;
use hexkeys::suggest::Lexicon;
use hexkeys::swipe::{Keys, Tuning, decode_tuned};
use hexkeys::Point;
use serde_json::Value;
use std::io::BufRead;

fn main() {
    let mut args = std::env::args().skip(1);
    let usage = "usage: futo FILE[,FILE...] SWIPES.jsonl LAYOUT.json COUNT";
    let paths = args.next().expect(usage);
    let swipes = args.next().expect(usage);
    let layout: Value = serde_json::from_str(&std::fs::read_to_string(args.next().expect(usage)).expect("layout")).expect("json");
    let count: usize = args.next().expect(usage).parse().expect("count");
    // TUNE="end_radius=1.5,capitals=40" overrides weights.
    let mut tuning = Tuning::default();
    for setting in std::env::var("TUNE").unwrap_or_default().split(',').filter(|s| !s.is_empty()) {
        let (name, value) = setting.split_once('=').expect("name=value");
        let value: f32 = value.parse().expect("number");
        match name {
            "end_radius" => tuning.end_radius = value,
            "max_distance" => tuning.max_distance = value,
            "distance_weight" => tuning.distance_weight = value,
            "pass_free" => tuning.pass_free = value,
            "pass_weight" => tuning.pass_weight = value,
            "end_weight" => tuning.end_weight = value,
            "capitals" => tuning.capitals = value,
            "warp" => tuning.warp = value as usize,
            "turn_weight" => tuning.turn_weight = value,
            "turn_angle" => tuning.turn_angle = value,
            "context_weight" => tuning.context_weight = value,
            _ => panic!("no weight {name}"),
        }
    }
    let mut lexicon = Lexicon::default();
    for path in paths.split(',') {
        lexicon.add(Dictionary::new(std::fs::read(path).expect("read")).expect("parse"));
    }
    let (mut first, mut top3, mut total, mut considered, mut unknown) = (0, 0, 0, 0, 0);
    let mut time = std::time::Duration::ZERO;
    let mut slowest = std::time::Duration::ZERO;
    let file = std::io::BufReader::new(std::fs::File::open(swipes).expect("swipes"));
    for line in file.lines() {
        let swipe: Value = serde_json::from_str(&line.expect("line")).expect("json");
        let word = swipe["word"]
            .as_str()
            .unwrap_or("")
            .trim_matches(|c: char| !c.is_alphanumeric() && c != '\'')
            .to_lowercase();
        // Very large distances mark swipes that do not spell their word.
        if word.chars().count() < 2 || swipe["distance"].as_f64().unwrap_or(f64::MAX) > 1000.0 {
            continue;
        }
        let (width, height) = (swipe["canvas_width"].as_f64().unwrap() as f32, swipe["canvas_height"].as_f64().unwrap() as f32);
        let letters = layout["keys"]
            .as_array()
            .unwrap()
            .iter()
            .map(|key| {
                let c = key["letter"].as_str().unwrap().chars().next().unwrap();
                (c, Point { x: key["cx"].as_f64().unwrap() as f32 * width, y: key["cy"].as_f64().unwrap() as f32 * height })
            })
            .collect();
        let keys = Keys { letters, width: width / 10.0 };
        let path: Vec<Point> = swipe["data"]
            .as_array()
            .unwrap()
            .iter()
            .map(|p| Point { x: p["x"].as_f64().unwrap() as f32 * width, y: p["y"].as_f64().unwrap() as f32 * height })
            .collect();
        let sentence = swipe["sentence"].as_str().unwrap_or("");
        let index = swipe["word_idx"].as_u64().unwrap_or(0) as usize;
        let previous = index.checked_sub(1).and_then(|i| sentence.split_whitespace().nth(i)).map(|w| {
            w.trim_matches(|c: char| !c.is_alphanumeric() && c != '\'').to_owned()
        });
        // Words no dictionary has cannot be swiped; they are counted apart.
        if !hexkeys::suggest::is_word(&lexicon, &word).expect("read") {
            unknown += 1;
            continue;
        }
        let started = std::time::Instant::now();
        let found = decode_tuned(&lexicon, &keys, &path, previous.as_deref(), 3, &tuning).expect("decode");
        let elapsed = started.elapsed();
        if std::env::var("CONSIDERED").is_ok() {
            let all = decode_tuned(&lexicon, &keys, &path, previous.as_deref(), 1000, &tuning).expect("decode");
            considered += all.iter().any(|w| w.to_lowercase() == word) as usize;
        }
        if total > 0 {
            time += elapsed;
            slowest = slowest.max(elapsed);
        }
        total += 1;
        let hit = found.iter().position(|w| w.to_lowercase() == word);
        first += (hit == Some(0)) as usize;
        top3 += hit.is_some() as usize;
        if std::env::var("SHOW_MISSES").is_ok() && hit != Some(0) {
            println!("{word:>14}  {}", found.join(", "));
        }
        if total == count {
            break;
        }
    }
    println!(
        "first {:.1}%, top three {:.1}%, considered {:.1}% of {total} known words ({unknown} unknown skipped); mean {:.1?}, slowest {:.1?}",
        100.0 * first as f32 / total as f32,
        100.0 * top3 as f32 / total as f32,
        100.0 * considered as f32 / total as f32,
        time / (total.max(2) - 1) as u32,
        slowest
    );
}
