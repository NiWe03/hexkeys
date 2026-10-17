//! Speech to text with whisper.cpp, for the input method's microphone.
//!
//! The Kotlin side loads a downloaded model once through an opaque handle,
//! records 16 kHz mono audio itself, and hands it over when the user stops
//! speaking. The language is the one of the input method's languages that
//! whisper finds most likely in the audio, so a short German sentence is not
//! taken for Dutch.

use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jlong, jstring};
use whisper_rs::{FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters};

fn model<'a>(handle: jlong) -> &'a WhisperContext {
    // SAFETY: the handle comes from `voiceLoad` and lives until `voiceFree`;
    // calls come from one background thread at a time.
    unsafe { &*(handle as *const WhisperContext) }
}

/// Loads a whisper model file; 0 when it cannot be loaded.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_voiceLoad(mut env: JNIEnv, _class: JClass, path: JString) -> jlong {
    let Ok(path) = env.get_string(&path).map(String::from) else { return 0 };
    match WhisperContext::new_with_params(&path, WhisperContextParameters::default()) {
        Ok(context) => Box::into_raw(Box::new(context)) as jlong,
        Err(_) => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_voiceFree(_env: JNIEnv, _class: JClass, handle: jlong) {
    // SAFETY: see `model`; the Kotlin side drops the handle after this.
    drop(unsafe { Box::from_raw(handle as *mut WhisperContext) });
}

/// The text spoken in `audio`, 16 kHz mono samples from -1 to 1, in
/// whichever of `languages` (ISO codes separated by commas) is most likely;
/// empty when nothing could be made out.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_voiceTranscribe(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    audio: JFloatArray,
    languages: JString,
) -> jstring {
    let languages: String = env.get_string(&languages).map(Into::into).unwrap_or_default();
    let length = env.get_array_length(&audio).unwrap_or(0) as usize;
    let mut samples = vec![0f32; length];
    let _ = env.get_float_array_region(&audio, 0, &mut samples);
    let text = transcribe(model(handle), &samples, &languages).unwrap_or_default();
    env.new_string(text).expect("string").into_raw()
}

fn transcribe(model: &WhisperContext, samples: &[f32], languages: &str) -> Option<String> {
    let threads = std::thread::available_parallelism().map_or(4, |n| n.get()).min(4);
    // The encoder looks at 30 seconds whatever was said; told how much
    // audio there is, plus some room, it only works through that much,
    // which takes a fraction of the time for a sentence.
    let audio_ctx = (samples.len() / SAMPLES_PER_FRAME + AUDIO_CTX_ROOM).min(MAX_AUDIO_CTX) as i32;
    let params = |language: Option<&'static str>| {
        let mut params = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
        params.set_n_threads(threads as i32);
        params.set_audio_ctx(audio_ctx);
        params.set_language(language);
        params.set_translate(false);
        params.set_no_context(true);
        params.set_print_progress(false);
        params.set_print_realtime(false);
        params.set_print_timestamps(false);
        params.set_print_special(false);
        params.set_suppress_blank(true);
        // A recording is at most 30 seconds, one window: as one segment,
        // a short encoder context does not make the decoder start over and
        // repeat what it has already written.
        params.set_single_segment(true);
        params
    };
    let mut state = model.create_state().ok()?;
    let allowed: Vec<(&'static str, i32)> = languages
        .split(',')
        .filter_map(|code| {
            let id = whisper_rs::get_lang_id(code)?;
            Some((whisper_rs::get_lang_str(id)?, id))
        })
        .collect();
    let language = match allowed.as_slice() {
        [] => None,
        [(only, _)] => Some(*only),
        _ => {
            // Detection runs the encoder on whatever length a state last
            // decoded; a moment of silence decoded first sets it to this
            // recording's length instead of the full 30 seconds.
            let mut prime = params(Some(allowed[0].0));
            prime.set_max_tokens(1);
            state.full(prime, &[0.0; 1600]).ok()?;
            state.pcm_to_mel(samples, threads).ok()?;
            let (_, probabilities) = state.lang_detect(0, threads).ok()?;
            allowed
                .iter()
                .max_by(|a, b| probabilities[a.1 as usize].total_cmp(&probabilities[b.1 as usize]))
                .map(|(code, _)| *code)
        }
    };
    state.full(params(language), samples).ok()?;
    let text: String = state.as_iter().filter_map(|segment| segment.to_str().ok().map(str::to_owned)).collect();
    Some(text.trim().to_owned())
}

/// Audio samples per encoder frame: 16 kHz audio, 100 mel frames a second,
/// halved by the encoder's convolution.
const SAMPLES_PER_FRAME: usize = 320;

/// Encoder frames beyond the recording, a little over a second, which
/// keeps the last word from being cut off.
const AUDIO_CTX_ROOM: usize = 256;

/// The encoder's full context, 30 seconds.
const MAX_AUDIO_CTX: usize = 1500;
