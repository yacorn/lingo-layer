"""Exercise the installed release APK through its own UI, including ORT JNI and cache."""
import argparse
import json
import re
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('serial')
parser.add_argument('--adb', default='adb')
args = parser.parse_args()
package = 'dev.personal.screen_translator'
remote = '/sdcard/screen-translator-test-ui.xml'


def adb(*command):
    return subprocess.check_output([args.adb, '-s', args.serial, *command], encoding='utf-8')


def own_ui():
    assert adb('shell', 'pidof', package).strip(), 'Translation app exited'
    activity = adb('shell', 'dumpsys', 'activity', 'activities')
    assert any(package + '/.MainActivity' in line and 'ResumedActivity' in line for line in activity.splitlines()), 'Translation app is no longer foreground'
    try:
        adb('shell', 'uiautomator', 'dump', remote)
        root = ET.fromstring(adb('shell', 'cat', remote))
        nodes = [n.attrib for n in root.iter('node') if n.get('package') == package]
        assert nodes, 'Translation app UI is unavailable'
        return nodes
    finally:
        adb('shell', 'rm', '-f', remote)


def label(node):
    return node.get('content-desc') or node.get('text', '')


def tap(nodes, text):
    node = next(n for n in nodes if label(n).split('\n')[0] == text)
    assert node['enabled'] == 'true' and node['clickable'] == 'true', text
    left, top, right, bottom = map(int, re.findall(r'\d+', node['bounds']))
    adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))


def scroll_up(nodes, distance=None):
    bounds = [list(map(int, re.findall(r'\d+', n['bounds']))) for n in nodes]
    left = min(b[0] for b in bounds); right = max(b[2] for b in bounds)
    top = min(b[1] for b in bounds); bottom = max(b[3] for b in bounds)
    start = bottom - 150
    end = max(top + 150, start - distance) if distance else (top + bottom) // 2
    adb('shell', 'input', 'swipe', str((left + right) // 2), str(start), str(end), '400')
    return own_ui()


policy = adb('shell', 'dumpsys', 'window', 'policy')
assert not re.search(r'(?m)^\s*(?:showing|mIsShowing|inputRestricted|mInputRestricted)=true\s*$', policy), 'Unlock the phone before running this test'
assert 'SCREEN_STATE_OFF' not in policy, 'Turn the phone screen on before running this test'
adb('shell', 'am', 'force-stop', package)
adb('shell', 'am', 'start', '-W', '-n', package + '/.MainActivity')
nodes = own_ui()
tap(nodes, '직접 번역')
nodes = own_ui()
assert any(n.get('text') == '今日はいい天気ですね。少し散歩しませんか？' for n in nodes)
records = []
for cached in (False, True):
    if not any(label(n) == '이 문장 번역' for n in nodes):
        tap(nodes, '홈'); nodes = own_ui()
        tap(nodes, '직접 번역'); nodes = own_ui()
    tap(nodes, '이 문장 번역')
    for attempt in range(15):
        nodes = own_ui()
        values = [v for n in nodes for v in (label(n), n.get('hint', '')) if v]
        output = next((v for v in values if '산책' in v and '날씨' in v), None)
        timing = next((v for v in values if v == '이전 결과 재사용' or ('번역 ' in v and '초 · 기기에서 처리' in v)), None)
        if output and timing and (timing == '이전 결과 재사용') == cached:
            break
        if output and timing is None:
            nodes = scroll_up(nodes, 350)
    else:
        raise AssertionError('Direct translation/cache UI did not finish')
    records.append({'text': output, 'timing': timing, 'cached': cached})
print(json.dumps({'releaseUiPassed': True, 'results': records}, ensure_ascii=True, indent=2))
