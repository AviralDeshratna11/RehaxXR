"""Convert opt-in Android marker captures to TRC and an OpenSim 4 IK setup.

Requires a subject-scaled .osim model with joint-center virtual markers. Marker
names and landmark IDs must be supplied explicitly: skin markers are not joint
centers. No OpenSim installation is needed for conversion; --run invokes the
installed opensim-cmd executable. Only the Python standard library is used.
"""
import argparse
import bisect
import json
import math
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def prepare(capture, model, marker_map, output, rate=30):
    if not 1 <= rate <= 120:
        raise ValueError('Sample rate must be between 1 and 120 Hz.')
    data = json.loads(Path(capture).read_text(encoding='utf-8'))
    if (data.get('format'), data.get('units'), data.get('coordinates')) != (
        'creatofit-markers-v1', 'm', 'mediapipe-camera-pelvis-relative'):
        raise ValueError('Unsupported marker capture format or coordinates.')
    mapping = json.loads(Path(marker_map).read_text(encoding='utf-8'))
    if len(mapping) < 3 or any(type(i) is not int or not 0 <= i < 33 for i in mapping.values()):
        raise ValueError('Map at least three model virtual-marker names to landmark IDs 0..32.')
    if len(set(mapping.values())) != len(mapping):
        raise ValueError('Each model marker must map to a distinct landmark.')
    model = Path(model).resolve()
    model_names = {m.get('name') for m in ET.parse(model).iter('Marker')}
    if set(mapping) - model_names:
        raise ValueError(f'Model is missing virtual markers: {sorted(set(mapping) - model_names)}')
    frames = data['frames']
    if len(frames) < 2:
        raise ValueError('Capture needs at least two frames.')
    times = [f['time'] for f in frames]
    if not all(isinstance(t, (int, float)) and math.isfinite(t) for t in times):
        raise ValueError('Nonfinite capture time.')
    if any(b <= a for a, b in zip(times, times[1:])):
        raise ValueError('Capture times must increase strictly.')
    if max(b-a for a, b in zip(times, times[1:])) > .15:
        raise ValueError('Tracking gap exceeds 150 ms. Export a continuous visible movement.')
    for frame in frames:
        if len(frame['markers']) != 33:
            raise ValueError('Expected 33 landmarks per frame.')
        for name, index in mapping.items():
            p = frame['markers'][index]
            if len(p) != 4 or not all(math.isfinite(v) for v in p) or not .55 <= p[3] <= 1:
                raise ValueError(f'Unreliable marker {name}; record again with this joint visible.')
    duration = times[-1]-times[0]
    count = math.floor(duration*rate)+1
    if count < 2:
        raise ValueError('Capture duration is too short for this sample rate.')
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    trc = output/'markers.trc'
    names = list(mapping)
    lines = [f'PathFileType\t4\t(X/Y/Z)\t{trc.name}',
             'DataRate\tCameraRate\tNumFrames\tNumMarkers\tUnits\tOrigDataRate\tOrigDataStartFrame\tOrigNumFrames',
             f'{rate}\t{rate}\t{count}\t{len(names)}\tm\t{rate}\t1\t{count}',
             'Frame#\tTime\t'+'\t'.join(name+'\t\t' for name in names),
             '\t\t'+'\t'.join(f'X{i}\tY{i}\tZ{i}' for i in range(1, len(names)+1)), '']
    for n in range(count):
        t = times[0]+n/rate
        right = min(max(1, bisect.bisect_left(times, t)), len(times)-1)
        left = right-1
        mix = (t-times[left])/(times[right]-times[left])
        row = [str(n+1), f'{n/rate:.8f}']
        for index in mapping.values():
            a, b = frames[left]['markers'][index], frames[right]['markers'][index]
            x, y, z = [a[i]+(b[i]-a[i])*mix for i in range(3)]
            # OpenSim axes: x forward (camera away), y up, z right.
            # Pelvis-relative input lacks global translation and floor height.
            row.extend(f'{v:.8f}' for v in (z, -y, x))
        lines.append('\t'.join(row))
    trc.write_text('\n'.join(lines)+'\n', encoding='utf-8')
    root = ET.Element('OpenSimDocument', Version='40000')
    tool = ET.SubElement(root, 'InverseKinematicsTool', name='creatofit')
    for key, value in {'model_file':model.as_posix(), 'marker_file':trc.as_posix(),
                       'results_directory':output.as_posix(), 'time_range':f'0 {(count-1)/rate:.8f}',
                       'output_motion_file':(output/'motion.mot').as_posix(),
                       'report_errors':'true', 'report_marker_locations':'true'}.items():
        ET.SubElement(tool, key).text = str(value)
    tasks = ET.SubElement(tool, 'IKTaskSet')
    objects = ET.SubElement(tasks, 'objects')
    for name in names:
        task = ET.SubElement(objects, 'IKMarkerTask', name=name)
        ET.SubElement(task, 'apply').text = 'true'
        ET.SubElement(task, 'weight').text = '1'
    ET.SubElement(tasks, 'groups')
    ET.indent(root)
    setup = output/'ik_setup.xml'
    ET.ElementTree(root).write(setup, encoding='utf-8', xml_declaration=True)
    return setup


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    parser.add_argument('--model', required=True, type=Path)
    parser.add_argument('--marker-map', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=Path('opensim-results'))
    parser.add_argument('--rate', type=int, default=30)
    parser.add_argument('--run', action='store_true')
    parser.add_argument('--opensim-cmd', default='opensim-cmd')
    args = parser.parse_args()
    try:
        setup = prepare(args.capture, args.model, args.marker_map, args.output, args.rate)
        print(f'Prepared {setup}. Input is camera-relative, pelvis-centered estimated marker data.')
        if args.run:
            subprocess.run([args.opensim_cmd, 'run-tool', str(setup)], check=True, cwd=setup.parent)
    except (ValueError, KeyError, OSError, ET.ParseError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'OpenSim bridge: {error}\n')


if __name__ == '__main__':
    main()
