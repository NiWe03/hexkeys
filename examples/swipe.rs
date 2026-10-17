//! Swipe decoding on `.dict` files, timed, with synthetic paths: each word
//! traced through its key centres with every corner cut and the points
//! scattered, the way a quick finger draws it.
//! `cargo run --release --example swipe -- FILE[,FILE...] honeycomb|space_middle SCATTER WORD...`
//! where SCATTER is the spread of the points in key widths.

use hexkeys::dictionary::Dictionary;
use hexkeys::suggest::Lexicon;
use hexkeys::swipe::{Keys, decode};
use hexkeys::{Keyboard, Point, honeycomb, space_middle};

/// A small deterministic generator, so runs compare.
struct Noise(u64);

impl Noise {
    /// Roughly normal, mean 0 and spread 1.
    fn next(&mut self) -> f32 {
        let mut sum = 0.0;
        for _ in 0..4 {
            self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            sum += (self.0 >> 40) as f32 / (1u64 << 24) as f32;
        }
        (sum - 2.0) * 1.73
    }
}

fn trace(keys: &Keys, word: &str, scatter: f32, noise: &mut Noise) -> Vec<Point> {
    let centres: Vec<Point> = word
        .chars()
        .filter_map(|c| keys.letters.iter().find(|(letter, _)| *letter == c).map(|(_, p)| *p))
        .collect();
    let mut path = Vec::new();
    for pair in centres.windows(2) {
        for step in 0..12 {
            let t = step as f32 / 12.0;
            path.push(Point { x: pair[0].x + (pair[1].x - pair[0].x) * t, y: pair[0].y + (pair[1].y - pair[0].y) * t });
        }
    }
    path.push(*centres.last().expect("a word"));
    // Corners cut: each point the average of its neighbours, a few times over.
    for _ in 0..3 {
        let smoothed: Vec<Point> = (0..path.len())
            .map(|i| {
                let (a, b) = (path[i.saturating_sub(2)], path[(i + 2).min(path.len() - 1)]);
                Point { x: (a.x + path[i].x + b.x) / 3.0, y: (a.y + path[i].y + b.y) / 3.0 }
            })
            .collect();
        path = smoothed;
    }
    let (dx, dy) = (noise.next() * scatter * keys.width, noise.next() * scatter * keys.width);
    path.iter()
        .map(|p| Point {
            x: p.x + dx + noise.next() * scatter * 0.3 * keys.width,
            y: p.y + dy + noise.next() * scatter * 0.3 * keys.width,
        })
        .collect()
}

fn main() {
    let mut args = std::env::args().skip(1);
    let usage = "usage: swipe FILE[,FILE...] honeycomb|space_middle SCATTER WORD...";
    let paths = args.next().expect(usage);
    let letters = match args.next().expect(usage).as_str() {
        "space_middle" => space_middle(),
        _ => honeycomb(),
    };
    let scatter: f32 = args.next().expect(usage).parse().expect("scatter");
    let mut lexicon = Lexicon::default();
    for path in paths.split(',') {
        lexicon.add(Dictionary::new(std::fs::read(path).expect("read")).expect("parse"));
    }
    let keys = Keyboard::new(letters, 1080.0, 1.0, 400).swipe_keys();
    let mut noise = Noise(7);
    let started = std::time::Instant::now();
    decode(&lexicon, &keys, &trace(&keys, "the", 0.0, &mut noise), None, 1).expect("decode");
    println!("index built and first word in {:.1?}", started.elapsed());
    let (mut first, mut top3, mut total) = (0, 0, 0);
    for word in args {
        let path = trace(&keys, &word, scatter, &mut noise);
        let started = std::time::Instant::now();
        let found = decode(&lexicon, &keys, &path, None, 3).expect("decode");
        let elapsed = started.elapsed();
        total += 1;
        let hit = found.iter().position(|w| w.to_lowercase() == word);
        first += (hit == Some(0)) as usize;
        top3 += hit.is_some() as usize;
        let mark = match hit {
            Some(0) => " ",
            Some(_) => "~",
            None => "x",
        };
        println!("{mark} {word:>12} {elapsed:>7.1?}  {}", found.join(", "));
    }
    println!("first {first}/{total}, top three {top3}/{total}");
}
