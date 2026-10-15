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
                keys.push(Key { label: key_spec.label.clone(), row, cells });
                column += key_spec.span as f32;
            }
        }

        let height = cell_height + (spec.rows.len().saturating_sub(1)) as f32 * row_pitch;
        Self { keys, cell_width, cell_height, width, height }
    }

    /// The key under a touch: the one owning the nearest cell centre.
    pub fn key_at(&self, touch: Point) -> Option<&Key> {
        let stretch = self.cell_height / (self.cell_width * 2.0 / 3f32.sqrt());
        let distance = |cell: &Point| {
            let dx = touch.x - cell.x;
            let dy = (touch.y - cell.y) / stretch;
            dx * dx + dy * dy
        };
        self.keys
            .iter()
            .flat_map(|key| key.cells.iter().map(move |cell| (key, distance(cell))))
            .min_by(|a, b| a.1.total_cmp(&b.1))
            .map(|(key, _)| key)
    }

    /// Outline of a key, clockwise from the top of the first cell: a pointy-top
    /// hexagon for one cell, stretched into an octagon with a flat top and
    /// bottom when the key spans several cells.
    pub fn outline(&self, key: &Key) -> Vec<Point> {
        let half_width = self.cell_width / 2.0;
        let half_height = self.cell_height / 2.0;
        let quarter = self.cell_height / 4.0;
        let first = key.cells[0];
        let last = key.cells[key.cells.len() - 1];
        let y = first.y;
        let mut outline = vec![
            Point { x: first.x, y: y - half_height },
            Point { x: last.x, y: y - half_height },
            Point { x: last.x + half_width, y: y - quarter },
            Point { x: last.x + half_width, y: y + quarter },
            Point { x: last.x, y: y + half_height },
            Point { x: first.x, y: y + half_height },
            Point { x: first.x - half_width, y: y + quarter },
            Point { x: first.x - half_width, y: y - quarter },
        ];
        if key.cells.len() == 1 {
            // Top and bottom collapse to single vertices.
            outline.remove(5);
            outline.remove(1);
        }
        outline
    }
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
    fn single_cell_keys_are_hexagons() {
        let layout = layout();
        for key in &layout.keys {
            let corners = layout.outline(key).len();
            assert_eq!(corners, if key.cells.len() == 1 { 6 } else { 8 }, "{}", key.label);
        }
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
