//! Writes every key's outline of the space-at-the-bottom layout as TSV, a
//! key's label then its corners as x,y, after a first line with the cell
//! width, cell height and layout height: `cargo run --example outlines -- [WIDTH]`.

use hexkeys::{Layout, space_bottom};

fn main() {
    let width: f32 = std::env::args().nth(1).map_or(1008.0, |a| a.parse().expect("width"));
    let layout = Layout::fit(&space_bottom(), width, 1.0);
    println!("{} {} {}", layout.cell_width, layout.cell_height, layout.height);
    for key in &layout.keys {
        let corners: Vec<String> = layout.outline(key).iter().map(|p| format!("{:.1},{:.1}", p.x, p.y)).collect();
        println!("{}\t{}", key.label, corners.join(" "));
    }
}
