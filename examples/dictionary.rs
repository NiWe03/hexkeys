//! Looks words up in a `.dict` file: `cargo run --example dictionary -- FILE WORD...`.

use hexkeys::dictionary::Dictionary;

fn main() {
    let mut args = std::env::args().skip(1);
    let path = args.next().expect("usage: dictionary FILE WORD...");
    let started = std::time::Instant::now();
    let dictionary = Dictionary::new(std::fs::read(&path).expect("read")).expect("parse");
    println!("{:?} loaded in {:?}", dictionary.attributes.get("dictionary"), started.elapsed());
    for word in args {
        match dictionary.find(&word).expect("read") {
            Some(node) => {
                let next = dictionary.next_words(&node).expect("read");
                let next: Vec<_> = next.iter().take(8).map(|(w, f)| format!("{w} {f}")).collect();
                println!("{word}: {:?}, next: {}", node.frequency, next.join(", "));
            }
            None => println!("{word}: not a word"),
        }
    }
}
