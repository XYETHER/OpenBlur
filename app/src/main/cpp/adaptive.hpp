// GPL-2.0-or-later. Adapter scheduling/policy, not a replacement motion estimator.
#pragma once
#include "diagnostic.hpp"
struct AdaptiveSession {
    BlurSession blur;
    Diagnostic diagnostic;
    float maximum,strength;
    double error=0;
    bool started=false;
    std::vector<uint8_t> upper;
    AdaptiveSession(int w,int h,int q,float maximum):blur(w,h,q),diagnostic(w,h),maximum(maximum),strength(10),upper(w*h*3/2){}
    void process(const uint8_t* p,const uint8_t* c,const uint8_t* n,double dt,uint8_t* out){
        if(!std::isfinite(dt)||dt<0||dt>10)throw std::invalid_argument("elapsedSeconds must be finite in 0..10");
        error=diagnostic.measure(p,c,n);
        double confidence=std::clamp((0.065-error)/0.045,0.0,1.0);
        float target=10+(maximum-10)*confidence;
        size_t size=upper.size();
        // Parent isolates cut neighbors by duplicating current. Such boundaries reset protection.
        bool boundary=memcmp(p,c,size)==0||memcmp(n,c,size)==0;
        if(boundary)target=10;
        strength=started?std::min(target,float(strength+(maximum-10)*dt/0.2)):target;
        started=true;
        constexpr float levels[]={10,25,50,100,200};float lo=10,hi=10;
        for(int i=1;i<5;i++){lo=levels[i-1];hi=levels[i];if(strength<=hi)break;}
        blur.process(p,c,n,lo,out);
        if(strength>lo&&blur.available){
            blur.render(hi,upper.data());
            // Same-frame blur-level mixture, never temporal source crossfade.
            int weight=std::clamp(int(std::lround((strength-lo)/(hi-lo)*32768)),0,32768);
            for(size_t i=0;i<size;i++)out[i]=(out[i]*(32768-weight)+upper[i]*weight+16384)>>15;
        }
    }
};
