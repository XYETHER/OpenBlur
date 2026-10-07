// GPL-2.0-or-later. Compensate luma assembly follows upstream MVCompensate.cpp.
#pragma once
#include <cmath>
#include <cstring>
#include "motion_core.hpp"
#include "Overlap.h"

// Antialiased separable Catmull-Rom bicubic downsample. Not zimg; rounding can differ.
struct CubicResize {
    struct Tap { int index; double weight; };
    int sw,sh,dw,dh;
    std::vector<std::vector<Tap>> xs,ys;
    std::vector<double> scratch;
    static double cubic(double x) {
        x=std::abs(x);
        if(x<1)return (1.5*x-2.5)*x*x+1;
        if(x<2)return ((-0.5*x+2.5)*x-4)*x+2;
        return 0;
    }
    static std::vector<std::vector<Tap>> weights(int src,int dst){
        std::vector<std::vector<Tap>> result(dst);double scale=double(src)/dst,filter=std::max(1.0,scale);
        for(int i=0;i<dst;i++){
            double center=(i+0.5)*scale-0.5,sum=0;
            int first=int(std::ceil(center-2*filter)),last=int(std::floor(center+2*filter));
            for(int j=first;j<=last;j++){double v=cubic((j-center)/filter);sum+=v;result[i].push_back({std::clamp(j,0,src-1),v});}
            for(auto& t:result[i])t.weight/=sum;
        }return result;
    }
    CubicResize(int a,int b,int c,int d):sw(a),sh(b),dw(c),dh(d),xs(weights(a,c)),ys(weights(b,d)),scratch(c*b){}
    void run(const uint8_t* src,uint8_t* dst){
        for(int y=0;y<sh;y++)for(int x=0;x<dw;x++){double v=0;for(auto t:xs[x])v+=src[y*sw+t.index]*t.weight;scratch[y*dw+x]=v;}
        for(int y=0;y<dh;y++)for(int x=0;x<dw;x++){double v=0;for(auto t:ys[y])v+=scratch[t.index*dw+x]*t.weight;dst[y*dw+x]=std::clamp(int(std::lround(v)),0,255);}
    }
};
struct Diagnostic {
    int sw,sh,w,h;
    CubicResize luma,chroma;
    Pyramid prev,cur,next;
    Field backward,forward;
    OverlapWindows window{};
    OverlapsFunction overlap;
    std::vector<uint8_t> packed[3],comp;
    std::vector<uint16_t> temp;
    Diagnostic(int a,int b):sw(a),sh(b),w(std::max(64,(a/4)/2*2)),h(std::max(64,(b/4)/2*2)),
        luma(a,b,w,h),chroma(a/2,b/2,w/2,h/2),
        prev(w,h,1,analysisLevels(w,h)),cur(w,h,1,analysisLevels(w,h)),next(w,h,1,analysisLevels(w,h)),
        backward(w,h,1,8,4,analysisLevels(w,h)),forward(w,h,1,8,4,analysisLevels(w,h)),comp(w*h),temp(w*h) {
        for(auto& p:packed)p.resize(w*h*3/2);overInit(&window,8,8,4,4);overlap=selectOverlapsFunction(8,8,8,1);
    }
    ~Diagnostic(){overDeinit(&window);}
    double residual(Field& field,Pyramid& reference) {
        // NO SAD/scene fallback: every vector warps the actual neighbor.
        std::fill(temp.begin(),temp.end(),0);std::copy_n(packed[1].data(),w*h,comp.data());
        int nx=field.ad.nBlkX,ny=field.ad.nBlkY;
        auto p=reference.frames.frames[0]->planes[0];
        for(int by=0;by<ny;by++)for(int bx=0;bx<nx;bx++){
            int wi=(by==0?0:by==ny-1?6:3)+(bx==0?0:bx==nx-1?2:1);
            auto b=fgopGetBlock(&field.f,0,by*nx+bx);
            overlap(reinterpret_cast<uint8_t*>(temp.data()+by*4*w+bx*4),w*2,
              mvpGetPointer(p,b->x+b->vector.x,b->y+b->vector.y),p->nPitch,overGetWindow(&window,wi),8);
        }
        ToPixels<uint16_t,uint8_t>(comp.data(),w,reinterpret_cast<uint8_t*>(temp.data()),w*2,nx*4+4,ny*4+4,8);
        uint64_t error=0;for(int i=0;i<w*h;i++)error+=std::abs(int(comp[i])-int(packed[1][i]));
        return double(error)/(255.0*w*h);
    }
    double measure(const uint8_t* a,const uint8_t* b,const uint8_t* c) {
        const uint8_t* inputs[]={a,b,c};Pyramid* pyr[]={&prev,&cur,&next};
        for(int i=0;i<3;i++){
            luma.run(inputs[i],packed[i].data());
            for(int j=0;j<2;j++)chroma.run(inputs[i]+sw*sh+j*sw*sh/4,packed[i].data()+w*h+j*w*h/4);
            pyr[i]->fill(packed[i].data());
        }
        backward.search(cur,next,0);forward.search(cur,prev,0);
        return std::max(residual(backward,next),residual(forward,prev));
    }
};
