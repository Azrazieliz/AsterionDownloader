#include <jni.h>
#include <unistd.h>
#include <errno.h>
/* yt-dlp's Android Python wrapper may ignore its absolute output override.
 * Anchor its relative temporary files in the selected private staging dir.
 */
JNIEXPORT jint JNICALL Java_com_asterion_videoflow_AsterionCwd_cd(
    JNIEnv *env, jobject self, jstring path) {
    if (path == NULL) return EINVAL;
    const char *p = (*env)->GetStringUTFChars(env,path,NULL);
    if (p == NULL) return ENOMEM;
    const int result = chdir(p);
    const int code = result == 0 ? 0 : errno;
    (*env)->ReleaseStringUTFChars(env,path,p);
    return code;
}
