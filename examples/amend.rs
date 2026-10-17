//! Types sentences word by word, as with a space after each, and prints what
//! context autocorrect makes of them:
//! `cargo run --release --example amend -- FILE.dict CONTEXTS.tsv "its good" ...`.

use hexkeys::context::{Contexts, amend};
use hexkeys::dictionary::Dictionary;
use hexkeys::suggest::Lexicon;

fn main() {
    let mut args = std::env::args().skip(1);
    let dictionary = Dictionary::new(std::fs::read(args.next().expect("dictionary")).expect("read")).expect("parse");
    let counts = std::fs::read_to_string(args.next().expect("contexts")).expect("read");
    let mut lexicon = Lexicon::new(vec![dictionary]);
    lexicon.add_contexts(0, Contexts::parse(&counts));
    for sentence in args {
        let mut text = String::new();
        let pieces: Vec<String> = sentence
            .split(' ')
            .flat_map(|word| match word.strip_suffix(['.', ',', '?', '!']) {
                Some(stem) => vec![format!("{stem}"), word[stem.len()..].to_owned()],
                None => vec![word.to_owned()],
            })
            .collect();
        for piece in pieces {
            let punctuation = piece.len() == 1 && !piece.chars().all(char::is_alphanumeric);
            if punctuation {
                text = text.trim_end().to_owned();
                text.push_str(&piece);
                text.push(' ');
            } else {
                text.push_str(&piece);
                text.push(' ');
            }
            let probe = if punctuation { text.trim_end().to_owned() } else { text.clone() };
            if let Some((replaced, tail)) = amend(&lexicon, &probe, None) {
                let cut = probe.len() - probe.chars().rev().take(replaced).map(char::len_utf8).sum::<usize>();
                text = format!("{}{}{}", &probe[..cut], tail, &text[probe.len()..]);
            }
        }
        println!("{sentence:40} -> {}", text.trim_end());
    }
}
