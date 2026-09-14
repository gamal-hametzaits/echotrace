#!/usr/bin/env python3
from pathlib import Path
import re, sys, xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
A='{http://schemas.android.com/apk/res/android}'
issues=[]
def px(path):
    return ET.parse(root/path).getroot()
manifest=px('android/app/src/main/AndroidManifest.xml')
names=[x.get(A+'name') for x in manifest.findall('.//activity')]
for retired in ['.PairingActivity','.DisconnectedActivity']:
    if retired in names: issues.append(f'retired activity remains: {retired}')
main=px('android/app/src/main/res/layout/activity_main.xml')
if main.tag!='ScrollView' or main.get(A+'fillViewport')!='true': issues.append('main is not small-screen scrollable')
for view_id in ['partnerCode','connectBtn','addWidget','startOver']:
    nodes=[n for n in main.iter() if n.get(A+'id','').endswith('/'+view_id)]
    if not nodes: issues.append(f'missing {view_id}'); continue
    h=nodes[0].get(A+'minHeight')
    if h!='48dp' and view_id!='partnerCode': issues.append(f'{view_id} touch height is {h}')
if not any(n.get(A+'textIsSelectable')=='true' for n in main.iter() if n.get(A+'id','').endswith('/myCode')): issues.append('personal code is not selectable')
for path in ['android/app/src/main/res/layout/activity_main.xml','android/app/src/main/res/layout/activity_camera.xml']:
    txt=(root/path).read_text()
    if 'paddingLeft=' in txt or 'paddingRight=' in txt or re.search(r'layout_gravity=\"[^\"]*(?:left|right)', txt): issues.append(f'RTL-hardcoded attribute in {path}')
wm=manifest.find(".//activity[@android:name='.MainActivity']",{'android':'http://schemas.android.com/apk/res/android'})
if wm is None or wm.get(A+'windowSoftInputMode')!='adjustResize': issues.append('main adjustResize missing')
wc=manifest.find(".//activity[@android:name='.CameraActivity']",{'android':'http://schemas.android.com/apk/res/android'})
if wc is None or wc.get(A+'windowSoftInputMode')!='adjustResize': issues.append('camera adjustResize missing')
renderer=(root/'android/app/src/main/java/com/echotrace/app/WidgetRenderer.kt').read_text()
for state in ['partner == null','disconnected']:
    if state not in renderer: issues.append(f'widget state missing: {state}')
if renderer.count('rootClick(MainActivity::class.java') < 3: issues.append('widget states do not consistently route to main')
if issues:
    print('\n'.join('FAIL: '+i for i in issues)); sys.exit(1)
print('PASS: pairing routes to main; small-screen scrolling, 48dp targets, adjustResize, RTL and widget state routing verified')
