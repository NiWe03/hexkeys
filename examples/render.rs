//! Writes a layout as SVG to stdout:
//! `cargo run --example render -- <width> <stretch> [qwertz|8|7]`.

use hexkeys::{Layout, Point, honeycomb_7, honeycomb_8, qwertz};

fn main() {
    let mut args = std::env::args().skip(1);
    let width: f32 = args.next().map_or(1008.0, |a| a.parse().expect("width"));
    let stretch: f32 = args.next().map_or(1.0, |a| a.parse().expect("stretch"));
    let spec = match args.next().as_deref() {
        Some("8") => honeycomb_8(),
        Some("7") => honeycomb_7(),
        _ => qwertz(),
    };
    let layout = Layout::fit(&spec, width, stretch);
    let gap = layout.cell_width * 0.04;

    println!(
        r##"<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="{x0} {y0} {vw} {vh}">"##,
        w = width,
        h = layout.height + 2.0 * gap,
        x0 = -gap,
        y0 = -gap,
        vw = width + 2.0 * gap,
        vh = layout.height + 2.0 * gap,
    );
    println!(r##"<rect x="{}" y="{}" width="100%" height="100%" fill="#1b1b1f"/>"##, -gap, -gap);
    for key in &layout.keys {
        let center = key.center();
        let points: Vec<String> = inset(&layout.outline(key), gap)
            .iter()
            .map(|p| format!("{:.1},{:.1}", p.x, p.y))
            .collect();
        let letter = key.label.chars().count() == 1 && key.label.chars().all(char::is_alphabetic);
        let fill = if letter { "#3a3a42" } else { "#2a2a30" };
        let size = if letter { layout.cell_width * 0.36 } else { layout.cell_width * 0.26 };
        println!(r##"<polygon points="{}" fill="{fill}"/>"##, points.join(" "));
        println!(
            r##"<text x="{:.1}" y="{:.1}" font-family="sans-serif" font-size="{size:.1}" fill="#e8e8ee" text-anchor="middle" dominant-baseline="central">{}</text>"##,
            center.x, center.y, key.label
        );
    }
    println!("</svg>");
}

/// Moves every edge of a convex clockwise polygon inwards by `distance`, so
/// neighbouring keys show a gap of twice that everywhere.
fn inset(polygon: &[Point], distance: f32) -> Vec<Point> {
    let n = polygon.len();
    // Each edge as a point on it plus its inward unit normal. In screen
    // coordinates (y down) a clockwise polygon has its inside on the right.
    let edges: Vec<(Point, Point)> = (0..n)
        .map(|i| {
            let a = polygon[i];
            let b = polygon[(i + 1) % n];
            let (dx, dy) = (b.x - a.x, b.y - a.y);
            let length = (dx * dx + dy * dy).sqrt();
            let normal = Point { x: -dy / length, y: dx / length };
            (Point { x: a.x + normal.x * distance, y: a.y + normal.y * distance }, Point { x: dx, y: dy })
        })
        .collect();
    // New corners are where consecutive shifted edges intersect.
    (0..n)
        .map(|i| {
            let (p, r) = edges[(i + n - 1) % n];
            let (q, s) = edges[i];
            let cross = r.x * s.y - r.y * s.x;
            let t = ((q.x - p.x) * s.y - (q.y - p.y) * s.x) / cross;
            Point { x: p.x + r.x * t, y: p.y + r.y * t }
        })
        .collect()
}
