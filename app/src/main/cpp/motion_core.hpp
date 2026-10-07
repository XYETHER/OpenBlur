// OpenBlur adapter: GPL-2.0-or-later. Motion algorithms are pinned MVTools, not replacements.
#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>
#include <memory>
#include <stdexcept>
#include <algorithm>
#include "GroupOfPlanes.h"
#include "MaskFun.h"
#include "SimpleResize.h"
#include "CPU.h"

struct Pyramid {
    MVGroupOfFrames frames{};
    std::vector<uint8_t> data[3];
    int w,h,pel,levels;
    Pyramid(int width,int height,int p,int n):w(width),h(height),pel(p),levels(n) {
        mvgofInit(&frames,n,w,h,pel,16,16,YUVPLANES,1,2,2,8);
        uint8_t* ptr[3]; ptrdiff_t stride[3];
        for(int i=0;i<3;i++) {
            stride[i]=(( (i?w/2:w)+(i?16:32)+63)/64)*64;
            size_t bytes=PlaneSuperOffset(i!=0,h,n,pel,16,stride[i],2)+stride[i]*64;
            data[i].resize(bytes);ptr[i]=data[i].data();
        }
        mvgofUpdate(&frames,ptr,stride);
    }
    ~Pyramid(){mvgofDeinit(&frames);}
    void fill(const uint8_t* src) {
        mvgofResetState(&frames);
        for(int i=0;i<3;i++) {
            mvfFillPlane(frames.frames[0],src,i?w/2:w,i);
            src+=(i?w*h/4:w*h);
        }
        mvgofReduce(&frames,YUVPLANES,RfilterBilinear);
        mvgofPad(&frames,YUVPLANES);
        mvgofRefine(&frames,YUVPLANES,SharpBicubic);
    }
};
struct Field {
    GroupOfPlanes g{};
    FakeGroupOfPlanes f{};
    MVAnalysisData ad{};
    std::vector<uint8_t> bytes;
    Field(int w,int h,int pel,int block,int overlap,int levels) {
        ad.nWidth=w;ad.nHeight=h;ad.nPel=pel;ad.nBlkSizeX=ad.nBlkSizeY=block;
        ad.nOverlapX=ad.nOverlapY=overlap;ad.nBlkX=(w-overlap)/(block-overlap);ad.nBlkY=(h-overlap)/(block-overlap);
        ad.nLvCount=levels;ad.xRatioUV=ad.yRatioUV=2;ad.bitsPerSample=8;
        ad.nHPadding=ad.nVPadding=16;ad.nDeltaFrame=1;
        ad.nMotionFlags=MOTION_USE_SIMD|MOTION_USE_CHROMA_MOTION;ad.nCPUFlags=cpu_detect();
        gopInit(&g,block,block,levels,pel,ad.nMotionFlags,ad.nCPUFlags,overlap,overlap,ad.nBlkX,ad.nBlkY,2,2,0,8);
        fgopInit(&f,&ad);bytes.resize(gopGetArraySize(&g));
    }
    ~Field(){fgopDeinit(&f);gopDeinit(&g);}
    void search(Pyramid& src,Pyramid& ref,int quality) {
        int area=ad.nBlkSizeX*ad.nBlkSizeY;
        gopSearchMVs(&g,&src.frames,&ref.frames,quality?SearchHex2:SearchExhaustive,2,ad.nPel,
            1000*area/64,1200*area/64,50,1,1,bytes.data(),0,nullptr,0,50,0,10000LL*area/64,24,1,quality?1:0,SearchExhaustive);
        fgopUpdate(&f,bytes.data());
    }
    void recalculate(Field& old,Pyramid& src,Pyramid& ref) {
        gopRecalculateMVs(&g,&old.f,&src.frames,&ref.frames,SearchHex2,2,250,50,bytes.data(),0,74,nullptr,0,1,1);
        fgopUpdate(&f,bytes.data());
    }
    bool usable() {
        int64_t t1=400;int t2=130;char error[128]={};
        scaleThSCD(&t1,&t2,&ad,"FlowBlur",error,sizeof(error));
        return fgopIsUsable(&f,t1,t2);
    }
};
inline int analysisLevels(int w,int h) {
    int bx=(w-4)/4,by=(h-4)/4,n=0;
    int coveredW=4*bx+4,coveredH=4*by+4;
    while(((coveredW>>n)-4)/4>0 && ((coveredH>>n)-4)/4>0) ++n;
    return n;
}
struct BlurSession {
    int w,h,quality,pel,levels;
    bool available=false;
    Pyramid previous,current,next;
    Field backward,forward;
    std::unique_ptr<Field> refinedB,refinedF;
    SimpleResize resize[2]{};
    std::vector<int16_t> small[4],full[4],smallUV[4],fullUV[4];
    std::vector<uint8_t> finest[3];
    BlurSession(int width,int height,int q):w(width),h(height),quality(q),pel(q==2?4:2),levels(analysisLevels(w,h)),
        previous(w,h,pel,levels),current(w,h,pel,levels),next(w,h,pel,levels),
        backward(w,h,pel,8,4,levels),forward(w,h,pel,8,4,levels) {
        if(q==2){refinedB=std::make_unique<Field>(w,h,pel,4,2,1);refinedF=std::make_unique<Field>(w,h,pel,4,2,1);}
        auto& a=q==2?refinedB->ad:backward.ad;
        for(int i=0;i<2;i++) simpleInit(&resize[i],i?w/2:w,i?h/2:h,a.nBlkX,a.nBlkY,i?w/2:w,i?h/2:h,pel,1);
        for(int j=0;j<4;j++){small[j].resize(a.nBlkX*a.nBlkY);smallUV[j].resize(small[j].size());full[j].resize(w*h);fullUV[j].resize(w*h/4);}
        for(int i=0;i<3;i++){auto p=current.frames.frames[0]->planes[i];finest[i].resize(p->nPaddedWidth*p->nPaddedHeight*pel*pel);}
    }
    ~BlurSession(){for(auto& r:resize)simpleDeinit(&r);}
    void process(const uint8_t*,const uint8_t*,const uint8_t*,float,uint8_t*);
    void render(float,uint8_t*);
};
