//! Suggestions from `.dict` files for typed words, timed, and with `PREVIOUS:`
//! alone what is predicted after it, and with `=` the strip for that text
//! before the cursor:
//! `cargo run --release --example suggest -- FILE[,FILE...] [PREVIOUS:]WORD... [=TEXT]...`.

use hexkeys::dictionary::Dictionary;
use hexkeys::{Layout, honeycomb, suggest};

fn main() {
    let mut args = std::env::args().skip(1);
    let paths = args.next().expect("usage: suggest FILE[,FILE...] [PREVIOUS:]WORD...");
    let lexicon = suggest::Lexicon::new(
        paths.split(',').map(|path| Dictionary::new(std::fs::read(path).expect("read")).expect("parse")).collect(),
    );
    let layout = Layout::fit(&honeycomb(), 1080.0, 1.0);
    let substitution = |typed: char, meant: char| layout.substitution(typed, meant);
    for arg in args {
        if let Some(before) = arg.strip_prefix('=') {
            let strip = suggest::strip(&lexicon, before, &substitution).expect("read");
            println!("{before}\t{}\t{}\t{}", strip.slots.join("|"), strip.correction.unwrap_or_default(), strip.unknown.unwrap_or_default());
            continue;
        }
        let (previous, typed) = match arg.split_once(':') {
            Some((previous, typed)) => (Some(previous), typed),
            None => (None, arg.as_str()),
        };
        let started = std::time::Instant::now();
        if let (Some(previous), "") = (previous, typed) {
            let words = suggest::predict(&lexicon, previous, 5).expect("read");
            println!("{arg:>16} {:>6.1?}  {}", started.elapsed(), words.join(", "));
            continue;
        }
        let found = suggest::suggest(&lexicon, typed, previous, &substitution, 5).expect("read");
        let elapsed = started.elapsed();
        let list: Vec<String> = found.iter().map(|s| format!("{} {:.0}{}", s.word, s.score, if s.exact { "*" } else { "" })).collect();
        println!("{arg:>16} {:>6.1?}  {}", elapsed, list.join(", "));
    }
}
