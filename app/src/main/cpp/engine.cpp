// GPL-2.0-or-later; see vendor/mvtools/LICENSE.
#include <cstring>
#include <cmath>
#include "motion_core.hpp"
#include "flowblur_kernel.inc"
void BlurSession::process(const uint8_t* prev,const uint8_t* cur,const uint8_t* nxt,float strength,uint8_t* out) {
    if(!std::isfinite(strength)||strength<0||strength>200)throw std::invalid_argument("strength must be 0..200");
    if(strength==0){memcpy(out,cur,w*h*3/2);return;}
    previous.fill(prev);current.fill(cur);next.fill(nxt);
    // Exact upstream MVFlowBlur neighbor-vector orientation.
    backward.search(previous,current,quality);forward.search(next,current,quality);
    Field *b=&backward,*f=&forward;
    if(quality==2){refinedB->recalculate(backward,previous,current);refinedF->recalculate(forward,next,current);b=refinedB.get();f=refinedF.get();}
    available=b->usable()&&f->usable();
    if(!available){memcpy(out,cur,w*h*3/2);return;}
    int nx=b->ad.nBlkX,ny=b->ad.nBlkY;
    MakeVectorSmallMasks(&b->f,nx,ny,small[0].data(),nx,small[1].data(),nx);
    MakeVectorSmallMasks(&f->f,nx,ny,small[2].data(),nx,small[3].data(),nx);
    for(int k=0;k<4;k++) {
        resize[0].simpleResize_int16_t(&resize[0],full[k].data(),w,small[k].data(),nx,k%2==0);
        VectorSmallMaskYToHalfUV(small[k].data(),nx,ny,smallUV[k].data(),2);
        resize[1].simpleResize_int16_t(&resize[1],fullUV[k].data(),w/2,smallUV[k].data(),nx,k%2==0);
    }
    for(int i=0;i<3;i++) {
        auto p=current.frames.frames[0]->planes[i];
        int fw=p->nPaddedWidth*pel,fh=p->nPaddedHeight*pel;
        if(pel==2) Merge4PlanesToBig(finest[i].data(),fw,p->pPlane[0],p->pPlane[1],p->pPlane[2],p->pPlane[3],p->nPaddedWidth,p->nPaddedHeight,p->nPitch,8);
        else Merge16PlanesToBig(finest[i].data(),fw,p->pPlane[0],p->pPlane[1],p->pPlane[2],p->pPlane[3],p->pPlane[4],p->pPlane[5],p->pPlane[6],p->pPlane[7],p->pPlane[8],p->pPlane[9],p->pPlane[10],p->pPlane[11],p->pPlane[12],p->pPlane[13],p->pPlane[14],p->pPlane[15],p->nPaddedWidth,p->nPaddedHeight,p->nPitch,8);
    }
    render(strength,out);
}
void BlurSession::render(float strength,uint8_t* out) {
    if(!available)throw std::logic_error("no prepared vectors");
    for(int i=0;i<3;i++) {
        int fw=current.frames.frames[0]->planes[i]->nPaddedWidth*pel;
        auto* v=i?fullUV:full;
        int pw=i?w/2:w,ph=i?h/2:h,pad=i?8:16;
        FlowBlur(out,pw,finest[i].data()+pad*pel*fw+pad*pel,fw,v[0].data(),v[2].data(),v[1].data(),v[3].data(),pw,pw,ph,int(strength*256.0f/200),quality?1:2,pel,8);
        out+=pw*ph;
    }
}
extern "C" void* ob_create(int w,int h,int quality){
    if(w<64||h<64||w>4096||h>4096||(w&1)||(h&1)||quality<0||quality>2)throw std::invalid_argument("unsupported dimensions or quality");
    return new BlurSession(w,h,quality);
}
extern "C" void ob_process(void* s,const uint8_t* p,const uint8_t* c,const uint8_t* n,float strength,uint8_t* out){static_cast<BlurSession*>(s)->process(p,c,n,strength,out);}
extern "C" void ob_destroy(void* s){delete static_cast<BlurSession*>(s);}
