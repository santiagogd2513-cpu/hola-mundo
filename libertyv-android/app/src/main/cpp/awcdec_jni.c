#include <jni.h>

#include "awcdec_core.h"

JNIEXPORT jlong JNICALL
Java_com_libertyv_app_audio_NativeAudio_nativeInit(JNIEnv *env, jclass clazz, jboolean is_mp3, jint sample_rate) {
    (void) env;
    (void) clazz;
    return (jlong) (intptr_t) awc_dec_init(is_mp3 ? 1 : 0, sample_rate);
}

JNIEXPORT jshortArray JNICALL
Java_com_libertyv_app_audio_NativeAudio_nativeFeed(JNIEnv *env, jclass clazz, jlong handle,
                                                   jbyteArray data, jint off, jint len) {
    (void) clazz;
    awc_dec *d = (awc_dec *) (intptr_t) handle;
    if (!d) return NULL;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return NULL;
    int n = awc_dec_feed(d, (const uint8_t *) bytes + off, len);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (n < 0) n = 0;
    jshortArray out = (*env)->NewShortArray(env, n);
    if (out && n > 0) (*env)->SetShortArrayRegion(env, out, 0, n, (const jshort *) awc_dec_output(d));
    return out;
}

JNIEXPORT jint JNICALL
Java_com_libertyv_app_audio_NativeAudio_nativeErrors(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    awc_dec *d = (awc_dec *) (intptr_t) handle;
    return d ? awc_dec_errors(d) : 0;
}

JNIEXPORT void JNICALL
Java_com_libertyv_app_audio_NativeAudio_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    awc_dec_free((awc_dec *) (intptr_t) handle);
}
