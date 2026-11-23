"""Download pinned build tooling and the offline MediaPipe model into this workspace."""
from pathlib import Path
import urllib.request, zipfile, hashlib

ROOT = Path(__file__).resolve().parents[1]
def download(url, path):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        print('Downloading', path.name, flush=True)
        urllib.request.urlretrieve(url, path)
    return path

if __name__ == '__main__':
    archive = download('https://services.gradle.org/distributions/gradle-9.1.0-bin.zip', ROOT/'tools/gradle-9.1.0-bin.zip')
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == 'a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806', 'Unexpected Gradle distribution checksum'
    if not (ROOT/'tools/gradle-9.1.0/bin/gradle.bat').exists():
        with zipfile.ZipFile(archive) as z: z.extractall(ROOT/'tools')
    from download_pose_models import ensure_models
    ensure_models()
