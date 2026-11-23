"""Validate model checksums and the avatar GLB / muscle-region contracts without an Android device."""
import hashlib, json, re, struct
from pathlib import Path
root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/main/assets'
src = root / 'app/src/main/java/com/creatofit/motion/core'
from download_pose_models import EXPECTED
catalog = json.loads((assets / 'pose_models.json').read_text())
assert catalog['default'] == 'full' and {m['id'] for m in catalog['models']} == set(EXPECTED)
for model in catalog['models']:
    assert hashlib.sha256((assets / model['asset']).read_bytes()).hexdigest() == EXPECTED[model['id']] == model['sha256']

b = (assets / 'avatar.glb').read_bytes()
magic, version, length = struct.unpack_from('<III', b)
assert magic == 0x46546c67 and version == 2 and length == len(b)
n, t = struct.unpack_from('<II', b, 12)
assert t == 0x4e4f534a
g = json.loads(b[20:20 + n])
names = [node['name'] for node in g['nodes']]
assert len(names) == len(set(names)), 'duplicate node names'

segments = set(re.findall(r'\("([a-z_]+)"\)', re.search(r'enum class Seg\(.*?\{(.*?);', (src / 'Body.kt').read_text(), re.S).group(1)))
assert len(segments) == 16 and segments <= set(names), segments - set(names)
regions = json.loads((assets / 'avatar_regions.json').read_text())
assert set(regions) <= set(names)
for name, r in regions.items():
    assert r['segment'] in segments, name
    node = next(x for x in g['nodes'] if x['name'] == r['segment'])
    assert names.index(name) in node.get('children', []), f'{name} is not a child of {r["segment"]}'
catalogue = set(re.findall(r'MuscleGroup\.[A-Z_]+, "([a-z_]+)"', (src / 'Muscles.kt').read_text()))
for region in catalogue:
    for side in 'lr':
        assert f'{region}_{side}' in regions, f'missing mesh region {region}_{side}'
materials = [m['name'] for m in g['materials']]
assert len({f'm_{r}' for r in regions} & set(materials)) == len(regions), 'each region needs its own material'
print(f'PASS: model checksums; valid GLB with {len(segments)} segments and {len(regions)} independently coloured muscle regions; '
      f'all {len(catalogue)} catalogue regions present on both sides.')
