#include <cstddef>
#include <cstdlib>
#include "DCTFFTW.h"
// dct=0 is the only exposed mode. Fail closed if a future caller violates it.
void dctInit(DCTFFTW*, int,int,int,int) { std::abort(); }
void dctDeinit(DCTFFTW*) { std::abort(); }
void dctBytes2D(DCTFFTW*,const uint8_t*,ptrdiff_t,uint8_t*,ptrdiff_t) { std::abort(); }
