//! Words whose swipe could be taken for a delete slide: those starting on a
//! key in the right edge column, where delete slides used to start. Each is swiped through the keyboard at the
//! speeds measured from the phone's gesture log, through its letters' key
//! centres with some scatter, after the pause a finger makes before it
//! moves; prints how many come out as a delete instead of a swipe, by
//! speed, and the most frequent of them:
//! `cargo run --release --example delete_conflicts -- FILE[,FILE...] bottom|middle`.

use hexkeys::dictionary::Dictionary;
use hexkeys::{Action, Keyboard, Point, space_middle, space_bottom};

/// Speeds in key widths a second: the 10th, 25th, 50th, 75th and 90th
/// percentile of the user's swipes in the log.
const SPEEDS: [f32; 5] = [6.3, 12.1, 16.8, 20.0, 24.7];
/// The median pause before the finger moves, in ms.
const PAUSE: u64 = 56;

struct Noise(u64);

impl Noise {
    /// Roughly normal, mean 0 and spread 1.
    fn next(&mut self) -> f32 {
        let mut sum = 0.0;
        for _ in 0..6 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            sum += (self.0 % 10_000) as f32 / 10_000.0;
        }
        (sum - 3.0) / 0.707
    }
}

fn main() {
    let mut args = std::env::args().skip(1);
    let files = args.next().expect("dictionaries");
    let letters = if args.next().as_deref() == Some("middle") { space_middle() } else { space_bottom() };
    let probe = Keyboard::new(letters.clone(), 1008.0, 1.0, 400);
    let keys = probe.swipe_keys();
    let w = keys.width;
    let edge = 1008.0 - 1.1 * w;
    let centre = |c: char| keys.letters.iter().find(|(l, _)| *l == c).map(|(_, p)| *p);
    let mut words: Vec<(u8, String, Vec<Point>)> = Vec::new();
    for path in files.split(',') {
        let dictionary = Dictionary::new(std::fs::read(path).expect("read")).expect("parse");
        dictionary
            .for_each_word(|word, frequency| {
                let letters: Vec<char> = word.to_lowercase().chars().filter(|c| c.is_alphabetic()).collect();
                let Some(mut points) = letters.iter().map(|&c| centre(c)).collect::<Option<Vec<Point>>>() else { return };
                points.dedup();
                if points.len() >= 2 && points[0].x > edge {
                    words.push((frequency, word.to_owned(), points));
                }
            })
            .expect("walk");
    }
    words.sort_by_key(|word| std::cmp::Reverse(word.0));
    words.dedup_by(|a, b| a.1 == b.1);
    let mut noise = Noise(0x9e3779b97f4a7c15);
    let mut keyboard = Keyboard::new(letters, 1008.0, 1.0, 400);
    for speed in SPEEDS {
        let (mut deleted, mut weight, mut total_weight) = (Vec::new(), 0.0f64, 0.0f64);
        for (frequency, word, points) in &words {
            // Scatter around each key, as a finger never hits centres.
            let path: Vec<Point> = points
                .iter()
                .map(|p| Point { x: (p.x + noise.next() * 0.15 * w).min(1007.0), y: p.y + noise.next() * 0.15 * w })
                .collect();
            let mut time = 0u64;
            keyboard.touch_down(path[0], 0);
            time += PAUSE;
            keyboard.touch_move(path[0], time);
            let mut is_delete = false;
            for pair in path.windows(2) {
                let (a, b) = (pair[0], pair[1]);
                let length = (b.x - a.x).hypot(b.y - a.y);
                let steps = ((length / (speed * w) * 1000.0) / 16.0).ceil().max(1.0) as usize;
                for step in 1..=steps {
                    let t = step as f32 / steps as f32;
                    time += 16;
                    let action = keyboard.touch_move(Point { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t }, time);
                    let _ = keyboard.tick(time);
                    if matches!(action, Action::Delete { .. }) {
                        is_delete = true;
                        if std::env::var("WORD").as_deref() == Ok(word.as_str()) {
                            eprintln!("  at {time} ms, step {step}/{steps}: {action:?}");
                        }
                    }
                }
            }
            if std::env::var("WORD").as_deref() == Ok(word.as_str()) {
                eprintln!("{word} at {speed}: path {:?}", path.iter().map(|p| (p.x as i32, p.y as i32)).collect::<Vec<_>>());
            }
            let end = path[path.len() - 1];
            let last = keyboard.touch_up(end, time + 16);
            if std::env::var("WORD").as_deref() == Ok(word.as_str()) {
                eprintln!("  delete during: {is_delete}, last {last:?}");
            }
            // A delete that bent into a swipe ends as one: what counts is the end.
            let delete = !matches!(last, Action::Swipe) && (is_delete || matches!(last, Action::Delete { .. }));
            let w8 = 2f64.powf(*frequency as f64 / 16.0);
            total_weight += w8;
            if delete {
                weight += w8;
                deleted.push(word.clone());
            }
        }
        println!(
            "{speed:>5.1} kw/s: {} of {} words ({:.1}%) deleted, {:.1}% weighted by use; most used: {}",
            deleted.len(),
            words.len(),
            100.0 * deleted.len() as f64 / words.len() as f64,
            100.0 * weight / total_weight,
            deleted.iter().take(25).cloned().collect::<Vec<_>>().join(" "),
        );
    }
}
