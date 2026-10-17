//! What holding each letter offers for the given `.dict` files:
//! `cargo run --release --example accents -- FILE[,FILE...]`.

use hexkeys::dictionary::Dictionary;
use hexkeys::suggest::Lexicon;

fn main() {
    let paths = std::env::args().nth(1).expect("usage: accents FILE[,FILE...]");
    let lexicon = Lexicon::new(
        paths.split(',').map(|path| Dictionary::new(std::fs::read(path).expect("read")).expect("parse")).collect(),
    );
    let started = std::time::Instant::now();
    let accents = lexicon.accents().expect("read");
    let mut letters: Vec<_> = accents.into_iter().collect();
    letters.sort();
    for (letter, accented) in letters {
        println!("{letter}: {}", accented.iter().collect::<String>());
    }
    println!("in {:.0?}", started.elapsed());
}
