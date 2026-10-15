//! JNI entry points for `io.github.tieo.hexkeys.Core`.
//!
//! The Kotlin side owns one `Keyboard` through an opaque handle: it asks for
//! outlines and labels to draw, passes touches in, and gets back what a press
//! means. Every decision about keys lives in the core; Kotlin only draws and
//! talks to the app being typed into.

use hexkeys::{Action, Keyboard, Point};
use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jfloat, jfloatArray, jint, jlong, jobjectArray, jstring};

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
) -> jlong {
    Box::into_raw(Box::new(Keyboard::new(width, stretch))) as jlong
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

/// The key under a touch, or -1 when there is none.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_keyAt(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jfloat,
    y: jfloat,
) -> jint {
    keyboard(handle).key_at(Point { x, y }).map_or(-1, |index| index as jint)
}

/// What pressing the key means, as `[kind, text]` with kind one of
/// `commit`, `backspace`, `enter`, `redraw`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_press(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jobjectArray {
    let result = keyboard(handle).press(index as usize);
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

/// Opens the key's long-press popup; false when the key has none.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_longPress(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jni::sys::jboolean {
    keyboard(handle).long_press(index as usize) as jni::sys::jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_popupMove(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jfloat,
    y: jfloat,
) {
    keyboard(handle).popup_move(Point { x, y });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_popupRelease(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jobjectArray {
    let result = keyboard(handle).popup_release();
    action(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_popupCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    keyboard(handle).popup_cancel();
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
