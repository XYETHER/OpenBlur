// GPL-2.0-or-later; JNI boundary validates and owns handles, never exposes raw pointers.
#include <jni.h>
#include <mutex>
#include <unordered_map>
#include "adaptive.hpp"
namespace {
std::mutex guard;
std::unordered_map<jlong,std::unique_ptr<AdaptiveSession>> sessions;
jlong nextId=1;
void raise(JNIEnv* env,const char* type,const char* message){jclass cls=env->FindClass(type);if(cls)env->ThrowNew(cls,message);}
void validate(int w,int h,int q,float strength){
    if(w<64||h<64||w>4096||h>4096||(w&1)||(h&1)||q<0||q>2||!std::isfinite(strength)||strength<10||strength>200)
        throw std::invalid_argument("Even I420 dimensions 64..4096, quality 0..2 and maxStrength 10..200 required");
    // Bound the large pel=4 pyramid, finest planes and full-resolution vector working set.
    size_t estimate=size_t(w)*h*(q==2?160:72)+32*1024*1024;
    if(estimate>768ULL*1024*1024)throw std::invalid_argument("Resolution/quality exceeds native 768 MiB working-set budget");
}
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_motionblur_app_render_NativeMotionBlur_create(JNIEnv* env,jobject,jint w,jint h,jint q,jfloat maximum){
    try{validate(w,h,q,maximum);std::lock_guard<std::mutex> lock(guard);auto s=std::make_unique<AdaptiveSession>(w,h,q,maximum);jlong id=nextId++;sessions.emplace(id,std::move(s));return id;}
    catch(const std::bad_alloc&){raise(env,"java/lang/OutOfMemoryError","MVTools session allocation failed");}
    catch(const std::exception& e){raise(env,"java/lang/IllegalArgumentException",e.what());}return 0;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_motionblur_app_render_NativeMotionBlur_process(JNIEnv* env,jobject,jlong id,jbyteArray previous,jbyteArray current,jbyteArray next,jdouble dt){
    try{
        std::lock_guard<std::mutex> lock(guard);auto it=sessions.find(id);if(it==sessions.end())throw std::invalid_argument("Invalid or closed MVTools session");
        auto& s=*it->second;jsize size=s.blur.w*s.blur.h*3/2;
        if(!previous||!current||!next||env->GetArrayLength(previous)!=size||env->GetArrayLength(current)!=size||env->GetArrayLength(next)!=size)throw std::invalid_argument("Expected three exact packed I420 frames");
        // Region copies avoid pinning the JVM heap throughout lengthy CPU processing.
        std::vector<uint8_t> input(size_t(size)*3),output(size);
        env->GetByteArrayRegion(previous,0,size,reinterpret_cast<jbyte*>(input.data()));
        env->GetByteArrayRegion(current,0,size,reinterpret_cast<jbyte*>(input.data()+size));
        env->GetByteArrayRegion(next,0,size,reinterpret_cast<jbyte*>(input.data()+2*size));
        if(env->ExceptionCheck())return nullptr;
        s.process(input.data(),input.data()+size,input.data()+2*size,dt,output.data());
        jbyteArray result=env->NewByteArray(size);if(result)env->SetByteArrayRegion(result,0,size,reinterpret_cast<const jbyte*>(output.data()));return result;
    }catch(const std::bad_alloc&){raise(env,"java/lang/OutOfMemoryError","MVTools frame allocation failed");}
    catch(const std::exception& e){raise(env,"java/lang/IllegalArgumentException",e.what());}return nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_dev_motionblur_app_render_NativeMotionBlur_destroy(JNIEnv*,jobject,jlong id){std::lock_guard<std::mutex> lock(guard);sessions.erase(id);}
// Standalone smoke ABI exercises same adaptive class as JNI, not a separate algorithm.
extern "C" void* ob_adaptive_create(int w,int h,int q,float max){validate(w,h,q,max);return new AdaptiveSession(w,h,q,max);}
extern "C" void ob_adaptive_process(void* p,const uint8_t* a,const uint8_t* b,const uint8_t* c,double dt,uint8_t* out){static_cast<AdaptiveSession*>(p)->process(a,b,c,dt,out);}
extern "C" double ob_adaptive_error(void* p){return static_cast<AdaptiveSession*>(p)->error;}
extern "C" float ob_adaptive_strength(void* p){return static_cast<AdaptiveSession*>(p)->strength;}
extern "C" void ob_adaptive_destroy(void* p){delete static_cast<AdaptiveSession*>(p);}
