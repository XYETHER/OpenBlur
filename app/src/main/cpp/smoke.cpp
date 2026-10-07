#include <cstdio>
#include <vector>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <cmath>
#include <chrono>
#include <exception>
extern "C" void* ob_create(int,int,int);
extern "C" void ob_process(void*,const uint8_t*,const uint8_t*,const uint8_t*,float,uint8_t*);
extern "C" void ob_destroy(void*);
extern "C" void* ob_adaptive_create(int,int,int,float);
extern "C" void ob_adaptive_process(void*,const uint8_t*,const uint8_t*,const uint8_t*,double,uint8_t*);
extern "C" double ob_adaptive_error(void*);
extern "C" float ob_adaptive_strength(void*);
extern "C" void ob_adaptive_destroy(void*);
static void require(bool value,const char* why){if(!value){fprintf(stderr,"FAIL %s\n",why);exit(1);}}
static void texture(std::vector<uint8_t>& p,int w,int h,int shift){
 for(int y=0;y<h;y++)for(int x=0;x<w;x++)p[y*w+x]=(((x+shift)/8+y/8)%2)*120+30;
}
int main(int argc,char** argv){
 int w=argc>1?atoi(argv[1]):128,h=argc>2?atoi(argv[2]):96;
 std::vector<uint8_t>a(w*h*3/2,128),b=a,c=a,out=a;
 texture(a,w,h,0);texture(b,w,h,4);texture(c,w,h,8);
 for(int q=0;q<3;q++){
  auto start=std::chrono::steady_clock::now();void* s=ob_create(w,h,q);
  ob_process(s,b.data(),b.data(),b.data(),100,out.data());require(out==b,"static identity");
  ob_process(s,a.data(),b.data(),c.data(),200,out.data());int changed=0;for(size_t i=0;i<b.size();i++)changed+=out[i]!=b[i];
  require(changed>0,"translation changes output");
  require(!memcmp(out.data()+w*h,b.data()+w*h,w*h/2),"neutral chroma preserved");
  ob_process(s,a.data(),b.data(),c.data(),0,out.data());require(out==b,"strength zero identity");
  ob_destroy(s);
  double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
  printf("%dx%d q=%d static PASS; translated changed=%d; neutral chroma PASS; zero PASS; raw create+3calls %.3fms\n",w,h,q,changed,ms);
 }
 auto start=std::chrono::steady_clock::now();void* d=ob_adaptive_create(w,h,1,100);
 ob_adaptive_process(d,a.data(),b.data(),c.data(),1.0/30,out.data());
 double good=ob_adaptive_error(d);float before=ob_adaptive_strength(d);
 printf("dynamic translation residual=%.8f strength=%.4f\n",good,before);require(good<.02,"compensated translation confidence");
 std::vector<uint8_t> dark(a.size(),16),bright(a.size(),235);
 ob_adaptive_process(d,dark.data(),b.data(),bright.data(),1.0/30,out.data());
 double bad=ob_adaptive_error(d);float protectedValue=ob_adaptive_strength(d);
 printf("dynamic mismatch residual=%.8f strength=%.4f\n",bad,protectedValue);require(bad>.065&&protectedValue==10,"immediate mismatch protection");
 ob_adaptive_process(d,a.data(),b.data(),c.data(),.02,out.data());
 float recovered=ob_adaptive_strength(d);printf("dynamic recovery at20ms strength=%.4f\n",recovered);require(std::abs(recovered-19)<.01,"PTS-based recovery");
 ob_adaptive_process(d,b.data(),b.data(),c.data(),.02,out.data());require(ob_adaptive_strength(d)==10,"isolated cut boundary protection");
 for(int i=0;i<12;i++)ob_adaptive_process(d,a.data(),b.data(),c.data(),.02,out.data());
 require(ob_adaptive_strength(d)>99,"eventual recovery");
 bool rejected=false;try{ob_adaptive_process(d,a.data(),b.data(),c.data(),NAN,out.data());}catch(const std::exception&){rejected=true;}require(rejected,"nonfinite elapsed rejected");
 ob_adaptive_destroy(d);
 printf("dynamic repeated-session/boundary/invalid-time PASS; total %.3fms\n",std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count());
 printf("ALL PASS\n");return 0;
}
