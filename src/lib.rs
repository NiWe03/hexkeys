//! Hexagonal keyboard geometry.
//!
//! Keys sit on a honeycomb of pointy-top hexagon cells. Every other row is
//! shifted by half a cell, so each cell touches six neighbours. A key covers
//! one cell or a run of adjacent cells in the same row (space, shift, enter),
//! drawn as one elongated hexagon.
//!
//! Hit-testing picks the cell whose centre is nearest to the touch. On a
//! regular hexagonal lattice the region nearest to a centre is exactly that
//! hexagon, so the touch area matches what is drawn. Cells may be stretched
//! vertically to give taller keys; distances are then measured with y divided
//! by the stretch, which keeps the nearest-centre regions equal to the
//! stretched hexagons.

/// One key as authored in a layout: what it is and how many cells it spans.
#[derive(Clone, Debug, PartialEq)]
pub struct KeySpec {
    pub label: String,
    pub span: usize,
}

impl KeySpec {
    pub fn new(label: &str, span: usize) -> Self {
        assert!(span >= 1, "a key spans at least one cell");
        Self { label: label.to_owned(), span }
    }
}

/// One row: its keys from left to right, starting at a cell column.
///
/// Columns count in whole cells. Odd rows are shifted right by half a cell
/// on top of that, which is what interlocks the rows.
#[derive(Clone, Debug, PartialEq)]
pub struct RowSpec {
    pub start_column: usize,
    pub keys: Vec<KeySpec>,
}

/// A layout before it is fitted to a screen.
#[derive(Clone, Debug, PartialEq)]
pub struct LayoutSpec {
    pub rows: Vec<RowSpec>,
}

impl LayoutSpec {
    /// Width in cells, counting the half-cell shift of odd rows.
    pub fn width_in_cells(&self) -> f32 {
        self.rows
            .iter()
            .enumerate()
            .map(|(row, spec)| {
                let cells: usize = spec.keys.iter().map(|key| key.span).sum();
                (spec.start_column + cells) as f32 + row_shift(row)
            })
            .fold(0.0, f32::max)
    }
}

fn row_shift(row: usize) -> f32 {
    if row % 2 == 1 { 0.5 } else { 0.0 }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Point {
    pub x: f32,
    pub y: f32,
}

/// A key placed on screen.
#[derive(Clone, Debug, PartialEq)]
pub struct Key {
    pub label: String,
    pub row: usize,
    /// Centres of the cells the key covers, left to right.
    pub cells: Vec<Point>,
    /// `cells` plus, for a key at the end of a row that stops half a cell
    /// short of the keyboard's edge, one more centre beyond it. The key's
    /// area then runs on to the edge, which keeps the keyboard's sides
    /// straight instead of leaving the honeycomb's half-cell notches.
    pub regions: Vec<Point>,
}

impl Key {
    /// Centre of the whole key, where its label goes.
    pub fn center(&self) -> Point {
        let first = self.cells[0];
        let last = self.cells[self.cells.len() - 1];
        Point { x: (first.x + last.x) / 2.0, y: first.y }
    }
}

/// A layout fitted to a width in pixels.
#[derive(Clone, Debug, PartialEq)]
pub struct Layout {
    pub keys: Vec<Key>,
    /// Distance between neighbouring cell centres in a row.
    pub cell_width: f32,
    /// Height of a cell from top vertex to bottom vertex.
    pub cell_height: f32,
    pub width: f32,
    pub height: f32,
}

impl Layout {
    /// Fits `spec` to `width` pixels. `stretch` scales cell height relative to
    /// a regular hexagon (1.0 = regular).
    pub fn fit(spec: &LayoutSpec, width: f32, stretch: f32) -> Self {
        let cell_width = width / spec.width_in_cells();
        let cell_height = cell_width * 2.0 / 3f32.sqrt() * stretch;
        let row_pitch = cell_height * 0.75;

        let mut keys = Vec::new();
        for (row, row_spec) in spec.rows.iter().enumerate() {
            let y = cell_height / 2.0 + row as f32 * row_pitch;
            let mut column = row_spec.start_column as f32 + row_shift(row);
            for key_spec in &row_spec.keys {
                let cells = (0..key_spec.span)
                    .map(|i| Point { x: (column + i as f32 + 0.5) * cell_width, y })
                    .collect();
                keys.push(Key { label: key_spec.label.clone(), row, regions: Vec::new(), cells });
                column += key_spec.span as f32;
            }
        }

        for key in &mut keys {
            key.regions = key.cells.clone();
        }
        for row in 0..spec.rows.len() {
            let in_row = |key: &&mut Key| key.row == row;
            if let Some(first) = keys.iter_mut().find(in_row) {
                let left = first.cells[0];
                if left.x - cell_width / 2.0 > 0.01 {
                    first.regions.insert(0, Point { x: left.x - cell_width, y: left.y });
                }
            }
            if let Some(last) = keys.iter_mut().rev().find(in_row) {
                let right = last.cells[last.cells.len() - 1];
                if right.x + cell_width / 2.0 < width - 0.01 {
                    last.regions.push(Point { x: right.x + cell_width, y: right.y });
                }
            }
        }

        let height = cell_height + (spec.rows.len().saturating_sub(1)) as f32 * row_pitch;
        Self { keys, cell_width, cell_height, width, height }
    }

    /// The key under a touch: the one owning the nearest cell centre.
    /// A free-standing cell outline around `center`, clockwise on screen, for
    /// things drawn over the keys such as a long-press popup.
    pub fn hexagon(&self, center: Point) -> Vec<Point> {
        let (w, h) = (self.cell_width / 2.0, self.cell_height / 2.0);
        let q = self.cell_height / 4.0;
        [(0.0, -h), (w, -q), (w, q), (0.0, h), (-w, q), (-w, -q)]
            .iter()
            .map(|&(dx, dy)| Point { x: center.x + dx, y: center.y + dy })
            .collect()
    }

    pub fn find(&self, label: &str) -> &Key {
        self.keys.iter().find(|key| key.label == label).expect("no such key")
    }

    pub fn key_at(&self, touch: Point) -> Option<&Key> {
        let stretch = self.cell_height / (self.cell_width * 2.0 / 3f32.sqrt());
        let distance = |cell: &Point| {
            let dx = touch.x - cell.x;
            let dy = (touch.y - cell.y) / stretch;
            dx * dx + dy * dy
        };
        self.keys
            .iter()
            .flat_map(|key| key.regions.iter().map(move |cell| (key, distance(cell))))
            .min_by(|a, b| a.1.total_cmp(&b.1))
            .map(|(key, _)| key)
    }

    /// Outline of a key: every point of the keyboard whose nearest cell
    /// centre belongs to this key, as a polygon clockwise on screen.
    ///
    /// Inside the keyboard that is the key's hexagon, or the hexagons of its
    /// run of cells joined side by side, which keeps the honeycomb's zigzag
    /// along its top and bottom. At the keyboard's edges the region runs on to
    /// the border, so the keys tile the whole rectangle with no gaps and what
    /// is drawn is exactly what `key_at` answers to.
    pub fn outline(&self, key: &Key) -> Vec<Point> {
        let stretch = self.cell_height / (self.cell_width * 2.0 / 3f32.sqrt());
        // Work in coordinates where the hexagons are regular, so that
        // perpendicular bisectors are the true cell borders.
        let to_regular = |p: &Point| Point { x: p.x, y: p.y / stretch };
        let centres: Vec<Point> =
            self.keys.iter().flat_map(|other| other.regions.iter().map(to_regular)).collect();

        let mut outline: Option<Vec<Point>> = None;
        for cell in key.regions.iter().map(to_regular) {
            let mut polygon = vec![
                Point { x: 0.0, y: 0.0 },
                Point { x: self.width, y: 0.0 },
                Point { x: self.width, y: self.height / stretch },
                Point { x: 0.0, y: self.height / stretch },
            ];
            // Against every other cell, the key's own included, so that the
            // key's cells meet along shared edges instead of overlapping.
            for other in centres.iter().filter(|other| !same(**other, cell)) {
                polygon = clip_nearer(&polygon, cell, *other);
            }
            // Clipping can leave corners in the middle of a straight edge; the
            // shared edge must be one edge on both sides for the merge to find it.
            let polygon = drop_collinear(polygon);
            outline = Some(match outline {
                None => polygon,
                Some(left) => merge_along_shared_edge(&left, &polygon),
            });
        }
        drop_collinear(outline.expect("a key has at least one cell"))
            .into_iter()
            .map(|p| Point { x: p.x, y: p.y * stretch })
            .collect()
    }
}

fn same(a: Point, b: Point) -> bool {
    (a.x - b.x).abs() < 1e-3 && (a.y - b.y).abs() < 1e-3
}

/// Joins two clockwise polygons that share one edge into their union. Cells
/// of one key sit side by side in a row, so each new cell shares exactly its
/// left edge with the key built so far.
fn merge_along_shared_edge(left: &[Point], right: &[Point]) -> Vec<Point> {
    let (n, m) = (left.len(), right.len());
    // The shared edge runs a -> b in `left` and b -> a in `right`.
    for i in 0..n {
        let (a, b) = (left[i], left[(i + 1) % n]);
        if let Some(j) = (0..m).find(|&j| same(right[j], b) && same(right[(j + 1) % m], a)) {
            let mut union = Vec::with_capacity(n + m - 2);
            // Around `left` from b back to a, then around `right` from a to b.
            union.extend((0..n).map(|k| left[(i + 1 + k) % n]));
            union.extend((1..m - 1).map(|k| right[(j + 1 + k) % m]));
            return union;
        }
    }
    panic!("cells of one key do not touch");
}

/// Removes repeated corners, then corners in the middle of a straight edge.
fn drop_collinear(mut polygon: Vec<Point>) -> Vec<Point> {
    polygon.dedup_by(|a, b| same(*a, *b));
    while polygon.len() > 1 && same(polygon[0], polygon[polygon.len() - 1]) {
        polygon.pop();
    }
    let n = polygon.len();
    (0..n)
        .filter(|&i| {
            let (o, a, b) = (polygon[(i + n - 1) % n], polygon[i], polygon[(i + 1) % n]);
            let turn = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
            turn.abs() > 1e-2
        })
        .map(|i| polygon[i])
        .collect()
}

/// Keeps the part of a convex polygon that is at least as near to `own` as to
/// `other` (Sutherland-Hodgman against their perpendicular bisector).
fn clip_nearer(polygon: &[Point], own: Point, other: Point) -> Vec<Point> {
    // p is kept when (other - own) . p <= (|other|^2 - |own|^2) / 2.
    let normal = Point { x: other.x - own.x, y: other.y - own.y };
    let limit = (other.x * other.x + other.y * other.y - own.x * own.x - own.y * own.y) / 2.0;
    let side = |p: &Point| normal.x * p.x + normal.y * p.y - limit;
    let mut clipped = Vec::with_capacity(polygon.len() + 1);
    for i in 0..polygon.len() {
        let a = polygon[i];
        let b = polygon[(i + 1) % polygon.len()];
        let (sa, sb) = (side(&a), side(&b));
        if sa <= 0.0 {
            clipped.push(a);
        }
        if (sa < 0.0) != (sb < 0.0) && sa != sb {
            let t = sa / (sa - sb);
            clipped.push(Point { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });
        }
    }
    clipped
}

fn letters(s: &str) -> impl Iterator<Item = KeySpec> + '_ {
    s.chars().map(|c| KeySpec::new(&c.to_string(), 1))
}

fn row(start_column: usize, keys: impl IntoIterator<Item = KeySpec>) -> RowSpec {
    RowSpec { start_column, keys: keys.into_iter().collect() }
}

/// German QWERTZ on a honeycomb: letter rows keep their QWERTZ order, the
/// bottom rows carry the modifiers and the space key.
pub fn qwertz() -> LayoutSpec {
    let third = std::iter::once(KeySpec::new("⇧", 2))
        .chain(letters("yxcvbnm"))
        .chain(std::iter::once(KeySpec::new("⌫", 2)));
    let bottom = [("?123", 2), (",", 1), ("space", 5), (".", 1), ("⏎", 2)]
        .map(|(label, span)| KeySpec::new(label, span));

    LayoutSpec {
        rows: vec![
            row(0, letters("qwertzuiopü")),
            row(0, letters("asdfghjklöä")),
            row(0, third),
            row(0, bottom),
        ],
    }
}

/// What a key press asks the app to do.
#[derive(Clone, Debug, PartialEq)]
pub enum Action {
    /// Insert this text at the cursor.
    Commit(String),
    Backspace,
    Enter,
    /// Nothing for the app; the keyboard itself changed (shift, layer).
    Redraw,
}

#[derive(Clone, Copy, Debug, PartialEq)]
enum Shift {
    Off,
    /// The next letter is upper case, then shift turns off.
    Once,
}

/// The alternatives a long press offers, shown as a row of cells above the
/// key; the finger slides along it and lifting commits the one under it.
#[derive(Clone, Debug, PartialEq)]
pub struct Popup {
    pub choices: Vec<String>,
    /// Centres of the choice cells, in keyboard coordinates. The row sits one
    /// row above the key, so over the top row it is above the keyboard.
    pub cells: Vec<Point>,
    pub selected: usize,
}

/// A fitted layout plus the state that decides what its keys mean.
pub struct Keyboard {
    pub layout: Layout,
    shift: Shift,
    pub popup: Option<Popup>,
}

impl Keyboard {
    pub fn new(width: f32, stretch: f32) -> Self {
        Self { layout: Layout::fit(&qwertz(), width, stretch), shift: Shift::Off, popup: None }
    }

    /// How far above the keyboard a popup can reach: one row's pitch.
    pub fn headroom(&self) -> f32 {
        self.layout.cell_height * 0.75
    }

    /// Opens the key's popup if it has alternatives; returns whether it did.
    pub fn long_press(&mut self, index: usize) -> bool {
        let label = self.layout.keys[index].label.as_str();
        let shifted = self.shift == Shift::Once;
        let choices: Vec<String> =
            alternates(label).iter().map(|c| if shifted { upper(c) } else { (*c).to_owned() }).collect();
        if choices.is_empty() {
            return false;
        }
        let layout = &self.layout;
        let center = layout.keys[index].center();
        let w = layout.cell_width;
        let n = choices.len() as f32;
        // Centred over the key, then pushed inwards so no cell leaves the sides.
        let first = (center.x - (n - 1.0) / 2.0 * w).clamp(w / 2.0, layout.width - w / 2.0 - (n - 1.0) * w);
        let y = center.y - layout.cell_height * 0.75;
        let cells: Vec<Point> = (0..choices.len()).map(|i| Point { x: first + i as f32 * w, y }).collect();
        let selected = nearest_by_x(&cells, center.x);
        self.popup = Some(Popup { choices, cells, selected });
        true
    }

    /// Follows the finger along an open popup.
    pub fn popup_move(&mut self, touch: Point) {
        if let Some(popup) = &mut self.popup {
            popup.selected = nearest_by_x(&popup.cells, touch.x);
        }
    }

    /// Lifting the finger commits the selected alternative.
    pub fn popup_release(&mut self) -> Action {
        match self.popup.take() {
            Some(popup) => {
                self.shift = Shift::Off;
                Action::Commit(popup.choices[popup.selected].clone())
            }
            None => Action::Redraw,
        }
    }

    pub fn popup_cancel(&mut self) {
        self.popup = None;
    }

    /// The text shown on a key in the current state.
    pub fn label(&self, index: usize) -> String {
        let label = &self.layout.keys[index].label;
        match self.shift {
            Shift::Once if is_letter(label) => upper(label),
            _ => label.clone(),
        }
    }

    pub fn key_at(&self, touch: Point) -> Option<usize> {
        let key = self.layout.key_at(touch)?;
        self.layout.keys.iter().position(|k| std::ptr::eq(k, key))
    }

    pub fn press(&mut self, index: usize) -> Action {
        let label = self.layout.keys[index].label.as_str();
        match label {
            "⇧" => {
                self.shift = if self.shift == Shift::Off { Shift::Once } else { Shift::Off };
                Action::Redraw
            }
            "⌫" => Action::Backspace,
            "⏎" => Action::Enter,
            "space" => Action::Commit(" ".into()),
            // The symbol layer does not exist yet.
            "?123" => Action::Redraw,
            _ => {
                let text = self.label(index);
                self.shift = Shift::Off;
                Action::Commit(text)
            }
        }
    }
}

/// Long-press alternatives for German and English, left to right.
fn alternates(label: &str) -> &'static [&'static str] {
    match label {
        "s" => &["ß"],
        "a" => &["à", "á", "â", "æ"],
        "e" => &["é", "è", "ê", "ë"],
        "i" => &["í", "ì", "î", "ï"],
        "o" => &["ó", "ò", "ô", "œ"],
        "u" => &["ú", "ù", "û"],
        "c" => &["ç"],
        "n" => &["ñ"],
        "." => &["…", "!", "?"],
        "," => &[";", ":", "'", "\""],
        _ => &[],
    }
}

/// Upper case of a single character, with the capital sharp s rather than
/// the "SS" that `to_uppercase` spells it as.
fn upper(text: &str) -> String {
    if text == "ß" { "ẞ".to_owned() } else { text.to_uppercase() }
}

fn nearest_by_x(cells: &[Point], x: f32) -> usize {
    (0..cells.len()).min_by(|&a, &b| (cells[a].x - x).abs().total_cmp(&(cells[b].x - x).abs())).unwrap_or(0)
}

fn is_letter(label: &str) -> bool {
    let mut chars = label.chars();
    matches!((chars.next(), chars.next()), (Some(c), None) if c.is_alphabetic())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn layout() -> Layout {
        Layout::fit(&qwertz(), 1008.0, 1.3)
    }

    #[test]
    fn every_row_fills_the_same_cell_count() {
        for row in &qwertz().rows {
            let cells: usize = row.keys.iter().map(|key| key.span).sum();
            assert_eq!(row.start_column + cells, 11);
        }
    }

    #[test]
    fn fits_the_given_width() {
        let layout = layout();
        let right = layout
            .keys
            .iter()
            .map(|key| layout.outline(key).iter().map(|p| p.x).fold(0.0, f32::max))
            .fold(0.0, f32::max);
        assert!((right - 1008.0).abs() < 0.01, "right edge at {right}");
    }

    #[test]
    fn each_cell_centre_hits_its_own_key() {
        let layout = layout();
        for key in &layout.keys {
            for cell in &key.cells {
                assert_eq!(layout.key_at(*cell).unwrap().label, key.label);
            }
        }
    }

    #[test]
    fn touches_inside_a_drawn_cell_hit_that_key() {
        let layout = layout();
        let h = layout.cell_height;
        let w = layout.cell_width;
        // Points just inside the six edges of a hexagon centred on the cell.
        let inside = [
            (0.0, -0.49 * h),
            (0.0, 0.49 * h),
            (-0.49 * w, 0.0),
            (0.49 * w, 0.0),
            (-0.24 * w, -0.36 * h),
            (0.24 * w, 0.36 * h),
        ];
        for key in &layout.keys {
            for cell in &key.cells {
                for (dx, dy) in inside {
                    let touch = Point { x: cell.x + dx, y: cell.y + dy };
                    // Points past the keyboard edge have no neighbour to lose to.
                    if touch.y < 0.0 || touch.y > layout.height {
                        continue;
                    }
                    assert_eq!(
                        layout.key_at(touch).unwrap().label,
                        key.label,
                        "touch {touch:?} in cell {cell:?}"
                    );
                }
            }
        }
    }

    #[test]
    fn inner_single_cell_keys_are_hexagons() {
        let layout = layout();
        let g = layout.find("g");
        assert_eq!(layout.outline(g).len(), 6);
    }

    #[test]
    fn every_outline_is_clockwise() {
        let layout = layout();
        for key in &layout.keys {
            assert!(polygon_area(&layout.outline(key)) > 0.0, "{}", key.label);
        }
    }

    #[test]
    fn keys_tile_the_whole_keyboard() {
        let layout = layout();
        let area: f32 = layout.keys.iter().map(|key| polygon_area(&layout.outline(key))).sum();
        let expected = layout.width * layout.height;
        assert!((area - expected).abs() / expected < 1e-4, "{area} vs {expected}");
    }

    #[test]
    fn single_cell_outlines_are_clockwise_and_convex() {
        let layout = layout();
        for key in layout.keys.iter().filter(|key| key.regions.len() == 1) {
            let outline = layout.outline(key);
            let n = outline.len();
            for i in 0..n {
                let (o, a, b) = (outline[i], outline[(i + 1) % n], outline[(i + 2) % n]);
                let turn = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
                assert!(turn > 0.0, "{} turns the wrong way at corner {i}", key.label);
            }
        }
    }

    /// Area of a polygon clockwise on screen (y down) is positive.
    fn polygon_area(polygon: &[Point]) -> f32 {
        let n = polygon.len();
        (0..n)
            .map(|i| {
                let (a, b) = (polygon[i], polygon[(i + 1) % n]);
                a.x * b.y - b.x * a.y
            })
            .sum::<f32>()
            / 2.0
    }

    #[test]
    fn shift_capitalises_one_letter() {
        let mut keyboard = Keyboard::new(1008.0, 1.3);
        let index = |label: &str| keyboard_index(&keyboard, label);
        let (shift, a, b) = (index("⇧"), index("a"), index("b"));
        assert_eq!(keyboard.press(shift), Action::Redraw);
        assert_eq!(keyboard.label(a), "A");
        assert_eq!(keyboard.press(a), Action::Commit("A".into()));
        assert_eq!(keyboard.press(b), Action::Commit("b".into()));
    }

    #[test]
    fn umlauts_capitalise() {
        let mut keyboard = Keyboard::new(1008.0, 1.3);
        let (shift, o) = (keyboard_index(&keyboard, "⇧"), keyboard_index(&keyboard, "ö"));
        keyboard.press(shift);
        assert_eq!(keyboard.press(o), Action::Commit("Ö".into()));
    }

    #[test]
    fn long_press_on_s_gives_sharp_s_and_capital_after_shift() {
        let mut keyboard = Keyboard::new(1008.0, 1.3);
        let (shift, s) = (keyboard_index(&keyboard, "⇧"), keyboard_index(&keyboard, "s"));
        assert!(keyboard.long_press(s));
        assert_eq!(keyboard.popup_release(), Action::Commit("ß".into()));
        keyboard.press(shift);
        assert!(keyboard.long_press(s));
        assert_eq!(keyboard.popup_release(), Action::Commit("ẞ".into()));
        assert_eq!(keyboard.label(s), "s", "shift is used up by the alternative");
    }

    #[test]
    fn popup_follows_the_finger_and_stays_on_screen() {
        let mut keyboard = Keyboard::new(1008.0, 1.3);
        let q = keyboard_index(&keyboard, "q");
        assert!(!keyboard.long_press(q), "q has no alternatives");
        let e = keyboard_index(&keyboard, "e");
        assert!(keyboard.long_press(e));
        let popup = keyboard.popup.clone().unwrap();
        let w = keyboard.layout.cell_width;
        assert!(popup.cells.iter().all(|c| c.x >= w / 2.0 - 0.01));
        assert!(popup.cells[0].y < 0.0, "a top-row popup sits in the headroom");
        assert!(popup.cells[0].y - keyboard.layout.cell_height / 2.0 >= -keyboard.headroom() - 0.01);
        keyboard.popup_move(popup.cells[3]);
        assert_eq!(keyboard.popup_release(), Action::Commit("ë".into()));
    }

    fn keyboard_index(keyboard: &Keyboard, label: &str) -> usize {
        keyboard.layout.keys.iter().position(|k| k.label == label).unwrap()
    }

    #[test]
    fn rows_interlock() {
        let layout = layout();
        let q = layout.keys.iter().find(|k| k.label == "q").unwrap().center();
        let a = layout.keys.iter().find(|k| k.label == "a").unwrap().center();
        assert!((a.x - q.x - layout.cell_width / 2.0).abs() < 0.01);
        assert!((a.y - q.y - layout.cell_height * 0.75).abs() < 0.01);
    }
}
