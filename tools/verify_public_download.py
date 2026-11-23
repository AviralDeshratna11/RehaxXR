"""Verify the public tester page and reconstruct its exact APK over HTTPS without saving footage or installing anything."""
import hashlib,json,sys,urllib.request
from urllib.parse import urljoin,urlparse
base=sys.argv[1].rstrip('/')+'/'
assert urlparse(base).scheme=='https'
def fetch(path):
    url=urljoin(base,path)
    assert urlparse(url).netloc==urlparse(base).netloc
    return urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=40)
with fetch('') as r:
    html=r.read().decode()
assert 'Download Android APK' in html and 'download.js' in html
with fetch('release.json') as r: release=json.load(r)
expected='6272821345fe21a14660f967e0280469f5dc34791effd94bd8ab69754b9a41b4'
assert release['sha256']==expected and release['version']=='0.1.2'
digest=hashlib.sha256();received=0
for i,part in enumerate(release['parts'],1):
    count=0
    with fetch(part['path']) as response:
        while block:=response.read(1024*1024):
            digest.update(block);count+=len(block);received+=len(block)
    assert count==part['bytes']
    print(f'Verified hosted download part {i}/{len(release["parts"])}',flush=True)
assert received==release['bytes'] and digest.hexdigest()==expected
print(json.dumps(dict(url=base,version=release['version'],bytes=received,sha256=digest.hexdigest(),result='PASS: public page and hosted APK match the signed testing build')))
