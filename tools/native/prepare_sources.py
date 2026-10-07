"""Vendor pinned MVTools CPU sources. Run from any directory; no runtime dependencies."""
from pathlib import Path
import subprocess, shutil, hashlib, json
BASE = Path(__file__).resolve().parent
OUT = BASE.parents[1] / 'app/src/main/cpp/vendor'
PINS = {'mvtools': ('https://github.com/dubhater/vapoursynth-mvtools.git', '17250aa979616ac48dfb0e18abfdcf2bd4e3afc0'), 'vapoursynth': ('https://github.com/vapoursynth/vapoursynth.git', 'c5e387f63d7c4ad452bd9e9f57834666dfce6041')}
for name,(url,rev) in PINS.items():
    src=BASE/'upstream'/name
    if not src.exists():
        subprocess.run(['git','clone',url,str(src)],check=True)
    subprocess.run(['git','-C',str(src),'checkout','--detach',rev],check=True)
    dest=OUT/name
    dest.mkdir(parents=True,exist_ok=True)
    if name=='mvtools':
        shutil.copytree(src/'src',dest/'src',dirs_exist_ok=True)
        shutil.copy2(src/'LICENSE',dest/'LICENSE')
        text=(src/'src/MVFlowBlur.cpp').read_text(encoding='utf-8')
        kernel=text[:text.index('#include <climits>')] + '#include <cstdlib>\n#include <cstddef>\n#include <cstdint>\n#include <VSHelper4.h>\n#include "CommonFunctions.h"\n' + text[text.index('template<typename PixelType>'):text.index('static const VSFrame *VS_CC mvflowblurGetFrame')]
        (OUT.parent/'flowblur_kernel.inc').write_text('// Extracted unchanged pixel kernel from pinned MVFlowBlur.cpp by prepare_sources.py\n'+kernel)
    else:
        shutil.copytree(src/'include',dest/'include',dirs_exist_ok=True)
        for f in src.glob('COPYING*'): shutil.copy2(f,dest/f.name)
manifest={'pins':PINS, 'sha256':{str(p.relative_to(OUT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in OUT.rglob('*') if p.is_file() and p.name != 'manifest.json'}}
(OUT/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print('Vendored',len(manifest['sha256']),'files in',OUT)
