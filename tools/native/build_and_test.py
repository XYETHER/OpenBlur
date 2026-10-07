"""Reproducible NDK build, optional physical-device smoke and ELF audit.
python tools/native/build_and_test.py --device SERIAL --sizes 128x96 130x98 1920x1080
No APK/Gradle changes; pushes only the named native test/library to /data/local/tmp.
"""
import argparse, subprocess, os, hashlib, json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--device');p.add_argument('--sizes',nargs='+',default=['128x96']);p.add_argument('--asan',action='store_true');args=p.parse_args()
base=Path(__file__).resolve().parent;project=base.parents[1]
sdk=Path(os.environ.get('ANDROID_SDK_ROOT',str(Path.home()/'AppData/Local/Android/Sdk')))
ndk=sdk/'ndk/25.1.8937393';cmake=sdk/'cmake/3.22.1/bin/cmake.exe';ninja=sdk/'cmake/3.22.1/bin/ninja.exe';adb=sdk/'platform-tools/adb.exe'
build=base/('build-asan' if args.asan else 'build-arm64');log=[]
logstem='asan-test' if args.asan else 'release-test'
def run(cmd):
    cmd=list(map(str,cmd));r=subprocess.run(cmd,cwd=project,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    text='$ '+subprocess.list2cmdline(cmd)+'\n'+r.stdout;print(text,flush=True);log.append(text)
    (base/(logstem+'.log')).write_text('\n'.join(log))
    if r.returncode:raise SystemExit(r.returncode)
    return r.stdout
flags=['-DCMAKE_CXX_FLAGS=-fsanitize=address -fno-omit-frame-pointer','-DCMAKE_SHARED_LINKER_FLAGS=-fsanitize=address','-DCMAKE_EXE_LINKER_FLAGS=-fsanitize=address'] if args.asan else []
run([cmake,'-S',project/'app/src/main/cpp','-B',build,'-G','Ninja',f'-DCMAKE_MAKE_PROGRAM={ninja}',f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake','-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-26','-DANDROID_STL=c++_static','-DCMAKE_BUILD_TYPE=Release']+flags)
run([cmake,'--build',build,'-j','4'])
readelf=ndk/'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
run([readelf,'-h','-d','-l',build/'libopenblur_mvtools.so'])
if args.device:
    run([adb,'-s',args.device,'push',build/'native_smoke',build/'libopenblur_mvtools.so','/data/local/tmp/'])
    preload=''
    if args.asan:
        runtime=next((ndk/'toolchains/llvm/prebuilt/windows-x86_64/lib64/clang').glob('*/lib/linux/libclang_rt.asan-aarch64-android.so'))
        run([adb,'-s',args.device,'push',runtime,'/data/local/tmp/']);preload=f'LD_PRELOAD=/data/local/tmp/{runtime.name} ASAN_OPTIONS=detect_leaks=0 '
    run([adb,'-s',args.device,'shell','chmod 755 /data/local/tmp/native_smoke'])
    for size in args.sizes:
        w,h=map(int,size.split('x'))
        run([adb,'-s',args.device,'shell',f'{preload}LD_LIBRARY_PATH=/data/local/tmp /data/local/tmp/native_smoke {w} {h}'])
evidence={'device':args.device,'sizes':args.sizes,'asan':args.asan,'files':{f:hashlib.sha256((build/f).read_bytes()).hexdigest() for f in ['native_smoke','libopenblur_mvtools.so']}}
(base/(logstem+'.json')).write_text(json.dumps(evidence,indent=2))
