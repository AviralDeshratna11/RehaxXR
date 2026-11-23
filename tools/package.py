"""Package a built debug APK and portable Android Studio sources, excluding machine-local caches."""
from pathlib import Path
import hashlib, shutil, zipfile
root=Path(__file__).resolve().parents[1]
out=root/'deliverables';out.mkdir(exist_ok=True)
apk=out/'Creatofit-debug.apk'
shutil.copy2(root/'app/build/outputs/apk/debug/app-debug.apk',apk)
files=[]
for folder in ['app/src','gradle/wrapper','docs']:
    files.extend(p for p in (root/folder).rglob('*') if p.is_file())
files.extend((root/'tools').glob('*.py'))
for name in ['.gitignore','settings.gradle.kts','build.gradle.kts','gradle.properties','gradlew','gradlew.bat','app/build.gradle.kts','README.md','THIRD_PARTY_NOTICES.md']:
    files.append(root/name)
with zipfile.ZipFile(out/'Creatofit-Android-Studio.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for p in sorted(set(files)): archive.write(p,'Creatofit/'+p.relative_to(root).as_posix())
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    assert 'assets/pose_landmarker_lite.task' in z.namelist()
    assert 'assets/avatar.glb' in z.namelist() and 'assets/avatar_regions.json' in z.namelist()
    assert any(n.startswith('lib/arm64-v8a/') for n in z.namelist())
    assert any(n.startswith('lib/x86_64/') for n in z.namelist())
    assert hashlib.sha256(z.read('assets/pose_landmarker_lite.task')).hexdigest()=='59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a'
    from download_pose_models import EXPECTED
    import json
    for model in json.loads(z.read('assets/pose_models.json'))['models']:
        assert hashlib.sha256(z.read('assets/'+model['asset'])).hexdigest()==EXPECTED[model['id']]
for p in [apk,out/'Creatofit-Android-Studio.zip']:
    digest=hashlib.sha256(p.read_bytes()).hexdigest()
    p.with_suffix(p.suffix+'.sha256').write_text(digest+'  '+p.name+'\n')
    print(p.name,p.stat().st_size,'bytes; SHA-256',digest)
