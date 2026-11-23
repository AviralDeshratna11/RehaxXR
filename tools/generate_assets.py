"""Original procedural avatar for Creatofit Motion (no third-party art).

Builds app/src/main/assets/avatar.glb and avatar_regions.json.

The avatar is a stylised, rounded humanoid made of rigid segments. Every segment is a glTF node whose
local frame matches core/Body.kt: Y runs along the segment (proximal -> distal), Z is anterior,
X = Y x Z. Torso/neck/head: Y up, Z forward (X = subject's left). Limbs at rest: Y down, Z forward
(X = subject's right). Foot: Y heel -> toe, Z up (X = subject's right).
Muscle highlight patches are child nodes of their segment, each with its own material so the app can
colour them independently. Dimensions must match BodyDims in core/Body.kt.
"""
import json, math, struct, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/src/main/assets'

# ---------------------------------------------------------------------------------------------
# Mesh helpers
# ---------------------------------------------------------------------------------------------
class Mesh:
    def __init__(self):
        self.p = []; self.n = []; self.i = []
    def add(self, other):
        base = len(self.p)
        self.p += other.p; self.n += other.n; self.i += [base + k for k in other.i]
        return self
    def transformed(self, rot, offset):
        m = Mesh()
        m.p = [tuple(sum(rot[r][c] * v[c] for c in range(3)) + offset[r] for r in range(3)) for v in self.p]
        m.n = [tuple(sum(rot[r][c] * v[c] for c in range(3)) for r in range(3)) for v in self.n]
        m.i = list(self.i)
        return m

def _norm(v):
    l = math.sqrt(sum(c * c for c in v)) or 1.0
    return tuple(c / l for c in v)

def _sub(a, b): return tuple(x - y for x, y in zip(a, b))
def _cross(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

def smooth_normals(mesh):
    acc = {}
    key = lambda v: (round(v[0], 5), round(v[1], 5), round(v[2], 5))
    for t in range(0, len(mesh.i), 3):
        a, b, c = (mesh.p[mesh.i[t + k]] for k in range(3))
        fn = _cross(_sub(b, a), _sub(c, a))
        for v in (a, b, c):
            k = key(v); s = acc.get(k, (0, 0, 0)); acc[k] = (s[0] + fn[0], s[1] + fn[1], s[2] + fn[2])
    mesh.n = [_norm(acc[key(v)]) for v in mesh.p]
    return mesh

def interp(profile, y):
    """Linear interpolation of (y, rx, rz, cx, cz) rows at height y."""
    if y <= profile[0][0]: return profile[0][1:]
    for a, b in zip(profile, profile[1:]):
        if a[0] <= y <= b[0]:
            t = (y - a[0]) / ((b[0] - a[0]) or 1)
            return tuple(a[k] + (b[k] - a[k]) * t for k in range(1, 5))
    return profile[-1][1:]

def norm_profile(rows):
    """Rows may be (y, r), (y, rx, rz) or (y, rx, rz, cx, cz)."""
    out = []
    for r in rows:
        if len(r) == 2: out.append((r[0], r[1], r[1], 0.0, 0.0))
        elif len(r) == 3: out.append((r[0], r[1], r[2], 0.0, 0.0))
        else: out.append(tuple(r))
    return out

def surface(profile, y, theta):
    rx, rz, cx, cz = interp(profile, y)
    s, c = math.sin(theta), math.cos(theta)
    p = (cx + rx * s, y, cz + rz * c)
    n = _norm((s / max(rx, 1e-4), 0.0, c / max(rz, 1e-4)))
    return p, n

def loft(rows, slices=32, refine=3):
    """Closed surface of revolution with elliptical rings around Y; theta from +Z toward +X."""
    prof = norm_profile(rows)
    # Refine the profile so silhouettes stay smooth.
    ys = []
    for a, b in zip(prof, prof[1:]):
        for k in range(refine): ys.append(a[0] + (b[0] - a[0]) * k / refine)
    ys.append(prof[-1][0])
    m = Mesh()
    for y in ys:
        for i in range(slices + 1):
            p, n = surface(prof, y, 2 * math.pi * i / slices)
            m.p.append(p); m.n.append(n)
    for j in range(len(ys) - 1):
        for i in range(slices):
            a = j * (slices + 1) + i; b = a + 1; c = a + slices + 1; d = c + 1
            m.i += [a, b, c, b, d, c]
    return smooth_normals(m)

def ellipsoid(rx, ry, rz, center=(0, 0, 0), rings=18, slices=28):
    rows = []
    for k in range(rings + 1):
        t = -math.pi / 2 + math.pi * k / rings
        rows.append((center[1] + ry * math.sin(t), rx * math.cos(t), rz * math.cos(t), center[0], center[2]))
    return loft(rows, slices, refine=1)

def capsule(radius, length, slices=16):
    rows = []
    for k in range(7):
        t = -math.pi / 2 + (math.pi / 2) * k / 6
        rows.append((radius * math.sin(t), radius * math.cos(t)))
    for k in range(7):
        t = (math.pi / 2) * k / 6
        rows.append((length + radius * math.sin(t), radius * math.cos(t)))
    return loft(rows, slices, refine=1)

def rotation_y_to(d):
    """Rotation matrix taking +Y to unit vector d (minimal rotation)."""
    d = _norm(d); y = (0, 1, 0)
    v = _cross(y, d); c = sum(a * b for a, b in zip(y, d)); s = math.sqrt(sum(x * x for x in v))
    if s < 1e-6: return [[1, 0, 0], [0, 1, 0], [0, 0, 1]] if c > 0 else [[1, 0, 0], [0, -1, 0], [0, 0, -1]]
    k = [x / s for x in v]; K = [[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]]
    K2 = [[sum(K[r][q] * K[q][c2] for q in range(3)) for c2 in range(3)] for r in range(3)]
    return [[(1 if r == c2 else 0) + s * K[r][c2] + (1 - c) * K2[r][c2] for c2 in range(3)] for r in range(3)]

def patch(rows, y0, y1, theta_c, half_width, offset=.009, exponent=.55, nt=14, ns=12, taper_top=1.0, taper_bottom=1.0):
    """Muscle highlight: a spindle-shaped shell lying on the loft surface."""
    prof = norm_profile(rows)
    m = Mesh()
    for a in range(nt + 1):
        t = a / nt
        y = y0 + (y1 - y0) * t
        shape = math.sin(math.pi * t) ** exponent
        shape *= (taper_bottom + (1 - taper_bottom) * t) * (taper_top + (1 - taper_top) * (1 - t))
        hw = math.radians(half_width) * max(shape, .04)
        for b in range(ns + 1):
            s = -1 + 2 * b / ns
            p, n = surface(prof, y, math.radians(theta_c) + s * hw)
            m.p.append(tuple(p[k] + n[k] * offset for k in range(3))); m.n.append(n)
    for a in range(nt):
        for b in range(ns):
            i0 = a * (ns + 1) + b; i1 = i0 + 1; i2 = i0 + ns + 1; i3 = i2 + 1
            m.i += [i0, i1, i2, i1, i3, i2]
    p, n = surface(prof, (y0 + y1) / 2, math.radians(theta_c))
    anchor = tuple(p[k] + n[k] * (offset + .004) for k in range(3))
    return smooth_normals(m), anchor, n

# ---------------------------------------------------------------------------------------------
# Avatar shape (metres). Must agree with BodyDims in core/Body.kt.
# ---------------------------------------------------------------------------------------------
PELVIS = [(-.145, 0, 0, 0, -.005), (-.135, .095, .065, 0, -.005), (-.11, .150, .104, 0, -.008), (-.05, .170, .116, 0, -.012),
          (.03, .166, .112, 0, -.006), (.11, .150, .102, 0, 0), (.19, .142, .098, 0, 0), (.20, .124, .084, 0, 0), (.205, 0, 0, 0, 0)]
CHEST = [(.10, .134, .094, 0, 0), (.20, .138, .098, 0, 0), (.29, .156, .107, 0, .004), (.38, .180, .116, 0, .008),
         (.45, .194, .112, 0, .002), (.505, .182, .100, 0, -.004), (.545, .145, .084, 0, -.006), (.565, .088, .058, 0, -.006), (.572, 0, 0, 0, -.006)]
NECK = [(-.04, .052, .050), (.00, .054, .052), (.08, .052, .050), (.13, .050, .048)]
UPPER_ARM = [(-.062, 0), (-.054, .034), (-.038, .054), (-.012, .062), (.03, .058), (.10, .050), (.18, .046), (.25, .041), (.29, .039), (.312, .030), (.326, 0)]
FOREARM = [(-.036, 0), (-.028, .030, .032), (-.005, .041, .042), (.06, .046, .041), (.14, .038, .034), (.22, .031, .026), (.26, .029, .024), (.274, 0, 0)]
HAND = [(-.012, 0, 0), (-.005, .030, .018), (.03, .046, .024), (.09, .049, .023), (.14, .042, .020), (.168, .024, .014), (.176, 0, 0)]
THIGH = [(-.07, 0), (-.06, .05), (-.03, .080), (.01, .090), (.09, .088), (.20, .078), (.32, .064), (.40, .055), (.43, .054), (.458, .040), (.472, 0)]
SHANK = [(-.05, 0), (-.04, .040), (-.018, .053), (.03, .056, .058, 0, -.004), (.11, .058, .063, 0, -.012), (.22, .049, .050, 0, -.005),
         (.33, .038, .038), (.40, .034, .034), (.425, .026, .026), (.435, 0, 0)]
# Sole at z = -0.077 (ankle height 0.075 + 2 mm), heel 6 cm behind and toe 21 cm ahead of the ankle.
FOOT = [(y, rx, rz, cx, cz + .008) for (y, rx, rz, cx, cz) in [
        (-.072, 0, 0, 0, -.045), (-.066, .030, .030, 0, -.047), (-.048, .043, .040, 0, -.045), (0, .047, .050, 0, -.035),
        (.07, .051, .040, 0, -.045), (.15, .049, .029, 0, -.056), (.19, .040, .022, 0, -.062), (.205, .022, .014, 0, -.066), (.212, 0, 0, 0, -.068)]]
SHORTS_LEG = [(-.07, .093), (-.02, .096), (.03, .098), (.09, .094), (.15, .088), (.165, .087)]

SKIN = [.74, .69, .64, 1]      # warm light neutral (linear)
SHORTS = [.030, .036, .050, 1]
SHOES = [.022, .025, .030, 1]
SOLE = [.80, .80, .80, 1]
EYES = [.012, .014, .018, 1]
FLOOR = [.60, .62, .66, 1]

def side_theta(theta, side, lateral_positive_x):
    """Limb patch angles are authored for the right side (+X lateral for limbs); mirror for left."""
    if side == 'r': return theta if lateral_positive_x else -theta
    return -theta if lateral_positive_x else theta

def build():
    segments = {}   # name -> list of (Mesh, material)
    patches = {}    # region node -> (segment, Mesh, anchor, normal)

    segments['pelvis'] = [(loft(PELVIS, 40), 'shorts')]
    segments['chest'] = [(loft(CHEST, 40), 'skin')]
    segments['neck'] = [(loft(NECK, 24), 'skin')]
    head = ellipsoid(.112, .130, .120, (0, .118, .004), rings=22, slices=36)
    nose = ellipsoid(.013, .017, .013, (0, .094, .123), rings=8, slices=12)
    ear_l = ellipsoid(.017, .028, .013, (.111, .110, -.004), rings=8, slices=12)
    ear_r = ellipsoid(.017, .028, .013, (-.111, .110, -.004), rings=8, slices=12)
    hair = ellipsoid(.116, .074, .124, (0, .190, -.006), rings=14, slices=36)
    eyes = ellipsoid(.013, .019, .008, (.041, .128, .112), 8, 12).add(ellipsoid(.013, .019, .008, (-.041, .128, .112), 8, 12))
    segments['head'] = [(head.add(nose).add(ear_l).add(ear_r), 'skin'), (eyes, 'eyes'), (hair, 'hair')]

    for side in 'lr':
        lat = 1 if side == 'r' else -1   # +X is lateral for right limbs and feet
        segments[f'upper_arm_{side}'] = [(loft(UPPER_ARM, 28), 'skin')]
        segments[f'forearm_{side}'] = [(loft(FOREARM, 24), 'skin')]
        hand = loft(HAND, 22)
        thumb = capsule(.0125, .045, 12).transformed(rotation_y_to((lat * .55, .55, .62)), (lat * .030, .022, .010))
        segments[f'hand_{side}'] = [(hand.add(thumb), 'skin')]
        segments[f'thigh_{side}'] = [(loft(THIGH, 30), 'skin'), (loft(SHORTS_LEG, 30, refine=2), 'shorts')]
        segments[f'shank_{side}'] = [(loft(SHANK, 28), 'skin')]
        foot = loft(FOOT, 28)
        segments[f'foot_{side}'] = [(foot, 'shoes')]

        def lp(region, seg, rows, y0, y1, theta, hw, **kw):
            m, anchor, normal = patch(rows, y0, y1, side_theta(theta, side, True), hw, **kw)
            patches[f'{region}_{side}'] = (f'{seg}_{side}', m, anchor, normal)
        def tp(region, seg, rows, y0, y1, theta, hw, **kw):
            # Torso: +X is the subject's LEFT, so the right side uses negative angles.
            t = theta if side == 'l' else -theta
            m, anchor, normal = patch(rows, y0, y1, t, hw, **kw)
            patches[f'{region}_{side}'] = (seg, m, anchor, normal)

        # Torso (chest piece)
        tp('pec', 'chest', CHEST, .355, .468, 30, 34, exponent=.28, taper_top=.5)
        tp('abs', 'chest', CHEST, .13, .335, 10, 9.5, exponent=.12)
        tp('obliques', 'chest', CHEST, .12, .30, 50, 17, exponent=.45)
        tp('serratus', 'chest', CHEST, .30, .405, 73, 12, exponent=.5)
        tp('lats', 'chest', CHEST, .22, .455, 128, 27, exponent=.5, taper_bottom=.35)
        tp('trap_upper', 'chest', CHEST, .475, .566, 150, 30, exponent=.4)
        tp('trap_mid', 'chest', CHEST, .36, .475, 163, 14, exponent=.45)
        tp('erector', 'chest', CHEST, .12, .345, 171, 7.5, exponent=.2)
        # Hips (pelvis piece)
        tp('glute_max', 'pelvis', PELVIS, -.115, .065, 148, 27, exponent=.45)
        tp('glute_med', 'pelvis', PELVIS, .015, .135, 103, 16, exponent=.5)
        tp('hip_flexor', 'pelvis', PELVIS, -.06, .075, 30, 13, exponent=.5)
        # Arms
        lp('delt_front', 'upper_arm', UPPER_ARM, -.045, .115, 22, 34, exponent=.5)
        lp('delt_side', 'upper_arm', UPPER_ARM, -.05, .125, 90, 30, exponent=.5)
        lp('delt_rear', 'upper_arm', UPPER_ARM, -.045, .115, 158, 34, exponent=.5)
        lp('biceps', 'upper_arm', UPPER_ARM, .085, .265, 0, 44, exponent=.5)
        lp('triceps', 'upper_arm', UPPER_ARM, .05, .27, 180, 50, exponent=.5)
        lp('forearm_flex', 'forearm', FOREARM, -.005, .17, 50, 46, exponent=.5)
        # Legs
        lp('quads', 'thigh', THIGH, .03, .405, 0, 58, exponent=.45, offset=.016)
        lp('hamstrings', 'thigh', THIGH, .06, .39, 180, 46, exponent=.45, offset=.016)
        lp('adductors', 'thigh', THIGH, .01, .27, -90, 28, exponent=.5, offset=.016, taper_bottom=.4)
        lp('calves', 'shank', SHANK, .01, .27, 180, 56, exponent=.5)
        lp('tibialis', 'shank', SHANK, .05, .34, 32, 20, exponent=.45)

    # Floor: soft disc whose alpha fades at the rim.
    floor = Mesh(); rings, slices = 8, 64
    floor.p.append((0, 0, 0)); floor.n.append((0, 1, 0))
    for r in range(1, rings + 1):
        rad = .62 * r / rings
        for s in range(slices):
            a = 2 * math.pi * s / slices
            floor.p.append((rad * math.sin(a), 0, rad * math.cos(a))); floor.n.append((0, 1, 0))
    for s in range(slices):
        floor.i += [0, 1 + s, 1 + (s + 1) % slices]
    for r in range(1, rings):
        for s in range(slices):
            a = 1 + (r - 1) * slices + s; b = 1 + (r - 1) * slices + (s + 1) % slices
            c = a + slices; d = b + slices
            floor.i += [a, c, b, b, c, d]
    floor_alpha = [1.0] + [max(0.0, min(1.0, (1 - r / rings) * 2.2)) for r in range(1, rings + 1) for _ in range(slices)]
    return segments, patches, floor, floor_alpha

# ---------------------------------------------------------------------------------------------
# GLB writer
# ---------------------------------------------------------------------------------------------
def write_glb(segments, patches, floor, floor_alpha):
    blob = bytearray(); views = []; accessors = []
    def accessor(values, fmt, typ, count, target, minimum=None, maximum=None):
        while len(blob) % 4: blob.append(0)
        off = len(blob); blob.extend(struct.pack('<' + fmt * len(values), *values))
        views.append(dict(buffer=0, byteOffset=off, byteLength=len(blob) - off, target=target))
        acc = dict(bufferView=len(views) - 1, componentType=5126 if fmt == 'f' else 5123, count=count, type=typ)
        if minimum is not None: acc['min'] = minimum; acc['max'] = maximum
        accessors.append(acc); return len(accessors) - 1
    def primitive(mesh, material, colors=None):
        assert len(mesh.p) < 65535, 'uint16 index overflow'
        flat = [c for v in mesh.p for c in v]
        mn = [min(v[k] for v in mesh.p) for k in range(3)]; mx = [max(v[k] for v in mesh.p) for k in range(3)]
        attrs = {'POSITION': accessor(flat, 'f', 'VEC3', len(mesh.p), 34962, mn, mx),
                 'NORMAL': accessor([c for v in mesh.n for c in v], 'f', 'VEC3', len(mesh.n), 34962)}
        if colors is not None:
            attrs['COLOR_0'] = accessor([c for a in colors for c in (1, 1, 1, a)], 'f', 'VEC4', len(colors), 34962)
        return dict(attributes=attrs, indices=accessor(mesh.i, 'H', 'SCALAR', len(mesh.i), 34963), material=material)

    materials = []; index = {}
    def material(name, color, rough=.62, blend=False):
        index[name] = len(materials)
        m = dict(name=name, pbrMetallicRoughness=dict(baseColorFactor=color, metallicFactor=0, roughnessFactor=rough))
        if blend: m['alphaMode'] = 'BLEND'
        materials.append(m)
    material('skin', SKIN, .58); material('shorts', SHORTS, .8); material('shoes', SHOES, .55)
    material('eyes', EYES, .25); material('hair', [.035, .028, .024, 1], .75); material('floor', FLOOR, .95)

    meshes = []; nodes = []; scene = []; regions = {}
    for seg, parts in segments.items():
        meshes.append(dict(name=seg, primitives=[primitive(m, index[mat]) for m, mat in parts]))
        nodes.append(dict(name=seg, mesh=len(meshes) - 1, children=[]))
        scene.append(len(nodes) - 1)
    seg_node = {n['name']: k for k, n in enumerate(nodes)}
    for region, (seg, m, anchor, normal) in sorted(patches.items()):
        material(f'm_{region}', [1, .45, .2, 0], .5, blend=True)
        meshes.append(dict(name=region, primitives=[primitive(m, len(materials) - 1)]))
        nodes.append(dict(name=region, mesh=len(meshes) - 1))
        nodes[seg_node[seg]]['children'].append(len(nodes) - 1)
        regions[region] = dict(segment=seg, anchor=[round(v, 4) for v in anchor], normal=[round(v, 4) for v in normal])
    meshes.append(dict(name='floor', primitives=[primitive(floor, index['floor'])]))
    nodes.append(dict(name='floor', mesh=len(meshes) - 1)); scene.append(len(nodes) - 1)
    for n in nodes:
        if 'children' in n and not n['children']: del n['children']

    gltf = dict(asset=dict(version='2.0', generator='Creatofit original procedural avatar'), scene=0, scenes=[dict(nodes=scene)],
                nodes=nodes, meshes=meshes, materials=materials, accessors=accessors, bufferViews=views, buffers=[dict(byteLength=0)])
    while len(blob) % 4: blob.append(0)
    gltf['buffers'][0]['byteLength'] = len(blob)
    j = json.dumps(gltf, separators=(',', ':')).encode(); j += b' ' * ((-len(j)) % 4)
    glb = struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(j) + 8 + len(blob)) + struct.pack('<II', len(j), 0x4E4F534A) + j + struct.pack('<II', len(blob), 0x004E4942) + bytes(blob)
    return glb, regions

if __name__ == '__main__':
    segments, patches, floor, alpha = build()
    glb, regions = write_glb(segments, patches, floor, alpha)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'avatar.glb').write_bytes(glb)
    (OUT / 'avatar_regions.json').write_text(json.dumps(regions, indent=1) + '\n', encoding='utf8')
    tris = sum(len(m.i) // 3 for parts in segments.values() for m, _ in parts) + sum(len(p[1].i) // 3 for p in patches.values())
    print(f'avatar.glb: {len(segments)} segments, {len(patches)} muscle regions, {tris} triangles, {len(glb)} bytes')
