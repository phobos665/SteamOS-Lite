#include <jni.h>

/* Orders the controller ring's payload writes before the sequence number that publishes them, so
 * the reader in the Linux session never sees a sequence number ahead of its data. Paired with the
 * acquire loads in libfakeinput.so. */
JNIEXPORT void JNICALL
Java_com_steamoslite_input_FakeInputWriter_nativeStoreFence(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    __atomic_thread_fence(__ATOMIC_RELEASE);
}
