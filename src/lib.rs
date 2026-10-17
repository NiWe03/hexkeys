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

pub mod dictionary;
pub mod suggest;
pub mod swipe;

/// One key as authored in a layout: what it is and how many cells it spans.
#[derive(Clone, Debug, PartialEq)]
pub struct KeySpec {
    pub label: String,
    pub span: usize,
    /// A second key sharing the cell: the cell is split down the middle,
    /// `label` on the left half and this on the right.
    pub right_half: Option<String>,
}

impl KeySpec {
    pub fn new(label: &str, span: usize) -> Self {
        assert!(span >= 1, "a key spans at least one cell");
        Self { label: label.to_owned(), span, right_half: None }
    }

    /// Two keys splitting one cell, left and right.
    pub fn halves(left: &str, right: &str) -> Self {
        Self { label: left.to_owned(), span: 1, right_half: Some(right.to_owned()) }
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
    /// Keys filling the half cell a shifted row leaves free at its left and
    /// right end, instead of the row's outer keys running on into it.
    pub left_edge: Option<String>,
    pub right_edge: Option<String>,
}

/// A layout before it is fitted to a screen.
#[derive(Clone, Debug, PartialEq)]
pub struct LayoutSpec {
    pub rows: Vec<RowSpec>,
    /// Whether the first row is the one shifted by half a cell, which makes
    /// the even rows the short ones instead of the odd.
    pub first_row_shifted: bool,
    /// Digits in a row of their own above the honeycomb, narrower and lower
    /// than the cells, as the number row of a phone keyboard.
    pub numbers: Option<String>,
}

impl LayoutSpec {
    /// Width in cells, counting the half-cell shift of odd rows.
    pub fn width_in_cells(&self) -> f32 {
        self.rows
            .iter()
            .enumerate()
            .map(|(row, spec)| {
                let cells: usize = spec.keys.iter().map(|key| key.span).sum();
                (spec.start_column + cells) as f32 + self.shift(row)
            })
            .fold(0.0, f32::max)
    }

    /// How far a row is shifted right, in cells: every other row by half.
    fn shift(&self, row: usize) -> f32 {
        if (row % 2 == 1) != self.first_row_shifted { 0.5 } else { 0.0 }
    }
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
    /// Where the label goes: the middle of the key's visible area.
    pub label_at: Point,
    /// `cells` plus, for a key at the end of a row that stops half a cell
    /// short of the keyboard's edge, one more centre beyond it. The key's
    /// area then runs on to the edge, which keeps the keyboard's sides
    /// straight instead of leaving the honeycomb's half-cell notches.
    pub regions: Vec<Point>,
    /// Which half of its cell the key has, when it shares the cell.
    pub half: Option<Half>,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Half {
    Left,
    Right,
}

impl Key {
    /// Centre of the whole key, where its label goes.
    pub fn center(&self) -> Point {
        self.label_at
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
    /// Where the number row ends and the honeycomb starts; 0 without one.
    pub numbers_bottom: f32,
}

/// The row index of number row keys, which stand outside the honeycomb.
const NUMBER_ROW: usize = usize::MAX;

/// Height of the number row in cell heights.
const NUMBER_BAND: f32 = 1.0;

/// The share of the number row's height over which its two rows interlock.
const NUMBER_INTERLOCK: f32 = 0.4;

impl Layout {
    /// Fits `spec` to `width` pixels. `stretch` scales cell height relative to
    /// a regular hexagon (1.0 = regular).
    pub fn fit(spec: &LayoutSpec, width: f32, stretch: f32) -> Self {
        let cell_width = width / spec.width_in_cells();
        let cell_height = cell_width * 2.0 / 3f32.sqrt() * stretch;
        let row_pitch = cell_height * 0.75;
        let numbers_bottom = if spec.numbers.is_some() { cell_height * NUMBER_BAND } else { 0.0 };
        // Under a number row the honeycomb starts halfway between the top
        // row's points and its shoulders, so those keys are barely taller
        // than the rest.
        let top = if spec.numbers.is_some() { numbers_bottom - cell_height / 8.0 } else { 0.0 };

        let mut keys = Vec::new();
        for (row, row_spec) in spec.rows.iter().enumerate() {
            let y = top + cell_height / 2.0 + row as f32 * row_pitch;
            let mut column = row_spec.start_column as f32 + spec.shift(row);
            for key_spec in &row_spec.keys {
                let cells: Vec<Point> = (0..key_spec.span)
                    .map(|i| Point { x: (column + i as f32 + 0.5) * cell_width, y })
                    .collect();
                let label_at = Point { x: (column + key_spec.span as f32 / 2.0) * cell_width, y };
                match &key_spec.right_half {
                    None => keys.push(Key {
                        label: key_spec.label.clone(),
                        row,
                        label_at,
                        regions: cells.clone(),
                        cells,
                        half: None,
                    }),
                    Some(right) => {
                        for (label, half, dx) in
                            [(&key_spec.label, Half::Left, -0.25), (right, Half::Right, 0.25)]
                        {
                            keys.push(Key {
                                label: label.clone(),
                                row,
                                label_at: Point { x: label_at.x + dx * cell_width, y },
                                regions: cells.clone(),
                                cells: cells.clone(),
                                half: Some(half),
                            });
                        }
                    }
                }
                column += key_spec.span as f32;
            }
        }

        for (row, row_spec) in spec.rows.iter().enumerate() {
            let y = top + cell_height / 2.0 + row as f32 * row_pitch;
            let left = keys.iter().position(|key| key.row == row);
            let right = keys.iter().rposition(|key| key.row == row);
            let (Some(left), Some(right)) = (left, right) else { continue };
            let left_x = keys[left].cells[0].x;
            let right_x = keys[right].cells[keys[right].cells.len() - 1].x;
            let left_gap = left_x - cell_width / 2.0 > 0.01;
            let right_gap = right_x + cell_width / 2.0 < width - 0.01;
            // A gap at a row's end goes to an edge key when the row has one,
            // otherwise the outer key runs on into it. Either way the
            // keyboard's sides stay straight.
            if left_gap {
                let centre = Point { x: left_x - cell_width, y };
                match &row_spec.left_edge {
                    Some(label) => keys.push(Key {
                        label: label.clone(),
                        row,
                        label_at: Point { x: (left_x - cell_width / 2.0) / 2.0, y },
                        cells: vec![centre],
                        regions: vec![centre],
                        half: None,
                    }),
                    None => {
                        // The label moves to the middle of what the key now covers.
                        // The label stays on the key's hexagon, as on every other key.
                        keys[left].regions.insert(0, centre);
                    }
                }
            }
            if right_gap {
                let centre = Point { x: right_x + cell_width, y };
                match &row_spec.right_edge {
                    Some(label) => keys.push(Key {
                        label: label.clone(),
                        row,
                        label_at: Point { x: (right_x + cell_width / 2.0 + width) / 2.0, y },
                        cells: vec![centre],
                        regions: vec![centre],
                        half: None,
                    }),
                    None => {
                        keys[right].regions.push(centre);
                    }
                }
            }
        }

        if let Some(digits) = &spec.numbers {
            // Digits alternate between an upper and a lower row, each key
            // two steps wide: the upper ones point down between the lower
            // ones, which point up between them, over the middle of the band.
            let step = width / digits.chars().count() as f32;
            for (i, digit) in digits.chars().enumerate() {
                let y = numbers_bottom * if i % 2 == 0 { 0.25 } else { 0.75 };
                let centre = Point { x: (i as f32 + 0.5) * step, y };
                keys.push(Key {
                    label: digit.to_string(),
                    row: NUMBER_ROW,
                    label_at: centre,
                    cells: vec![centre],
                    regions: vec![centre],
                    half: None,
                });
            }
        }

        // A top row cut at its shoulders shows only its lower part.
        if spec.numbers.is_some() {
            for key in keys.iter_mut().filter(|key| key.row == 0) {
                key.label_at.y += cell_height / 16.0;
            }
        }

        let height = top + cell_height + (spec.rows.len().saturating_sub(1)) as f32 * row_pitch;
        Self { keys, cell_width, cell_height, width, height, numbers_bottom }
    }

    /// Grows the layout to `height` by moving its keys down, so layers with
    /// fewer rows keep their bottom row where the others have it. The top
    /// row's keys run up into the space above, which keeps the tiling whole.
    fn lower_to(&mut self, height: f32) {
        let by = height - self.height;
        for key in &mut self.keys {
            for cell in key.cells.iter_mut().chain(key.regions.iter_mut()) {
                cell.y += by;
            }
            // The top row's labels sit in the middle of what those keys now cover.
            key.label_at.y += if key.row == 0 { by / 2.0 } else { by };
        }
        self.height = height;
        if self.numbers_bottom > 0.0 {
            self.numbers_bottom += by;
        }
    }

    /// How much heights count less than widths when the number row is split
    /// between its digits. With rows at a quarter and three quarters of the
    /// band, the keys of one row then reach between those of the other over
    /// `NUMBER_INTERLOCK` of the band's height.
    fn number_squash(&self) -> f32 {
        let digits = self.keys.iter().filter(|key| key.row == NUMBER_ROW).count().max(1);
        let step = self.width / digits as f32;
        (NUMBER_INTERLOCK / 2.0).sqrt() * self.numbers_bottom / step
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

    /// What typing `typed` where the word has `meant` costs: nothing for the
    /// same letter, less for a key touching it than for one farther away,
    /// since a finger slips onto a neighbour far more often.
    pub fn substitution(&self, typed: char, meant: char) -> f32 {
        if typed == meant {
            return 0.0;
        }
        let centre = |c: char| {
            self.keys.iter().find(|key| key.label.chars().eq(std::iter::once(c))).map(|key| key.cells[0])
        };
        match (centre(typed), centre(meant)) {
            (Some(a), Some(b)) => {
                let stretch = self.cell_height / (self.cell_width * 2.0 / 3f32.sqrt());
                let apart = (a.x - b.x).hypot((a.y - b.y) / stretch) / self.cell_width;
                if apart < 1.1 { 0.6 } else { 1.0 }
            }
            _ => 1.0,
        }
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
        // Number keys own the band above the honeycomb, split by x alone.
        let in_band = touch.y < self.numbers_bottom;
        let squash = self.number_squash();
        let distance = |cell: &Point| {
            if in_band {
                let (dx, dy) = (touch.x - cell.x, (touch.y - cell.y) / squash);
                dx * dx + dy * dy
            } else {
                distance(cell)
            }
        };
        let (key, cell) = self
            .keys
            .iter()
            .filter(|key| (key.row == NUMBER_ROW) == in_band)
            .flat_map(|key| key.regions.iter().map(move |cell| (key, *cell)))
            .min_by(|a, b| distance(&a.1).total_cmp(&distance(&b.1)))?;
        if key.half.is_none() {
            return Some(key);
        }
        // A split cell: the half the touch is on.
        let side = if touch.x < cell.x { Half::Left } else { Half::Right };
        self.keys.iter().find(|other| other.half == Some(side) && other.regions.iter().any(|c| same(*c, cell)))
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
        if key.row == NUMBER_ROW {
            // The band split between the digits by nearness, measured where
            // their honeycomb is not squashed.
            let squash = self.number_squash();
            let unsquash = |p: Point| Point { x: p.x, y: p.y / squash };
            let own = unsquash(key.cells[0]);
            let bottom = self.numbers_bottom / squash;
            let mut polygon = vec![
                Point { x: 0.0, y: 0.0 },
                Point { x: self.width, y: 0.0 },
                Point { x: self.width, y: bottom },
                Point { x: 0.0, y: bottom },
            ];
            for other in self.keys.iter().filter(|other| other.row == NUMBER_ROW) {
                let other = unsquash(other.cells[0]);
                if !same(other, own) {
                    polygon = clip_nearer(&polygon, own, other);
                }
            }
            return drop_collinear(polygon).into_iter().map(|p| Point { x: p.x, y: p.y * squash }).collect();
        }
        let stretch = self.cell_height / (self.cell_width * 2.0 / 3f32.sqrt());
        // Work in coordinates where the hexagons are regular, so that
        // perpendicular bisectors are the true cell borders.
        let to_regular = |p: &Point| Point { x: p.x, y: p.y / stretch };
        let centres: Vec<Point> =
            self.keys
                .iter()
                .filter(|other| other.row != NUMBER_ROW)
                .flat_map(|other| other.regions.iter().map(to_regular))
                .collect();

        let mut outline: Option<Vec<Point>> = None;
        for cell in key.regions.iter().map(to_regular) {
            let mut polygon = vec![
                Point { x: 0.0, y: self.numbers_bottom / stretch },
                Point { x: self.width, y: self.numbers_bottom / stretch },
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
            let mut polygon = drop_collinear(polygon);
            // A shared cell is cut down the middle.
            if let Some(half) = key.half {
                let (left, right) = (Point { x: cell.x - 1.0, y: cell.y }, Point { x: cell.x + 1.0, y: cell.y });
                polygon = match half {
                    Half::Left => clip_nearer(&polygon, left, right),
                    Half::Right => clip_nearer(&polygon, right, left),
                };
                polygon = drop_collinear(polygon);
            }
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
    RowSpec { start_column, keys: keys.into_iter().collect(), left_edge: None, right_edge: None }
}

fn keys(labels: &[(&str, usize)]) -> Vec<KeySpec> {
    labels.iter().map(|&(label, span)| KeySpec::new(label, span)).collect()
}

/// The keyboard: the largest keys that fit the letters in four rows, seven
/// and six to a row, with nothing but letters among them.
///
/// QWERTZ has three letter rows and keys this size need four, so letters
/// cannot all keep their row. Each sits in the cell nearest to where it is on
/// a QWERTZ phone keyboard (an optimal assignment over squared distance, with
/// vertical distance weighted double), which keeps left and right and each
/// letter's neighbourhood. Shift and backspace fill the half cells at the ends
/// of the last letter row, beside y and m as on QWERTZ. There are no umlauts;
/// ß is a long-press of s.
pub fn honeycomb() -> LayoutSpec {
    let mut last_letters = row(0, letters("yxcvnm"));
    last_letters.left_edge = Some("⇧".into());
    last_letters.right_edge = Some("⌫".into());
    LayoutSpec {
        rows: vec![
            row(0, letters("qerzuip")),
            row(0, letters("wdthjo")),
            row(0, letters("asfgbkl")),
            last_letters,
            bottom_row("?123"),
        ],
        first_row_shifted: false,
        numbers: Some("1234567890".into()),
    }
}

/// [`space_middle`] with space and the marks at the bottom. Its top two letter
/// rows are the reference layout's; the 13 letters left are exactly QWERTZ's home row
/// s d f h j k and bottom row y x c v b n m, which fill the next two rows
/// in QWERTZ order, f h and y in the cells the reference layout has them in. Keeping
/// the reference layout's y s d n m j k instead leaves no room for b left of n. The
/// bottom row reads . space ? as the reference layout's middle row does, and ?123 and
/// enter stay in their cells.
pub fn space_bottom() -> LayoutSpec {
    LayoutSpec {
        rows: vec![
            row(0, letters("wetzio")),
            row(0, letters("qargulp")),
            row(0, letters("sdfhjk")),
            row(0, letters("yxcvbnm")),
            row(0, keys(&[(".", 1), ("space", 2), ("?", 1), ("?123", 1), ("⏎", 1)])),
        ],
        first_row_shifted: true,
        numbers: Some("1234567890".into()),
    }
}

/// `spec` with shift and backspace in the half cells at the ends of its
/// third row, where QWERTZ has them beside its last letter row. Both
/// layouts have capitals and deleting as gestures; the keys are for those
/// who want them as well.
pub fn with_shift_and_backspace(mut spec: LayoutSpec) -> LayoutSpec {
    let row = &mut spec.rows[2];
    row.left_edge = Some("⇧".into());
    row.right_edge = Some("⌫".into());
    spec
}

/// the reference layout's arrangement, with its two spaces between the letters, plus
/// a number row along the top. A gesture types capitals and a slide in from
/// the edge deletes. It is a registered design and stays out of any release.
pub fn space_middle() -> LayoutSpec {
    LayoutSpec {
        rows: vec![
            row(0, letters("wetzio")),
            row(0, letters("qargulp")),
            row(0, keys(&[(".", 1), ("space", 1), ("f", 1), ("h", 1), ("space", 1), ("?", 1)])),
            row(0, letters("ysdnmjk")),
            row(0, keys(&[("x", 1), ("c", 1), ("v", 1), ("b", 1), ("?123", 1), ("⏎", 1)])),
        ],
        first_row_shifted: true,
        numbers: Some("1234567890".into()),
    }
}

/// The bottom row every layer shares, with the key back to letters or on to
/// symbols at its left.
fn bottom_row(switch: &str) -> RowSpec {
    row(0, keys(&[(switch, 1), (",", 1), ("space", 3), (".", 1), ("⏎", 1)]))
}

/// Symbols for the first symbol page, most used first.
const SYMBOLS: &str = "@#€&-+()/*\"':;!_%=$<>[]{}\\|~^°";

/// Rarer symbols for the second page.
const MORE_SYMBOLS: &str = "§¢£¥₹©®™✓•¶∆π√÷×±≈≠≤≥‰¿¡«»„“”–—…`";

/// The key that turns to the second symbol page, and on it back to the first.
const MORE: &str = "=\\<";

/// A symbol page shaped exactly like `letters`: the number row, space,
/// enter, backspace and the punctuation keys stay where they are, the key
/// to the symbols turns into the key back to the letters in the same
/// place, and the letter cells hold symbols not already on the letters,
/// in reading order. Shift's place, or the last letter cell where there is
/// no shift, turns to the other page. `page` 0 is the first page, 1 the rarer.
pub fn symbol_layer(letters: &LayoutSpec, page: usize) -> LayoutSpec {
    let mut spec = letters.clone();
    let on_letters: Vec<String> = spec
        .rows
        .iter()
        .flat_map(|row| row.keys.iter().map(|key| key.label.clone()))
        .filter(|label| !is_letter(label))
        .collect();
    let pool = if page == 0 { SYMBOLS } else { MORE_SYMBOLS };
    let mut symbols = pool.chars().map(String::from).filter(|symbol| !on_letters.contains(symbol));
    let has_shift = spec.rows.iter().any(|row| row.left_edge.as_deref() == Some("⇧"));
    let turn = if page == 0 { MORE } else { "?123" };
    let last_letter = spec
        .rows
        .iter()
        .enumerate()
        .flat_map(|(r, row)| row.keys.iter().enumerate().filter(|(_, key)| is_letter(&key.label)).map(move |(k, _)| (r, k)))
        .last();
    for (r, row) in spec.rows.iter_mut().enumerate() {
        for (k, key) in row.keys.iter_mut().enumerate() {
            if key.label == "?123" {
                key.label = "ABC".into();
            } else if is_letter(&key.label) {
                key.label = if !has_shift && Some((r, k)) == last_letter {
                    turn.into()
                } else {
                    symbols.next().unwrap_or_default()
                };
            }
        }
        if row.left_edge.as_deref() == Some("⇧") {
            row.left_edge = Some(turn.into());
        }
    }
    spec
}

/// What a key press asks the app to do.
#[derive(Clone, Debug, PartialEq)]
pub enum Action {
    /// Insert this text at the cursor.
    Commit(String),
    /// Replace `old`, just typed before the cursor, with `new`.
    Replace { old: String, new: String },
    Backspace,
    Enter,
    /// Enter held: the field's own action, such as send or search.
    EnterAction,
    /// A sideways flick on space: the next language, or the previous one
    /// when `forward` is false.
    Language { forward: bool },
    /// Space held and the finger moved: the cursor moves `dx` characters
    /// right (left when negative) and `dy` lines down.
    Cursor { dx: i32, dy: i32 },
    /// Nothing for the app; the keyboard itself changed (shift, layer).
    Redraw,
    /// Nothing changed.
    Nothing,
    /// A path was drawn across the letters; [`Keyboard::swiped`] has it for
    /// the decoder.
    Swipe,
    /// A delete swipe now has `pieces` deleted before the cursor, counted
    /// from where it started: more delete, fewer put back what was deleted
    /// last. Pieces added since the last action are whole words when
    /// `words` is set, otherwise characters. `started` marks the first
    /// action of a delete swipe.
    Delete { pieces: u32, words: bool, started: bool },
}

#[derive(Clone, Copy, Debug, PartialEq)]
enum Shift {
    Off,
    /// The next letter is upper case, then shift turns off.
    Once,
    /// Every letter is upper case until shift is tapped again: shift held.
    Locked,
}

/// The alternatives a long press offers, shown as a row of cells above the
/// key; the finger slides along it and lifting commits the one under it.
#[derive(Clone, Debug, PartialEq)]
pub struct Popup {
    pub choices: Vec<String>,
    /// Centres of the choice cells, in keyboard coordinates. The row sits one
    /// row above the key, so over the top row it is above the keyboard.
    pub cells: Vec<Point>,
    /// Width of a choice's hexagon, smaller than a key's.
    pub cell_width: f32,
    /// The choice lifting the finger takes, if any.
    pub selected: Option<usize>,
    /// Where the finger was when the popup opened; the selection follows
    /// it only once it has moved away from there.
    pub origin: Point,
    /// The finger has moved away from `origin`.
    pub moved: bool,
    /// A capital typed already by holding its letter, which a chosen
    /// accent replaces; lifting without choosing one keeps it.
    pub replaces: Option<String>,
}

/// How big a popup's hexagons are next to a key's, as on other keyboards
/// whose long-press choices are smaller than the keys.
const POPUP_SCALE: f32 = 0.72;

/// Below this distance from where it went down, a finger is tapping, in key
/// widths. Above it the touch is a flick, up to one key width; anything
/// longer is a path over several keys, which belongs to swipe typing.
const FLICK: f32 = 0.35;

/// How far a finger may drift and still count as holding still, in key widths.
const STILL: f32 = 0.25;

/// How near the delete edge a touch has to start to be a delete swipe, in
/// key widths: a finger sliding in from the edge lands close to it. In the
/// phone's log most strokes leftwards from within 1.1 key widths of the
/// right edge were deletes started a little too far in, decoded as swipes.
const EDGE: f32 = 1.1;

/// How far a delete swipe may bend away from the straight line between
/// where it started and where the finger is, before it counts as swipe
/// typing after all, in key widths: a swiped word turns at its letters,
/// a delete slide runs straight, level or slanted.
const DELETE_STRAY: f32 = 0.5;

/// How long after it starts a delete slide can still turn into swipe
/// typing, in ms. A swipe starting at the edge bends at once; a delete held
/// for seconds and moved back and forth drifts too, which must not count.
const DELETE_DECIDE_MS: u64 = 400;

/// How near the far side of the keyboard a delete swipe has to reach to
/// delete whole words, in key widths from that edge: holding anywhere else
/// is choosing how many characters to take.
const FAR_SIDE: f32 = 1.0;

/// How long a delete swipe rests on the far side before its first whole
/// word goes, in ms.
const FAR_HOLD_MS: u64 = 250;

/// Pause between characters while backspace is held, in ms.
const BACKSPACE_REPEAT_MS: u64 = 60;

/// How far a delete swipe moves inwards for its first character, in key
/// widths. Further characters come faster: n characters take
/// `DELETE_FIRST * n^(2/3)`, so a short slide deletes a letter or two with
/// care and a long one clears a lot.
const DELETE_FIRST: f32 = 0.7;

/// How much farther back a delete swipe has to move before it puts a
/// character back, in key widths, so a finger easing off does not undo
/// more than it means to.
const RESTORE_SLACK: f32 = 0.25;

/// How long a delete swipe has to have lasted for its distance to count in
/// full, in ms. A quicker stroke counts for less, so a flick takes a
/// letter where a slow slide of the same length takes several.
const DELETE_RAMP_MS: f32 = 350.0;

/// Characters a delete swipe deletes that has moved `inwards` key widths
/// in `elapsed` ms.
fn delete_count(inwards: f32, elapsed: u64) -> u32 {
    let counted = inwards * (elapsed as f32 / DELETE_RAMP_MS).min(1.0);
    if counted <= 0.0 { 0 } else { (counted / DELETE_FIRST).powf(1.5) as u32 }
}

/// Strokes quicker than this, in ms, that run straight are gestures even
/// when longer than a key: a flick of the thumb. Swipe typing is slower;
/// in FUTO's swipe dataset 7% of two-letter words and almost no longer
/// ones are swiped this fast.
const FAST_MS: u64 = 150;

/// How far a quick stroke may bend and still count as straight, in key widths.
const FAST_BEND: f32 = 0.35;

/// The longest a quick stroke can be, in key widths.
const FAST_LONGEST: f32 = 3.5;

/// Pause between whole words deleted while a delete swipe rests on the far side, in ms.
const WORD_REPEAT_MS: u64 = 300;

/// The shortest pause between whole words, which the pause shrinks to.
const WORD_REPEAT_FASTEST: u64 = 60;

/// The gesture that types a capital letter.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Capital {
    /// A short flick up from the letter.
    FlickUp,
    /// Holding the letter: the capital is typed the moment the touch counts
    /// as a hold, and the letter's accents open with it chosen, so sliding
    /// to one of them swaps it in.
    Hold,
}

/// How long a letter is held before it types its capital, in ms. Taps in
/// the phone's gesture log last 63 ms at the median and 100 ms at the
/// 99.5th percentile, the longest 130 ms.
const CAPITAL_HOLD_MS: u64 = 200;

/// How gestures are set up, from the settings.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Gestures {
    /// Delete swipes come in from the left edge instead of the right.
    pub delete_from_left: bool,
    pub capital: Capital,
}

impl Default for Gestures {
    fn default() -> Self {
        Self { delete_from_left: false, capital: Capital::FlickUp }
    }
}

/// What a touch amounts to so far.
#[derive(Clone, Copy, Debug, PartialEq)]
enum Gesture {
    Tap,
    /// A short flick up.
    Up,
    /// A short flick down.
    Down,
    /// A flick another way, or one pulled back to where it started: nothing.
    Aborted,
    /// Movement longer than a key: a swipe-typing path.
    Path,
    /// A slide in from the delete edge: deleting as it goes.
    Delete,
    /// A quick straight stroke towards the delete edge's opposite side,
    /// starting in the third of the keyboard nearest the delete edge:
    /// deletes when the finger lifts.
    QuickDelete,
}

/// A delete swipe under way.
#[derive(Clone, Copy, Debug, PartialEq)]
struct Deleting {
    /// Characters deleted by how far the finger moved in.
    characters: u32,
    /// Whole words deleted while holding still.
    words: u32,
}

impl Deleting {
    fn pieces(&self) -> u32 {
        self.characters + self.words
    }
}

/// One finger on the keyboard. Times are in milliseconds on any clock that
/// only moves forward, as long as every call uses the same one.
#[derive(Clone, Debug)]
struct Touch {
    key: usize,
    start: Point,
    at: Point,
    /// The farthest the finger has been from `start`, and where that was.
    extent: f32,
    farthest: Point,
    /// Where and since when the finger has held still.
    anchor: Point,
    anchor_time: u64,
    /// When the next word is due while a delete swipe holds still.
    repeat_at: Option<u64>,
    /// When the finger went down and when it last moved.
    start_time: u64,
    last_time: u64,
    /// Every point the finger has been, for swipe typing.
    path: Vec<Point>,
    /// Went down at the delete edge, so sliding inwards deletes.
    at_edge: bool,
    deleting: Option<Deleting>,
    /// Held long enough to have acted already: typed its letter's capital,
    /// or enter's action; lifting then adds nothing.
    capital_typed: bool,
    /// Space held: the keyboard is a touchpad for the cursor.
    cursor: Option<Cursor>,
}

/// The cursor being moved by a finger after holding space: where the finger
/// was when it started, and how far the cursor has moved since.
#[derive(Clone, Copy, Debug)]
struct Cursor {
    origin: Point,
    dx: i32,
    dy: i32,
}

/// How far the finger moves for the cursor to move one character, in key
/// widths, and one line, in rows.
const CURSOR_STEP: f32 = 0.3;

/// A finished swipe across the letters.
#[derive(Clone, Debug, PartialEq)]
pub struct Swiped {
    pub path: Vec<Point>,
    /// Shift was on, so the word starts with a capital.
    pub capital: bool,
}

fn distance(a: Point, b: Point) -> f32 {
    (a.x - b.x).hypot(a.y - b.y)
}

/// How far a path strays from the straight line between its ends at most.
fn bend(path: &[Point]) -> f32 {
    let (Some(&a), Some(&b)) = (path.first(), path.last()) else { return 0.0 };
    let length = distance(a, b);
    if length == 0.0 {
        return path.iter().map(|p| distance(*p, a)).fold(0.0, f32::max);
    }
    path.iter()
        .map(|p| ((b.x - a.x) * (a.y - p.y) - (a.x - p.x) * (b.y - a.y)).abs() / length)
        .fold(0.0, f32::max)
}

/// A fitted layout plus the state that decides what its keys mean.
pub struct Keyboard {
    /// The layer on screen now.
    pub layout: Layout,
    /// Letters, symbols and more symbols, fitted once.
    layers: [Layout; 3],
    shift: Shift,
    pub popup: Option<Popup>,
    touch: Option<Touch>,
    /// How long a finger holds still before a long press, in ms.
    long_press_ms: u64,
    /// Which of `layers` is on screen.
    layer: usize,
    /// The last swipe, until the next one.
    pub swiped: Option<Swiped>,
    pub gestures: Gestures,
    /// Accented letters by the plain letter whose key offers them when held,
    /// from the languages in use.
    pub accents: std::collections::HashMap<char, Vec<char>>,
}

impl Keyboard {
    /// A keyboard with `letters` as its letter layer.
    pub fn new(letters: LayoutSpec, width: f32, stretch: f32, long_press_ms: u64) -> Self {
        let pages = [symbol_layer(&letters, 0), symbol_layer(&letters, 1)];
        let mut layers = [letters, pages[0].clone(), pages[1].clone()].map(|spec| Layout::fit(&spec, width, stretch));
        let height = layers.iter().map(|layer| layer.height).fold(0.0, f32::max);
        for layer in &mut layers {
            layer.lower_to(height);
        }
        Self {
            layout: layers[0].clone(),
            layers,
            shift: Shift::Off,
            popup: None,
            touch: None,
            long_press_ms,
            layer: 0,
            swiped: None,
            gestures: Gestures::default(),
            accents: Default::default(),
        }
    }

    /// How far inwards from the delete edge a point is, relative to where
    /// the touch started.
    fn inwards(&self, touch: &Touch, at: Point) -> f32 {
        let dx = at.x - touch.start.x;
        if self.gestures.delete_from_left { dx } else { -dx }
    }

    fn gesture(&self, touch: &Touch) -> Gesture {
        if touch.deleting.is_some() {
            return Gesture::Delete;
        }
        let w = self.layout.cell_width;
        if touch.extent >= w {
            return self.quick(touch).unwrap_or(Gesture::Path);
        }
        if touch.extent < FLICK * w {
            return Gesture::Tap;
        }
        let (dx, dy) = (touch.at.x - touch.start.x, touch.at.y - touch.start.y);
        if dx.hypot(dy) < FLICK * w {
            // Pulled back: nothing.
            Gesture::Aborted
        } else if dx.abs() >= dy.abs() {
            Gesture::Aborted
        } else if dy < 0.0 {
            Gesture::Up
        } else {
            Gesture::Down
        }
    }

    /// A quick straight stroke longer than a flick, read as the flick or
    /// delete it is; None for anything else, which is swipe typing.
    fn quick(&self, touch: &Touch) -> Option<Gesture> {
        let w = self.layout.cell_width;
        if touch.last_time - touch.start_time > FAST_MS || touch.extent > FAST_LONGEST * w || bend(&touch.path) > FAST_BEND * w {
            return None;
        }
        let (dx, dy) = (touch.at.x - touch.start.x, touch.at.y - touch.start.y);
        let third = self.layout.width / 3.0;
        let near_edge = if self.gestures.delete_from_left { touch.start.x < third } else { touch.start.x > 2.0 * third };
        if dy.abs() > 1.4 * dx.abs() {
            Some(if dy < 0.0 { Gesture::Up } else { Gesture::Down })
        } else if dx.abs() > 1.4 * dy.abs() && near_edge && self.inwards(touch, touch.at) > 0.0 {
            Some(Gesture::QuickDelete)
        } else {
            None
        }
    }

    /// Whether a gesture on a letter is the one set up to type its capital.
    fn is_capital(&self, gesture: Gesture) -> bool {
        matches!(
            (self.gestures.capital, gesture),
            (Capital::FlickUp, Gesture::Up)
        )
    }

    /// Whether a delete swipe has reached the far side of the keyboard,
    /// where resting deletes whole words.
    fn on_far_side(&self, touch: &Touch) -> bool {
        let reach = FAR_SIDE * self.layout.cell_width;
        if self.gestures.delete_from_left { touch.at.x > self.layout.width - reach } else { touch.at.x < reach }
    }

    /// Shift as it stands: 0 off, 1 for the next letter, 2 locked.
    pub fn shift_state(&self) -> u8 {
        match self.shift {
            Shift::Off => 0,
            Shift::Once => 1,
            Shift::Locked => 2,
        }
    }

    /// Shift goes off after a letter unless it is locked.
    fn letter_typed(&mut self) {
        if self.shift == Shift::Once {
            self.shift = Shift::Off;
        }
    }

    /// Whether space is held and the keyboard moves the cursor.
    pub fn moving_cursor(&self) -> bool {
        self.touch.as_ref().is_some_and(|touch| touch.cursor.is_some())
    }

    /// While a delete slide is under way, where it started and where the
    /// finger is now, along the keyboard's width, and whether it has reached
    /// the far side, where it deletes whole words.
    pub fn deleting(&self) -> Option<(f32, f32, bool)> {
        self.touch
            .as_ref()
            .filter(|touch| touch.deleting.is_some())
            .map(|touch| (touch.start.x, touch.at.x, self.on_far_side(touch)))
    }

    /// The key the finger went down on, which every gesture belongs to;
    /// none while swiping or deleting, which do not belong to a key.
    pub fn pressed(&self) -> Option<usize> {
        self.touch
            .as_ref()
            .filter(|touch| self.trail().is_empty() && touch.deleting.is_none())
            .map(|touch| touch.key)
    }

    pub fn touch_down(&mut self, at: Point, time: u64) {
        self.popup = None;
        let edge = EDGE * self.layout.cell_width;
        let at_edge = if self.gestures.delete_from_left { at.x < edge } else { at.x > self.layout.width - edge };
        self.touch = self.key_at(at).map(|key| Touch {
            key,
            start: at,
            at,
            extent: 0.0,
            farthest: at,
            anchor: at,
            anchor_time: time,
            repeat_at: None,
            start_time: time,
            last_time: time,
            path: vec![at],
            at_edge,
            deleting: None,
            capital_typed: false,
            cursor: None,
        });
    }

    /// Follows the finger. Only a delete swipe acts while the finger moves;
    /// everything else waits for it to lift.
    pub fn touch_move(&mut self, at: Point, time: u64) -> Action {
        if self.popup.is_some() {
            self.popup_move(at);
            return Action::Nothing;
        }
        let w = self.layout.cell_width;
        let Some(mut touch) = self.touch.clone() else { return Action::Nothing };
        touch.at = at;
        touch.last_time = time;
        if let Some(cursor) = &mut touch.cursor {
            let dx = ((at.x - cursor.origin.x) / (CURSOR_STEP * w)).trunc() as i32;
            let dy = ((at.y - cursor.origin.y) / (self.layout.cell_height * 0.75)).trunc() as i32;
            let moved = Action::Cursor { dx: dx - cursor.dx, dy: dy - cursor.dy };
            let changed = dx != cursor.dx || dy != cursor.dy;
            (cursor.dx, cursor.dy) = (dx, dy);
            self.touch = Some(touch);
            return if changed { moved } else { Action::Nothing };
        }
        touch.path.push(at);
        let from_start = distance(at, touch.start);
        if from_start > touch.extent {
            touch.extent = from_start;
            touch.farthest = at;
        }
        if distance(at, touch.anchor) > STILL * w {
            touch.anchor = at;
            touch.anchor_time = time;
            touch.repeat_at = None;
        }
        let inwards = self.inwards(&touch, at);
        let mut action = Action::Nothing;
        let mut started = false;
        if touch.deleting.is_none()
            && touch.at_edge
            && inwards >= FLICK * w
            && (at.x - touch.start.x).abs() >= (at.y - touch.start.y).abs()
        {
            touch.deleting = Some(Deleting { characters: 0, words: 0 });
            started = true;
        }
        // Bent early on: swipe typing after all, with everything put back. Later
        // the slide stays a delete however the finger wanders back and forth.
        if touch.deleting.is_some() && time - touch.start_time < DELETE_DECIDE_MS && bend(&touch.path) > DELETE_STRAY * w {
            touch.deleting = None;
            touch.at_edge = false;
            self.touch = Some(touch);
            return Action::Delete { pieces: 0, words: false, started: false };
        }
        if let Some(deleting) = &mut touch.deleting {
            let before = deleting.pieces();
            let elapsed = time - touch.start_time;
            let forwards = delete_count(inwards / w, elapsed);
            let back = delete_count(inwards / w + RESTORE_SLACK, elapsed);
            if forwards > deleting.characters {
                deleting.characters = forwards;
            } else if back < deleting.characters {
                deleting.characters = back;
            }
            if started || deleting.pieces() != before {
                action = Action::Delete { pieces: deleting.pieces(), words: false, started };
            }
        }
        self.touch = Some(touch);
        action
    }

    pub fn touch_up(&mut self, at: Point, time: u64) -> Action {
        if self.popup.is_some() {
            self.touch = None;
            return self.popup_release();
        }
        let moved = self.touch_move(at, time);
        let Some(touch) = self.touch.take() else { return moved };
        if touch.capital_typed {
            return Action::Redraw;
        }
        let gesture = self.gesture(&touch);
        let label = self.layout.keys[touch.key].label.clone();
        match gesture {
            // A flick in from the edge, too quick to have counted a letter yet, takes one.
            Gesture::Delete
                if touch.deleting.is_some_and(|deleting| deleting.pieces() == 0)
                    && self.inwards(&touch, touch.at) >= DELETE_FIRST * self.layout.cell_width =>
            {
                Action::Delete { pieces: 1, words: false, started: false }
            }
            Gesture::Delete => moved,
            Gesture::QuickDelete => {
                let elapsed = touch.last_time - touch.start_time;
                let pieces = delete_count(self.inwards(&touch, touch.at) / self.layout.cell_width, elapsed).max(1);
                Action::Delete { pieces, words: false, started: true }
            }
            Gesture::Tap => self.press(touch.key),
            // A flick sideways on space steps through the languages.
            Gesture::Aborted if label == "space" && (touch.at.x - touch.start.x).abs() > (touch.at.y - touch.start.y).abs() => {
                Action::Language { forward: touch.at.x > touch.start.x }
            }
            // A flick up on enter, towards the action drawn above its arrow, does the field's action.
            Gesture::Up if label == "⏎" => Action::EnterAction,
            _ if is_letter(&label) && self.is_capital(gesture) => {
                self.letter_typed();
                Action::Commit(upper(&label))
            }
            // On punctuation the capital gesture types the key's first alternative.
            _ if self.is_capital(gesture) && !alternates(&label).is_empty() => Action::Commit(alternates(&label)[0].to_owned()),
            Gesture::Path if self.is_swipe(&touch) => {
                self.swiped = Some(Swiped { path: touch.path, capital: self.shift != Shift::Off });
                self.letter_typed();
                Action::Swipe
            }
            _ => Action::Nothing,
        }
    }

    pub fn touch_cancel(&mut self) {
        self.touch = None;
        self.popup = None;
    }

    /// When `tick` next has something to do, if ever: the end of a hold that
    /// would open a popup or start deleting words, or the next word.
    pub fn deadline(&self) -> Option<u64> {
        if self.popup.is_some() {
            return None;
        }
        let touch = self.touch.as_ref()?;
        match self.gesture(touch) {
            Gesture::Delete if self.on_far_side(touch) => Some(touch.repeat_at.unwrap_or(touch.anchor_time + FAR_HOLD_MS)),
            Gesture::Delete => None,
            Gesture::Tap if self.layout.keys[touch.key].label == "⌫" => {
                Some(touch.repeat_at.unwrap_or(touch.anchor_time + self.long_press_ms))
            }
            _ if touch.capital_typed => None,
            Gesture::Tap if self.layout.keys[touch.key].label == "⇧" => Some(touch.anchor_time + self.long_press_ms),
            Gesture::Tap if self.holds_for_capital(touch.key) => Some(touch.anchor_time + CAPITAL_HOLD_MS),
            Gesture::Tap if matches!(self.layout.keys[touch.key].label.as_str(), "⏎" | "space") => {
                Some(touch.anchor_time + self.long_press_ms)
            }
            Gesture::Tap if !self.choices(touch.key).is_empty() => Some(touch.anchor_time + self.long_press_ms),
            _ => None,
        }
    }

    /// Acts on a hold that has lasted long enough: opens the popup of a key
    /// held still, or deletes a whole word while a delete swipe holds still.
    pub fn tick(&mut self, time: u64) -> Action {
        let Some(touch) = self.touch.clone() else { return Action::Nothing };
        if self.popup.is_some() || self.deadline().is_none_or(|due| time < due) {
            return Action::Nothing;
        }
        match self.gesture(&touch) {
            Gesture::Delete => {
                let touch = self.touch.as_mut().expect("touch");
                let deleting = touch.deleting.as_mut().expect("deleting");
                deleting.words += 1;
                // Faster the longer it rests there, so a whole text can go.
                let pause = (WORD_REPEAT_MS as f32 * 0.85f32.powi(deleting.words as i32)).max(WORD_REPEAT_FASTEST as f32);
                touch.repeat_at = Some(time + pause as u64);
                Action::Delete { pieces: deleting.pieces(), words: true, started: false }
            }
            Gesture::Tap if self.holds_for_capital(touch.key) => {
                self.touch.as_mut().expect("touch").capital_typed = true;
                let capital = upper(&self.layout.keys[touch.key].label);
                // A letter with accents offers them to slide to, in place of the capital.
                if self.long_press(touch.key) {
                    self.popup.as_mut().expect("popup").replaces = Some(capital.clone());
                }
                self.letter_typed();
                Action::Commit(capital)
            }
            Gesture::Tap if self.layout.keys[touch.key].label == "⏎" => {
                self.touch.as_mut().expect("touch").capital_typed = true;
                Action::EnterAction
            }
            // Backspace held deletes again and again until the finger lifts.
            Gesture::Tap if self.layout.keys[touch.key].label == "⌫" => {
                let touch = self.touch.as_mut().expect("touch");
                touch.capital_typed = true;
                touch.repeat_at = Some(time + BACKSPACE_REPEAT_MS);
                Action::Backspace
            }
            // Shift held locks capitals.
            Gesture::Tap if self.layout.keys[touch.key].label == "⇧" => {
                self.touch.as_mut().expect("touch").capital_typed = true;
                self.shift = Shift::Locked;
                Action::Redraw
            }
            Gesture::Tap if self.layout.keys[touch.key].label == "space" => {
                let touch = self.touch.as_mut().expect("touch");
                touch.capital_typed = true;
                touch.cursor = Some(Cursor { origin: touch.at, dx: 0, dy: 0 });
                Action::Redraw
            }
            Gesture::Tap if self.long_press(touch.key) => Action::Redraw,
            _ => Action::Nothing,
        }
    }

    /// A path counts as swipe typing when it is drawn on the letters and
    /// starts on one.
    fn is_swipe(&self, touch: &Touch) -> bool {
        self.layer == 0 && is_letter(&self.layout.keys[touch.key].label)
    }

    /// The path being drawn while it is swipe typing, for the trail on screen.
    pub fn trail(&self) -> &[Point] {
        match &self.touch {
            Some(touch) if self.gesture(touch) == Gesture::Path && self.is_swipe(touch) => &touch.path,
            _ => &[],
        }
    }

    /// The letter layer, whichever layer is showing.
    pub fn letter_layout(&self) -> &Layout {
        &self.layers[0]
    }

    /// The letter keys of the letter layer, for the swipe decoder.
    pub fn swipe_keys(&self) -> swipe::Keys {
        let letters = self.layers[0]
            .keys
            .iter()
            .filter(|key| is_letter(&key.label))
            .filter_map(|key| Some((key.label.chars().next()?, key.cells[0])))
            .collect();
        swipe::Keys { letters, width: self.layers[0].cell_width }
    }

    /// What typing `typed` for `meant` costs, judged on the letter layer
    /// whichever layer is showing.
    pub fn substitution(&self, typed: char, meant: char) -> f32 {
        self.layers[0].substitution(typed, meant)
    }

    /// How far above the keyboard a popup can reach: one row's pitch.
    pub fn headroom(&self) -> f32 {
        self.layout.cell_height * 0.75
    }

    /// Opens the key's popup if it has alternatives; returns whether it did.
    /// The choices sit in a row of small hexagons centred just above the
    /// key, the most used first and chosen; a letter held for its capital has
    /// none of its accents chosen until the finger moves to one.
    pub fn long_press(&mut self, index: usize) -> bool {
        let choices = self.choices(index);
        if choices.is_empty() {
            return false;
        }
        let layout = &self.layout;
        let center = layout.keys[index].center();
        let (w, h) = (layout.cell_width * POPUP_SCALE, layout.cell_height * POPUP_SCALE);
        let holding = self.holds_for_capital(index);
        // A small honeycomb above the key: the most used straight above it, the
        // next to its right, then left; more go into an interlocking row above.
        // It is pushed inwards where it would leave the sides.
        // Towards the middle of the keyboard: mirrored on its right half.
        let mirror = if center.x > layout.width / 2.0 { -1.0 } else { 1.0 };
        let places: Vec<(f32, usize)> = popup_places(choices.len()).into_iter().map(|(o, row)| (o * mirror, row)).collect();
        let (lowest, highest) = places.iter().fold((0f32, 0f32), |(lo, hi), &(o, _)| (lo.min(o), hi.max(o)));
        let shift = (w / 2.0 - (center.x + lowest * w)).max(0.0) - (center.x + highest * w - (layout.width - w / 2.0)).max(0.0);
        // Overlapping the key's top point a little, as the honeycomb's rows overlap.
        let y = center.y - layout.cell_height / 2.0 - h / 4.0;
        let cells: Vec<Point> =
            places.iter().map(|&(o, row)| Point { x: center.x + o * w + shift, y: y - row as f32 * h * 0.75 }).collect();
        // The most used choice comes first and starts chosen, wherever it sits.
        let selected = if holding { None } else { Some(0) };
        let origin = self.touch.as_ref().map_or(center, |touch| touch.at);
        self.popup = Some(Popup { choices, cells, cell_width: w, selected, origin, moved: false, replaces: None });
        true
    }

    /// The outline of a popup choice's hexagon.
    pub fn popup_outline(&self, index: usize) -> Vec<Point> {
        let Some(popup) = &self.popup else { return Vec::new() };
        let center = popup.cells[index];
        self.layout
            .hexagon(center)
            .into_iter()
            .map(|p| Point { x: center.x + (p.x - center.x) * POPUP_SCALE, y: center.y + (p.y - center.y) * POPUP_SCALE })
            .collect()
    }

    /// The key's long-press alternatives in the current shift state.
    fn choices(&self, index: usize) -> Vec<String> {
        let shifted = self.shift != Shift::Off;
        let label = &self.layout.keys[index].label;
        let mut choices: Vec<String> = match label.chars().next().filter(|_| is_letter(label)) {
            Some(letter) => self.accents.get(&letter).into_iter().flatten().map(|c| c.to_string()).collect(),
            // What a flick up types already is not offered again.
            None => alternates(label)
                .iter()
                .skip(if self.gestures.capital == Capital::FlickUp { 1 } else { 0 })
                .map(|c| (*c).to_owned())
                .collect(),
        };
        if shifted {
            choices = choices.iter().map(|c| upper(c)).collect();
        }
        choices
    }

    /// Whether holding this key offers its capital: a letter, with holding
    /// set up as the capital gesture.
    fn holds_for_capital(&self, index: usize) -> bool {
        self.gestures.capital == Capital::Hold && is_letter(&self.layout.keys[index].label)
    }

    /// Follows the finger along an open popup.
    pub fn popup_move(&mut self, touch: Point) {
        let still = STILL * self.layout.cell_width;
        let key_top = self.layout.cell_height * POPUP_SCALE;
        if let Some(popup) = &mut self.popup {
            popup.moved |= distance(touch, popup.origin) > still;
            if popup.moved {
                // Back down on the key, a held capital is kept after all.
                let back = popup.replaces.is_some() && touch.y > popup.cells[0].y + key_top;
                popup.selected = if back { None } else { Some(nearest(&popup.cells, touch)) };
            }
        }
    }

    /// Lifting the finger commits the chosen alternative, in place of a
    /// capital typed by holding when there is one.
    pub fn popup_release(&mut self) -> Action {
        let Some(popup) = self.popup.take() else { return Action::Redraw };
        self.letter_typed();
        match (popup.selected, popup.replaces) {
            (Some(index), Some(old)) => Action::Replace { old, new: popup.choices[index].clone() },
            (Some(index), None) => Action::Commit(popup.choices[index].clone()),
            (None, _) => Action::Redraw,
        }
    }

    fn show(&mut self, layer: usize) -> Action {
        self.layout = self.layers[layer].clone();
        self.layer = layer;
        self.shift = Shift::Off;
        self.popup = None;
        Action::Redraw
    }

    pub fn popup_cancel(&mut self) {
        self.popup = None;
    }

    /// The text shown on a key in the current state. A letter being flicked
    /// up shows the capital it will type.
    pub fn label(&self, index: usize) -> String {
        let label = &self.layout.keys[index].label;
        let flicked = self.touch.as_ref().is_some_and(|touch| {
            let gesture = self.gesture(touch);
            touch.key == index
                && self.is_capital(gesture)
        });
        if is_letter(label) && (self.shift != Shift::Off || flicked) { upper(label) } else { label.clone() }
    }

    /// What the capital gesture types on a punctuation key, shown small on
    /// it; empty for other keys.
    pub fn hint(&self, index: usize) -> &'static str {
        if self.gestures.capital != Capital::FlickUp {
            return "";
        }
        alternates(&self.layout.keys[index].label).first().copied().unwrap_or("")
    }

    /// The QWERTZ row of the key's letter, for themes; None for other keys.
    pub fn key_row(&self, index: usize) -> Option<usize> {
        qwertz_row(&self.layout.keys[index].label)
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
            "ABC" => self.show(0),
            "?123" => self.show(1),
            "=\\<" => self.show(2),
            _ => {
                let text = self.label(index);
                self.letter_typed();
                Action::Commit(text)
            }
        }
    }
}

/// The row a letter has on an ordinary QWERTZ keyboard, 0 for qwertzuiop to
/// 2 for yxcvbnm. Themes colour keys by it: the layout moves letters into
/// four rows, and the colour keeps each one where the eye expects it.
pub fn qwertz_row(label: &str) -> Option<usize> {
    let mut chars = label.chars();
    let (Some(c), None) = (chars.next(), chars.next()) else { return None };
    let c = c.to_lowercase().next()?;
    ["qwertzuiop", "asdfghjkl", "yxcvbnm"].iter().position(|row| row.contains(c))
}

/// Long-press alternatives of punctuation, left to right. Letters offer
/// the accented letters of the languages in use instead, see
/// [`Keyboard::accents`].
fn alternates(label: &str) -> &'static [&'static str] {
    match label {
        "." => &[",", ":", "-", ";", "…"],
        "?" => &["!", "'", "\"", "¿", "¡"],
        "," => &[":", "-", ";", "'", "\""],
        _ => &[],
    }
}

/// Upper case of a single character, with the capital sharp s rather than
/// the "SS" that `to_uppercase` spells it as.
fn upper(text: &str) -> String {
    if text == "ß" { "ẞ".to_owned() } else { text.to_uppercase() }
}

/// The cell nearest to a point.
fn nearest(cells: &[Point], at: Point) -> usize {
    (0..cells.len()).min_by(|&a, &b| distance(cells[a], at).total_cmp(&distance(cells[b], at))).unwrap_or(0)
}

/// Where a popup's choices go, in cell widths across from the key and rows
/// up: three to the first row (above the key, right, left), four to the
/// next, shifted half a cell to interlock (right of centre, left, then
/// outer right and left), and so on.
fn popup_places(count: usize) -> Vec<(f32, usize)> {
    let mut places = Vec::new();
    let mut row = 0;
    while places.len() < count {
        let offsets: &[f32] = if row % 2 == 0 { &[0.0, 1.0, -1.0] } else { &[0.5, -0.5, 1.5, -1.5] };
        places.extend(offsets.iter().map(|&o| (o, row)).take(count - places.len()));
        row += 1;
    }
    places
}

fn is_letter(label: &str) -> bool {
    let mut chars = label.chars();
    matches!((chars.next(), chars.next()), (Some(c), None) if c.is_alphabetic())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The honeycomb without its number row, whose cells are whole hexagons up to the top edge.
    fn plain() -> LayoutSpec {
        LayoutSpec { numbers: None, ..honeycomb() }
    }

    fn layout() -> Layout {
        Layout::fit(&plain(), 1008.0, 1.0)
    }

    #[test]
    fn rows_alternate_seven_and_six_cells() {
        for (index, row) in honeycomb().rows.iter().enumerate() {
            let cells: usize = row.keys.iter().map(|key| key.span).sum();
            assert_eq!(row.start_column + cells, if index % 2 == 0 { 7 } else { 6 });
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
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.3, 400);
        let index = |label: &str| keyboard_index(&keyboard, label);
        let (shift, a, b) = (index("⇧"), index("a"), index("b"));
        assert_eq!(keyboard.press(shift), Action::Redraw);
        assert_eq!(keyboard.label(a), "A");
        assert_eq!(keyboard.press(a), Action::Commit("A".into()));
        assert_eq!(keyboard.press(b), Action::Commit("b".into()));
    }

    #[test]
    fn long_press_on_s_gives_sharp_s_and_capital_after_shift() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.3, 400);
        keyboard.accents.insert('s', vec!['ß']);
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
        let mut keyboard = Keyboard::new(plain(), 1008.0, 1.3, 400);
        keyboard.accents.insert('u', vec!['ü', 'ú', 'û']);
        let q = keyboard_index(&keyboard, "q");
        assert!(!keyboard.long_press(q), "q has no alternatives");
        let u = keyboard_index(&keyboard, "u");
        assert!(keyboard.long_press(u));
        let popup = keyboard.popup.clone().unwrap();
        let w = keyboard.layout.cell_width;
        assert!(popup.cells.iter().all(|c| c.x >= w / 2.0 - 0.01));
        assert!(popup.cells[0].y < 0.0, "a top-row popup sits in the headroom");
        assert!(popup.cells[0].y - keyboard.layout.cell_height / 2.0 >= -keyboard.headroom() - 0.01);
        keyboard.popup_move(popup.cells[2]);
        assert_eq!(keyboard.popup_release(), Action::Commit("û".into()));
    }

    #[test]
    fn symbol_layers_switch_and_type() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let symbols = keyboard_index(&keyboard, "?123");
        assert_eq!(keyboard.press(symbols), Action::Redraw);
        let at = keyboard_index(&keyboard, "@");
        assert_eq!(keyboard.press(at), Action::Commit("@".into()));
        assert!(keyboard.layout.keys.iter().all(|key| !is_letter(&key.label)), "no letters left");
        let more = keyboard_index(&keyboard, "=\\<");
        keyboard.press(more);
        let pi = keyboard_index(&keyboard, "π");
        assert_eq!(keyboard.press(pi), Action::Commit("π".into()));
        let letters = keyboard_index(&keyboard, "ABC");
        keyboard.press(letters);
        assert_eq!(keyboard.layout.find("q").label, "q");
    }

    #[test]
    fn every_layer_tiles_the_keyboard() {
        for spec in [symbol_layer(&honeycomb(), 0), symbol_layer(&space_middle(), 1), space_middle()] {
            let layout = Layout::fit(&spec, 1008.0, 1.0);
            let area: f32 = layout.keys.iter().map(|key| polygon_area(&layout.outline(key))).sum();
            let expected = layout.width * layout.height;
            assert!((area - expected).abs() / expected < 1e-4);
        }
    }

    /// A point `right` key widths right and `down` key widths below the
    /// centre of the key labelled `label`.
    fn near(keyboard: &Keyboard, label: &str, right: f32, down: f32) -> Point {
        let center = keyboard.layout.find(label).center();
        let w = keyboard.layout.cell_width;
        Point { x: center.x + right * w, y: center.y + down * w }
    }

    fn gesture(keyboard: &mut Keyboard, label: &str, path: &[(f32, f32)]) -> Action {
        keyboard.touch_down(near(keyboard, label, 0.0, 0.0), 0);
        for (i, &(right, down)) in path.iter().enumerate() {
            keyboard.touch_move(near(keyboard, label, right, down), 10 * (i as u64 + 1));
        }
        let &(right, down) = path.last().unwrap_or(&(0.0, 0.0));
        keyboard.touch_up(near(keyboard, label, right, down), 10 * (path.len() as u64 + 1))
    }

    #[test]
    fn tap_types_the_key_even_when_the_finger_drifts() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        assert_eq!(gesture(&mut keyboard, "g", &[]), Action::Commit("g".into()));
        assert_eq!(gesture(&mut keyboard, "g", &[(0.2, 0.1)]), Action::Commit("g".into()));
    }

    #[test]
    fn flick_up_types_a_capital_and_previews_it() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let g = keyboard_index(&keyboard, "g");
        keyboard.touch_down(near(&keyboard, "g", 0.0, 0.0), 0);
        keyboard.touch_move(near(&keyboard, "g", 0.1, -0.6), 30);
        assert_eq!(keyboard.label(g), "G");
        assert_eq!(keyboard.touch_up(near(&keyboard, "g", 0.1, -0.6), 40), Action::Commit("G".into()));
        assert_eq!(keyboard.label(g), "g");
        assert_eq!(gesture(&mut keyboard, ",", &[(0.0, -0.6)]), Action::Commit(":".into()), "punctuation gives its first alternative");
        assert_eq!(gesture(&mut keyboard, "space", &[(0.0, -0.6)]), Action::Nothing);
    }

    #[test]
    fn a_slide_from_the_right_edge_deletes_and_sliding_back_puts_back() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let w = keyboard.layout.cell_width;
        let y = keyboard.layout.find("l").center().y;
        let at = |x: f32| Point { x, y };
        keyboard.touch_down(at(1007.0), 0);
        assert_eq!(keyboard.touch_move(at(1007.0 - 0.2 * w), 100), Action::Nothing, "not yet a slide");
        assert_eq!(keyboard.touch_move(at(1007.0 - 0.4 * w), 200), Action::Delete { pieces: 0, words: false, started: true });
        assert_eq!(keyboard.touch_move(at(1007.0 - 0.75 * w), 400), Action::Delete { pieces: 1, words: false, started: false });
        assert_eq!(keyboard.touch_move(at(1007.0 - 1.4 * w), 500), Action::Delete { pieces: 2, words: false, started: false });
        assert_eq!(keyboard.touch_move(at(1007.0 - 2.0 * w), 600), Action::Delete { pieces: 4, words: false, started: false }, "faster farther in");
        assert_eq!(keyboard.touch_move(at(1007.0 - 1.9 * w), 700), Action::Nothing, "easing back a little keeps them");
        assert_eq!(keyboard.touch_move(at(1007.0 - 1.2 * w), 800), Action::Delete { pieces: 2, words: false, started: false });
        assert_eq!(keyboard.pressed(), None);
        assert_eq!(keyboard.touch_up(at(1007.0 - 1.2 * w), 900), Action::Nothing);
        // From inside the keyboard the same slide is a flick that does nothing.
        assert_eq!(gesture(&mut keyboard, "g", &[(-0.3, 0.0), (-0.6, 0.1)]), Action::Nothing);
    }

    #[test]
    fn left_handed_delete_slides_in_from_the_left() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        keyboard.gestures.delete_from_left = true;
        let w = keyboard.layout.cell_width;
        let y = keyboard.layout.find("a").center().y;
        keyboard.touch_down(Point { x: 1.0, y }, 0);
        assert_eq!(keyboard.touch_move(Point { x: 1.0 + 0.4 * w, y }, 200), Action::Delete { pieces: 0, words: false, started: true });
        assert_eq!(keyboard.touch_move(Point { x: 1.0 + 0.8 * w, y }, 400), Action::Delete { pieces: 1, words: false, started: false });
    }

    #[test]
    fn other_flicks_type_nothing() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        assert_eq!(gesture(&mut keyboard, "g", &[(0.6, 0.0)]), Action::Nothing);
        assert_eq!(gesture(&mut keyboard, "g", &[(0.0, 0.6)]), Action::Nothing);
        assert_eq!(gesture(&mut keyboard, "g", &[(-0.6, 0.0), (0.0, 0.1)]), Action::Nothing, "pulled back");
    }

    #[test]
    fn long_paths_from_a_letter_are_swipes_with_their_points() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        assert_eq!(gesture(&mut keyboard, "g", &[(-0.6, 0.0), (-1.4, 0.0)]), Action::Swipe);
        let swiped = keyboard.swiped.clone().unwrap();
        assert_eq!(swiped.path.len(), 4);
        assert!(!swiped.capital);
        assert_eq!(gesture(&mut keyboard, "space", &[(1.5, 0.0)]), Action::Nothing, "not from a letter");
    }

    #[test]
    fn a_delete_slide_resting_on_the_far_side_deletes_whole_words_ever_faster() {
        let mut keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        let w = keyboard.layout.cell_width;
        let y = keyboard.layout.find("l").center().y;
        keyboard.touch_down(Point { x: 1007.0, y }, 0);
        keyboard.touch_move(Point { x: 600.0, y }, 300);
        assert_eq!(keyboard.deadline(), None, "resting in the middle only chooses how many characters");
        keyboard.touch_move(Point { x: 0.5 * w, y }, 500);
        assert_eq!(keyboard.deadline(), Some(500 + FAR_HOLD_MS));
        let first = keyboard.tick(500 + FAR_HOLD_MS);
        assert!(matches!(first, Action::Delete { words: true, .. }));
        let pauses: Vec<u64> = (0..6)
            .map(|_| {
                let due = keyboard.deadline().unwrap();
                keyboard.tick(due);
                keyboard.deadline().unwrap() - due
            })
            .collect();
        assert!(pauses.windows(2).all(|pair| pair[1] <= pair[0]), "{pauses:?}");
        assert!(keyboard.deleting().unwrap().2);
    }

    #[test]
    fn holding_backspace_repeats_and_holding_shift_locks_capitals() {
        let mut keyboard = Keyboard::new(with_shift_and_backspace(space_middle()), 1008.0, 1.0, 400);
        let backspace = near(&keyboard, "⌫", 0.0, 0.0);
        keyboard.touch_down(backspace, 0);
        assert_eq!(keyboard.tick(400), Action::Backspace);
        assert_eq!(keyboard.tick(400 + BACKSPACE_REPEAT_MS), Action::Backspace);
        assert_eq!(keyboard.touch_up(backspace, 500), Action::Redraw);
        let shift = near(&keyboard, "⇧", 0.0, 0.0);
        keyboard.touch_down(shift, 1000);
        keyboard.tick(1400);
        keyboard.touch_up(shift, 1500);
        assert_eq!(gesture(&mut keyboard, "g", &[]), Action::Commit("G".into()));
        assert_eq!(gesture(&mut keyboard, "a", &[]), Action::Commit("A".into()), "still capitals");
        assert_eq!(gesture(&mut keyboard, "⇧", &[]), Action::Redraw);
        assert_eq!(gesture(&mut keyboard, "a", &[]), Action::Commit("a".into()));
    }

    #[test]
    fn a_flick_up_types_a_capital_unless_holding_is_set_up() {
        let mut keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        let up = [(0.0, -0.6)];
        assert_eq!(gesture(&mut keyboard, "g", &up), Action::Commit("G".into()));
        keyboard.gestures.capital = Capital::Hold;
        assert_eq!(gesture(&mut keyboard, "g", &up), Action::Nothing);
    }

    #[test]
    fn holding_a_letter_types_its_capital_at_once_and_an_accent_can_replace_it() {
        let mut keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        keyboard.gestures.capital = Capital::Hold;
        keyboard.accents.insert('a', vec!['ä', 'à']);
        // No accents: the capital is all there is, and lifting adds nothing.
        keyboard.touch_down(near(&keyboard, "g", 0.0, 0.0), 0);
        assert_eq!(keyboard.deadline(), Some(CAPITAL_HOLD_MS));
        assert_eq!(keyboard.tick(CAPITAL_HOLD_MS), Action::Commit("G".into()));
        assert!(keyboard.popup.is_none());
        assert_eq!(keyboard.deadline(), None);
        assert_eq!(keyboard.touch_up(near(&keyboard, "g", 0.0, 0.0), 900), Action::Redraw);
        // Accents open with the capital typed and none of them chosen.
        keyboard.touch_down(near(&keyboard, "a", 0.0, 0.0), 1000);
        assert_eq!(keyboard.tick(1000 + CAPITAL_HOLD_MS), Action::Commit("A".into()));
        let popup = keyboard.popup.clone().expect("popup");
        assert_eq!(popup.choices, ["ä", "à"]);
        assert_eq!(popup.selected, None);
        assert!((popup.cells[0].x - near(&keyboard, "a", 0.0, 0.0).x).abs() < 1.0, "the most used accent sits above the key");
        assert!(popup.cells[1].x > popup.cells[0].x, "the next one towards the middle, to its right");
        // A finger never holds perfectly still; a little drift keeps the capital.
        keyboard.touch_move(near(&keyboard, "a", 0.1, 0.05), 1250);
        assert_eq!(keyboard.touch_up(near(&keyboard, "a", 0.1, 0.05), 1300), Action::Redraw);
        keyboard.touch_down(near(&keyboard, "a", 0.0, 0.0), 2000);
        keyboard.tick(2000 + CAPITAL_HOLD_MS);
        let cell = keyboard.popup.as_ref().unwrap().cells[0];
        keyboard.touch_move(cell, 2400);
        assert_eq!(keyboard.touch_up(cell, 2450), Action::Replace { old: "A".into(), new: "ä".into() });
        // Punctuation keeps the long press.
        keyboard.touch_down(near(&keyboard, ".", 0.0, 0.0), 3000);
        assert_eq!(keyboard.deadline(), Some(3400));
    }

    #[test]
    fn holding_enter_asks_for_the_fields_action_once() {
        let mut keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        let enter = near(&keyboard, "⏎", 0.0, 0.0);
        keyboard.touch_down(enter, 0);
        assert_eq!(keyboard.deadline(), Some(400));
        assert_eq!(keyboard.tick(400), Action::EnterAction);
        assert_eq!(keyboard.deadline(), None);
        assert_eq!(keyboard.touch_up(enter, 700), Action::Redraw);
        keyboard.touch_down(enter, 1000);
        assert_eq!(keyboard.touch_up(enter, 1080), Action::Enter);
        assert_eq!(gesture(&mut keyboard, "⏎", &[(0.0, -0.6)]), Action::EnterAction, "a flick up does it too");
    }

    #[test]
    fn holding_space_moves_the_cursor_with_the_finger() {
        let mut keyboard = Keyboard::new(space_bottom(), 1008.0, 1.0, 400);
        let w = keyboard.layout.cell_width;
        let space = near(&keyboard, "space", 0.0, 0.0);
        keyboard.touch_down(space, 0);
        assert_eq!(keyboard.tick(400), Action::Redraw);
        assert!(keyboard.moving_cursor());
        let at = |dx: f32| Point { x: space.x + dx * w, y: space.y };
        assert_eq!(keyboard.touch_move(at(0.1), 450), Action::Nothing);
        assert_eq!(keyboard.touch_move(at(0.65), 500), Action::Cursor { dx: 2, dy: 0 });
        assert_eq!(keyboard.touch_move(at(-0.35), 550), Action::Cursor { dx: -3, dy: 0 });
        assert_eq!(keyboard.touch_up(at(-0.35), 600), Action::Redraw, "lifting types no space");
        assert!(!keyboard.moving_cursor());
    }

    #[test]
    fn a_sideways_flick_on_space_steps_through_languages() {
        let mut keyboard = Keyboard::new(space_bottom(), 1008.0, 1.0, 400);
        assert_eq!(gesture(&mut keyboard, "space", &[(0.6, 0.0)]), Action::Language { forward: true });
        assert_eq!(gesture(&mut keyboard, "space", &[(-0.6, 0.05)]), Action::Language { forward: false });
        assert_eq!(gesture(&mut keyboard, "space", &[]), Action::Commit(" ".into()));
    }

    #[test]
    fn holding_still_opens_the_popup_and_lifting_commits_from_it() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        keyboard.accents.insert('s', vec!['ß']);
        keyboard.touch_down(near(&keyboard, "s", 0.0, 0.0), 0);
        assert_eq!(keyboard.tick(400), Action::Redraw);
        assert!(keyboard.popup.is_some());
        assert_eq!(keyboard.touch_up(near(&keyboard, "s", 0.0, 0.0), 500), Action::Commit("ß".into()));
        keyboard.touch_down(near(&keyboard, "q", 0.0, 0.0), 0);
        assert_eq!(keyboard.deadline(), None, "q has no popup to wait for");
    }

    fn keyboard_index(keyboard: &Keyboard, label: &str) -> usize {
        keyboard.layout.keys.iter().position(|k| k.label == label).unwrap()
    }

    #[test]
    fn every_letter_has_its_qwertz_row() {
        let keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let rows: Vec<Option<usize>> =
            ["q", "p", "a", "l", "y", "m", "⇧", "space", "ß"].iter().map(|l| qwertz_row(l)).collect();
        assert_eq!(rows, [Some(0), Some(0), Some(1), Some(1), Some(2), Some(2), None, None, None]);
        let letters = keyboard.layout.keys.iter().enumerate().filter(|(i, _)| keyboard.key_row(*i).is_some());
        assert_eq!(letters.count(), 26);
    }

    #[test]
    fn rows_interlock() {
        let layout = layout();
        let q = layout.find("q").cells[0];
        let w = layout.find("w").cells[0];
        assert!((w.x - q.x - layout.cell_width / 2.0).abs() < 0.01);
        assert!((w.y - q.y - layout.cell_height * 0.75).abs() < 0.01);
    }

    #[test]
    fn layers_share_the_tallest_height_and_still_tile() {
        let keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        let tallest = Layout::fit(&space_middle(), 1008.0, 1.0).height;
        for layout in &keyboard.layers {
            assert!((layout.height - tallest).abs() < 0.01);
            let area: f32 = layout.keys.iter().map(|key| polygon_area(&layout.outline(key))).sum();
            assert!((area - layout.width * tallest).abs() / (layout.width * tallest) < 1e-4);
        }
    }

    #[test]
    fn space_at_the_bottom_keeps_qwertz_order_below_the_shared_rows() {
        let (middle, bottom) = (space_middle(), space_bottom());
        assert_eq!(middle.rows[..2], bottom.rows[..2], "the top letter rows are the reference layout's");
        let labels = |row: &RowSpec| row.keys.iter().map(|key| key.label.clone()).collect::<Vec<_>>().concat();
        assert_eq!(labels(&bottom.rows[2]), "sdfhjk");
        assert_eq!(labels(&bottom.rows[3]), "yxcvbnm");
        // f, h and y stay in the cells the middle layout has them in.
        for (row, column) in [(2, 2), (2, 3), (3, 0)] {
            assert_eq!(middle.rows[row].keys[column], bottom.rows[row].keys[column]);
        }
    }

    #[test]
    fn space_middle_starts_with_a_short_shifted_row_as_wide_as_the_rest() {
        let layout = Layout::fit(&space_middle(), 1008.0, 1.0);
        let w = layout.find("w").cells[0];
        let q = layout.find("q").cells[0];
        assert!((w.x - q.x - layout.cell_width / 2.0).abs() < 0.01);
        assert!((layout.cell_width - 1008.0 / 7.0).abs() < 0.01);
        let keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        let letters = keyboard.layout.keys.iter().enumerate().filter(|(i, _)| keyboard.key_row(*i).is_some());
        assert_eq!(letters.count(), 26);
    }

    #[test]
    fn halves_split_their_cell_and_numbers_zigzag() {
        let mut spec = space_middle();
        spec.rows[4] = row(0, vec![KeySpec::new("?123", 1), KeySpec::new("space", 3), KeySpec::halves(",", "."), KeySpec::new("⏎", 1)]);
        let layout = Layout::fit(&spec, 1008.0, 1.0);
        let comma = layout.keys.iter().find(|key| key.half == Some(Half::Left)).unwrap();
        let cell = comma.cells[0];
        let w = layout.cell_width;
        assert_eq!(layout.key_at(Point { x: cell.x - w / 4.0, y: cell.y }).unwrap().label, ",");
        assert_eq!(layout.key_at(Point { x: cell.x + w / 4.0, y: cell.y }).unwrap().half, Some(Half::Right));
        let dot = layout.keys.iter().find(|key| key.half == Some(Half::Right)).unwrap();
        let (left, right) = (polygon_area(&layout.outline(comma)), polygon_area(&layout.outline(dot)));
        assert!((left - right).abs() / left < 1e-3, "{left} {right}");
        let area: f32 = layout.keys.iter().map(|key| polygon_area(&layout.outline(key))).sum();
        assert!((area - layout.width * layout.height).abs() / (layout.width * layout.height) < 1e-4);
        let (one, two) = (layout.find("1").cells[0], layout.find("2").cells[0]);
        assert!(two.y > one.y && two.x > one.x);
        for digit in ["1", "2", "5", "0"] {
            let key = layout.find(digit);
            assert_eq!(layout.key_at(key.cells[0]).unwrap().label, digit);
        }
    }

    #[test]
    fn a_delete_slide_that_turns_up_or_down_becomes_swipe_typing() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let w = keyboard.layout.cell_width;
        let p = keyboard.layout.find("p").cells[0];
        let at = |dx: f32, dy: f32| Point { x: 1000.0 + dx * w, y: p.y + dy * w };
        keyboard.touch_down(at(0.0, 0.0), 0);
        assert!(matches!(keyboard.touch_move(at(-0.5, 0.0), 100), Action::Delete { pieces: 0, .. }));
        keyboard.touch_move(at(-1.5, 0.0), 250);
        assert_eq!(keyboard.touch_move(at(-1.7, 0.7), 300), Action::Delete { pieces: 0, words: false, started: false });
        assert_eq!(keyboard.touch_move(at(-2.5, 1.4), 350), Action::Nothing);
        assert_eq!(keyboard.touch_up(at(-2.5, 1.4), 380), Action::Swipe);
    }

    #[test]
    fn a_delete_slide_moved_about_for_a_while_stays_a_delete() {
        let mut keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        let w = keyboard.layout.cell_width;
        let p = keyboard.layout.find("p").cells[0];
        let at = |dx: f32, dy: f32| Point { x: 1000.0 + dx * w, y: p.y + dy * w };
        keyboard.touch_down(at(0.0, 0.0), 0);
        keyboard.touch_move(at(-2.0, 0.0), 300);
        // Back towards the start and drifting down, as a finger does while choosing how much to delete.
        keyboard.touch_move(at(-0.6, 0.7), 900);
        keyboard.touch_move(at(-0.2, 0.75), 1100);
        assert!(keyboard.deleting().is_some());
        assert!(!matches!(keyboard.touch_up(at(-0.2, 0.75), 1200), Action::Swipe));
    }

    #[test]
    fn a_slanted_straight_delete_slide_keeps_deleting() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let w = keyboard.layout.cell_width;
        let y = keyboard.layout.find("p").cells[0].y;
        keyboard.touch_down(Point { x: 1000.0, y }, 0);
        let mut last = Action::Nothing;
        for step in 1..=10 {
            let t = step as f32 / 10.0;
            let action = keyboard.touch_move(Point { x: 1000.0 - 2.3 * w * t, y: y + 1.3 * w * t }, 50 * step);
            if action != Action::Nothing {
                last = action;
            }
        }
        assert!(matches!(last, Action::Delete { pieces, .. } if pieces >= 5), "{last:?}");
    }

    #[test]
    fn quick_straight_strokes_are_flicks_and_deletes_whatever_their_length() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let w = keyboard.layout.cell_width;
        // Up 1.6 key widths in 90 ms, as a thumb flicks: a capital.
        let h = keyboard.layout.find("h").cells[0];
        keyboard.touch_down(h, 0);
        keyboard.touch_move(Point { x: h.x + 0.1 * w, y: h.y - 0.8 * w }, 45);
        assert_eq!(keyboard.touch_up(Point { x: h.x + 0.2 * w, y: h.y - 1.6 * w }, 90), Action::Commit("H".into()));
        // The same stroke slowly: swipe typing.
        keyboard.touch_down(h, 0);
        keyboard.touch_move(Point { x: h.x + 0.1 * w, y: h.y - 0.8 * w }, 150);
        assert_eq!(keyboard.touch_up(Point { x: h.x + 0.2 * w, y: h.y - 1.6 * w }, 300), Action::Swipe);
        // Left from the right third, short of the edge, quickly: deletes on lifting.
        let o = keyboard.layout.find("i").cells[0];
        keyboard.touch_down(o, 0);
        keyboard.touch_move(Point { x: o.x - 0.7 * w, y: o.y }, 50);
        assert_eq!(keyboard.touch_up(Point { x: o.x - 1.4 * w, y: o.y + 0.1 * w }, 100), Action::Delete { pieces: 1, words: false, started: true }, "a flick takes one");
    }

    #[test]
    fn symbol_pages_keep_every_key_but_the_letters_in_place() {
        for letters in [honeycomb(), space_middle()] {
            let keyboard = Keyboard::new(letters, 1008.0, 1.0, 400);
            let [plain, symbols, more] = &keyboard.layers;
            for (key, symbol) in plain.keys.iter().zip(&symbols.keys) {
                assert_eq!(key.cells, symbol.cells, "{} and {} share a place", key.label, symbol.label);
                if key.label == "?123" {
                    assert_eq!(symbol.label, "ABC");
                } else if !is_letter(&key.label) && key.label != "⇧" {
                    assert_eq!(key.label, symbol.label);
                }
            }
            assert!(symbols.keys.iter().any(|key| key.label == MORE));
            assert!(more.keys.iter().any(|key| key.label == "?123"));
            let on_letters: Vec<&str> = plain.keys.iter().map(|key| key.label.as_str()).collect();
            assert!(symbols.keys.iter().filter(|key| !on_letters.contains(&key.label.as_str())).count() >= 20);
        }
    }

    #[test]
    fn space_at_the_bottom_has_the_middle_layouts_letters_and_only_letters_in_the_middle() {
        let layout = Layout::fit(&space_bottom(), 1008.0, 1.0);
        let letters = layout.keys.iter().filter(|key| is_letter(&key.label)).count();
        assert_eq!(letters, 26);
        let bottom = layout.keys.iter().map(|key| key.row).filter(|&row| row != NUMBER_ROW).max().unwrap();
        assert!(layout.keys.iter().filter(|key| !is_letter(&key.label) && key.row != NUMBER_ROW).all(|key| key.row == bottom));
        let area: f32 = layout.keys.iter().map(|key| polygon_area(&layout.outline(key))).sum();
        assert!((area - layout.width * layout.height).abs() / (layout.width * layout.height) < 1e-4);
    }
}
