// Extracted unchanged pixel kernel from pinned MVFlowBlur.cpp by prepare_sources.py
// Make a motion compensate temporal denoiser
// Copyright(c)2006 A.G.Balakhnin aka Fizick
// See legal notice in Copying.txt for more information

// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA, or visit
// http://www.gnu.org/copyleft/gpl.html .

#include <cstdlib>
#include <cstddef>
#include <cstdint>
#include <VSHelper4.h>
#include "CommonFunctions.h"
template<typename PixelType>
static void RealFlowBlur(uint8_t * VS_RESTRICT pdst8, ptrdiff_t dst_pitch, const uint8_t * VS_RESTRICT pref8, ptrdiff_t ref_pitch,
                         const int16_t *VXFullB, const int16_t *VXFullF, const int16_t *VYFullB, const int16_t *VYFullF,
                         int VPitch, int width, int height, int blur256, int prec, int nPel) {
    const PixelType *pref = (const PixelType *)pref8;
    PixelType *pdst = (PixelType *)pdst8;

    ref_pitch /= sizeof(PixelType);
    dst_pitch /= sizeof(PixelType);

    int nPelLog = ilog2(nPel);

    /* very slow, but precise motion blur */
    for (int h = 0; h < height; h++) {
        for (int w = 0; w < width; w++) {
            int bluredsum = pref[w << nPelLog];
            int vxF0 = VXFullF[w] * blur256;
            int vyF0 = VYFullF[w] * blur256;
            int mF = (VSMAX(abs(vxF0), abs(vyF0)) / prec) >> 8;
            if (mF > 0) {
                vxF0 /= mF;
                vyF0 /= mF;
                int vxF = vxF0;
                int vyF = vyF0;
                for (int i = 0; i < mF; i++) {
                    int dstF = pref[(vyF >> 8) * ref_pitch + (vxF >> 8) + (w << nPelLog)];
                    bluredsum += dstF;
                    vxF += vxF0;
                    vyF += vyF0;
                }
            }
            int vxB0 = VXFullB[w] * blur256;
            int vyB0 = VYFullB[w] * blur256;
            int mB = (VSMAX(abs(vxB0), abs(vyB0)) / prec) >> 8;
            if (mB > 0) {
                vxB0 /= mB;
                vyB0 /= mB;
                int vxB = vxB0;
                int vyB = vyB0;
                for (int i = 0; i < mB; i++) {
                    int dstB = pref[(vyB >> 8) * ref_pitch + (vxB >> 8) + (w << nPelLog)];
                    bluredsum += dstB;
                    vxB += vxB0;
                    vyB += vyB0;
                }
            }
            pdst[w] = bluredsum / (mF + mB + 1);
        }
        pdst += dst_pitch;
        pref += (ref_pitch << nPelLog);
        VXFullB += VPitch;
        VYFullB += VPitch;
        VXFullF += VPitch;
        VYFullF += VPitch;
    }
}


static void FlowBlur(uint8_t *pdst, ptrdiff_t dst_pitch, const uint8_t *pref, ptrdiff_t ref_pitch,
                     const int16_t *VXFullB, const int16_t *VXFullF, const int16_t *VYFullB, const int16_t *VYFullF,
                     int VPitch, int width, int height, int blur256, int prec, int nPel, int bitsPerSample) {
    if (bitsPerSample == 8)
        RealFlowBlur<uint8_t>(pdst, dst_pitch, pref, ref_pitch, VXFullB, VXFullF, VYFullB, VYFullF, VPitch, width, height, blur256, prec, nPel);
    else
        RealFlowBlur<uint16_t>(pdst, dst_pitch, pref, ref_pitch, VXFullB, VXFullF, VYFullB, VYFullF, VPitch, width, height, blur256, prec, nPel);
}


