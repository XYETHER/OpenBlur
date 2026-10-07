"""Exercise the exact instance-method JNI ABI on ART, without installing an APK."""
from pathlib import Path
import subprocess,sys,zipfile,json
base=Path(__file__).resolve().parent
sdk=Path.home()/'AppData/Local/Android/Sdk';java=Path('C:/Program Files/Android/Android Studio/jbr/bin')
serial=sys.argv[1] if len(sys.argv)>1 else 'R5CWC31SSWT'
classes=base/'java-classes';dex=base/'java-dex';classes.mkdir(exist_ok=True);dex.mkdir(exist_ok=True)
logs=[]
def run(cmd):
    cmd=list(map(str,cmd));r=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    line='$ '+subprocess.list2cmdline(cmd)+'\n'+r.stdout;logs.append(line);print(line)
    (base/'jni-test.log').write_text('\n'.join(logs))
    if r.returncode:raise SystemExit(r.returncode)
run([java/'javac.exe','--release','8','-d',classes,base/'java/dev/motionblur/app/render/NativeMotionBlur.java'])
d8=sdk/'build-tools/36.0.0/lib/d8.jar'
if not d8.exists(): d8=sorted((sdk/'build-tools').glob('*/lib/d8.jar'))[-1]
run([java/'java.exe','-cp',d8,'com.android.tools.r8.D8','--min-api','26','--output',dex,*classes.rglob('*.class')])
jar=base/'native-jni-test.jar'
with zipfile.ZipFile(jar,'w') as z:z.write(dex/'classes.dex','classes.dex')
adb=sdk/'platform-tools/adb.exe'
run([adb,'-s',serial,'push',jar,base/'build-arm64/libopenblur_mvtools.so','/data/local/tmp/'])
run([adb,'-s',serial,'shell','CLASSPATH=/data/local/tmp/native-jni-test.jar app_process /system/bin dev.motionblur.app.render.NativeMotionBlur'])
