// JNI bridge for the seccomp fix.
// Exposes install_seccomp_fix() to Kotlin as NativeInit.installSeccompFix().

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;

extern "C" {
    fn install_seccomp_fix();
}

#[no_mangle]
pub extern "C" fn Java_com_lightphone_spotify_NativeInit_installSeccompFix(
    _env: JNIEnv,
    _class: JClass,
) {
    unsafe { install_seccomp_fix() };
}
