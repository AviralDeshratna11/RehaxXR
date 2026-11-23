"""Bundle official version-1 Lite, Full and Heavy float16 pose tasks for offline comparison."""
from pathlib import Path
import hashlib,json,urllib.request
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets'
EXPECTED=dict(lite='59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a',full='5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1',heavy='64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b')
EXPECTED['movenet_thunder']='41641538679ec79b07d4101e591dda47d098c09af29607674b2a40b8a3798dd3'
EXPECTED['movenet_lightning']='0fac2226112d0371903ca86e3853cec24ef603a0b2f96f589b180f0ebdd135ab'

def ensure_models():
    ASSETS.mkdir(parents=True,exist_ok=True)
    catalog=[]
    for kind in ['lite','full','heavy']:
        name=f'pose_landmarker_{kind}.task'
        url=f'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_{kind}/float16/1/{name}'
        path=ASSETS/name
        if not path.exists():
            print(f'Downloading official {kind} model...',flush=True)
            temporary=path.with_suffix('.download')
            urllib.request.urlretrieve(url,temporary)
            temporary.replace(path)
        digest=hashlib.sha256(path.read_bytes()).hexdigest()
        assert digest==EXPECTED[kind],f'Unexpected {kind} model checksum'
        catalog.append(dict(id=kind,asset=name,version=1,precision='float16',url=url,sha256=digest,bytes=path.stat().st_size))
        print(f'{kind}: {path.stat().st_size} bytes, SHA-256 {digest}',flush=True)
    path=ASSETS/'movenet_thunder_float16_v4.tflite'
    url='https://tfhub.dev/google/lite-model/movenet/singlepose/thunder/tflite/float16/4?lite-format=tflite'
    if not path.exists():
        temporary=path.with_suffix('.download')
        urllib.request.urlretrieve(url,temporary)
        temporary.replace(path)
    digest=hashlib.sha256(path.read_bytes()).hexdigest()
    assert digest==EXPECTED['movenet_thunder'],'Unexpected MoveNet Thunder checksum'
    catalog.append(dict(id='movenet_thunder',asset=path.name,version=4,precision='float16',url=url,sha256=digest,bytes=path.stat().st_size,coordinates='2D only'))
    path=ASSETS/'movenet_lightning_float16_v4.tflite'
    url='https://tfhub.dev/google/lite-model/movenet/singlepose/lightning/tflite/float16/4?lite-format=tflite'
    if not path.exists():
        temporary=path.with_suffix('.download')
        urllib.request.urlretrieve(url,temporary)
        temporary.replace(path)
    digest=hashlib.sha256(path.read_bytes()).hexdigest()
    assert digest==EXPECTED['movenet_lightning'],'Unexpected MoveNet Lightning checksum'
    catalog.append(dict(id='movenet_lightning',asset=path.name,version=4,precision='float16',url=url,sha256=digest,bytes=path.stat().st_size,coordinates='2D only'))
    (ASSETS/'pose_models.json').write_text(json.dumps(dict(default='movenet_thunder',models=catalog),indent=2)+'\n',encoding='utf8')
    (ASSETS/'model.sha256').write_text(''.join(m['sha256']+'  '+m['asset']+'\n' for m in catalog))
    return catalog
if __name__=='__main__':ensure_models()
