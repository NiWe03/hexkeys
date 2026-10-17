//! JNI entry points for `io.github.tieo.hexkeys.Core`.
//!
//! The Kotlin side owns one `Keyboard` through an opaque handle: it asks for
//! outlines and labels to draw, passes touches in, and gets back what a press
//! means. Every decision about keys lives in the core; Kotlin only draws and
//! talks to the app being typed into.

use hexkeys::dictionary::Dictionary;
use hexkeys::{Action, Keyboard, Point, suggest};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jfloat, jfloatArray, jint, jlong, jobjectArray, jstring};

fn keyboard<'a>(handle: jlong) -> &'a mut Keyboard {
    // SAFETY: the handle comes from `create` and lives until `destroy`; the
    // input method calls in from its main thread only.
    unsafe { &mut *(handle as *mut Keyboard) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_create(
    _env: JNIEnv,
    _class: JClass,
    width: jfloat,
    stretch: jfloat,
    long_press_ms: jlong,
) -> jlong {
    Box::into_raw(Box::new(Keyboard::new(width, stretch, long_press_ms as u64))) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_destroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    // SAFETY: see `keyboard`; the Kotlin side drops the handle after this.
    drop(unsafe { Box::from_raw(handle as *mut Keyboard) });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_height(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jfloat {
    keyboard(handle).layout.height
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_keyCount(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    keyboard(handle).layout.keys.len() as jint
}

/// The key's outline as x0, y0, x1, y1, ...
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_outline(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jfloatArray {
    let layout = &keyboard(handle).layout;
    let flat: Vec<f32> =
        layout.outline(&layout.keys[index as usize]).iter().flat_map(|p| [p.x, p.y]).collect();
    let array = env.new_float_array(flat.len() as i32).expect("float array");
    env.set_float_array_region(&array, 0, &flat).expect("fill float array");
    array.into_raw()
}

/// Where the key's label goes, as x, y.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_labelPosition(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jfloatArray {
    let center = keyboard(handle).layout.keys[index as usize].center();
    let array = env.new_float_array(2).expect("float array");
    env.set_float_array_region(&array, 0, &[center.x, center.y]).expect("fill float array");
    array.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_label(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jstring {
    env.new_string(keyboard(handle).label(index as usize)).expect("label").into_raw()
}

/// The key the finger went down on, or -1 when no finger is down.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_pressed(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    keyboard(handle).pressed().map_or(-1, |index| index as jint)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_touchDown(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jfloat,
    y: jfloat,
    time: jlong,
) {
    keyboard(handle).touch_down(Point { x, y }, time as u64);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_touchMove(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jfloat,
    y: jfloat,
    time: jlong,
) {
    keyboard(handle).touch_move(Point { x, y }, time as u64);
}

/// What lifting the finger means, as `[kind, text]` with kind one of
/// `commit`, `backspace`, `enter`, `redraw`, `nothing`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_touchUp(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jfloat,
    y: jfloat,
    time: jlong,
) -> jobjectArray {
    let result = keyboard(handle).touch_up(Point { x, y }, time as u64);
    action(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_touchCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    keyboard(handle).touch_cancel();
}

/// When `tick` is next due, or -1 when nothing is waiting.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_deadline(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    keyboard(handle).deadline().map_or(-1, |time| time as jlong)
}

/// What a hold that has lasted long enough does, in the same form as `touchUp`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_tick(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    time: jlong,
) -> jobjectArray {
    let result = keyboard(handle).tick(time as u64);
    action(&mut env, result)
}

fn floats(env: &JNIEnv, values: &[f32]) -> jfloatArray {
    let array = env.new_float_array(values.len() as i32).expect("float array");
    env.set_float_array_region(&array, 0, values).expect("fill float array");
    array.into_raw()
}

fn action(env: &mut JNIEnv, action: Action) -> jobjectArray {
    let (kind, text) = match action {
        Action::Commit(text) => ("commit", text),
        Action::Backspace => ("backspace", String::new()),
        Action::Enter => ("enter", String::new()),
        Action::Redraw => ("redraw", String::new()),
        Action::Nothing => ("nothing", String::new()),
    };
    let array = env
        .new_object_array(2, "java/lang/String", jni::objects::JObject::null())
        .expect("string array");
    for (i, value) in [kind, text.as_str()].into_iter().enumerate() {
        let string = env.new_string(value).expect("string");
        env.set_object_array_element(&array, i as i32, string).expect("fill string array");
    }
    array.into_raw()
}

/// Room above the keys a popup may reach into.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_headroom(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jfloat {
    keyboard(handle).headroom()
}

/// Number of choices in the open popup, 0 when none is open.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_popupCount(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    keyboard(handle).popup.as_ref().map_or(0, |popup| popup.choices.len() as jint)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_popupSelected(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    keyboard(handle).popup.as_ref().map_or(-1, |popup| popup.selected as jint)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_popupOutline(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jfloatArray {
    let keyboard = keyboard(handle);
    let popup = keyboard.popup.as_ref().expect("no popup open");
    let flat: Vec<f32> =
        keyboard.layout.hexagon(popup.cells[index as usize]).iter().flat_map(|p| [p.x, p.y]).collect();
    floats(&env, &flat)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_popupLabelPosition(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jfloatArray {
    let popup = keyboard(handle).popup.as_ref().expect("no popup open");
    let cell = popup.cells[index as usize];
    floats(&env, &[cell.x, cell.y])
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_popupLabel(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jstring {
    let popup = keyboard(handle).popup.as_ref().expect("no popup open");
    env.new_string(&popup.choices[index as usize]).expect("label").into_raw()
}

/// Width of one cell, which labels are sized from.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_cellWidth(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jfloat {
    keyboard(handle).layout.cell_width
}

/// The dictionaries suggestions come from, kept apart from the keyboard,
/// which is rebuilt whenever its size changes.
fn lexicon<'a>(handle: jlong) -> &'a mut Vec<Dictionary> {
    // SAFETY: as for `keyboard`, with the handle from `lexiconCreate`.
    unsafe { &mut *(handle as *mut Vec<Dictionary>) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lexiconCreate(_env: JNIEnv, _class: JClass) -> jlong {
    Box::into_raw(Box::new(Vec::<Dictionary>::new())) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lexiconDestroy(_env: JNIEnv, _class: JClass, handle: jlong) {
    // SAFETY: see `lexicon`; the Kotlin side drops the handle after this.
    drop(unsafe { Box::from_raw(handle as *mut Vec<Dictionary>) });
}

/// Adds a `.dict` file's contents; false when they are not a dictionary
/// this reader understands.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lexiconAdd(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    bytes: JByteArray,
) -> jboolean {
    let Ok(bytes) = env.convert_byte_array(&bytes) else { return 0 };
    match Dictionary::new(bytes) {
        Ok(dictionary) => {
            lexicon(handle).push(dictionary);
            1
        }
        Err(_) => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lexiconClear(_env: JNIEnv, _class: JClass, handle: jlong) {
    lexicon(handle).clear();
}

/// The suggestion strip for the text before the cursor, as the left,
/// middle and right slot (empty when unused), the number of code points
/// a choice replaces, and the autocorrection (empty when none).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_strip(
    mut env: JNIEnv,
    _class: JClass,
    lexicon_handle: jlong,
    keyboard_handle: jlong,
    before: JString,
) -> jobjectArray {
    let before: String = env.get_string(&before).map(Into::into).unwrap_or_default();
    let keyboard = keyboard(keyboard_handle);
    let substitution = |typed: char, meant: char| keyboard.substitution(typed, meant);
    let strip = suggest::strip(lexicon(lexicon_handle), &before, &substitution)
        .unwrap_or(suggest::Strip { slots: Default::default(), replaces: 0, correction: None });
    let replaces = strip.replaces.to_string();
    let correction = strip.correction.unwrap_or_default();
    let values = [
        strip.slots[0].as_str(),
        strip.slots[1].as_str(),
        strip.slots[2].as_str(),
        replaces.as_str(),
        correction.as_str(),
    ];
    let array = env
        .new_object_array(values.len() as i32, "java/lang/String", jni::objects::JObject::null())
        .expect("string array");
    for (i, value) in values.into_iter().enumerate() {
        let string = env.new_string(value).expect("string");
        env.set_object_array_element(&array, i as i32, string).expect("fill string array");
    }
    array.into_raw()
}
