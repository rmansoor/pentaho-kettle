#!/usr/bin/env python3
"""Collect surefire failures from every module into qa/java21/failures.json.
Run after the sweep in UNIT_TEST_REPORT.md; then run build_report.py."""
import glob, re, html, json, os, collections
import xml.etree.ElementTree as ET
root=os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
rows=[]; mods=collections.OrderedDict()
for d in sorted(glob.glob(root+'/**/target/surefire-reports', recursive=True)):
    mod=os.path.relpath(d, root).replace('/target/surefire-reports','')
    if mod.startswith('qa/'): continue
    tot=fail=err=skip=0
    for f in glob.glob(d+'/TEST-*.xml'):
        ts=ET.parse(f).getroot()
        tot+=int(ts.get('tests',0)); fail+=int(ts.get('failures',0)); err+=int(ts.get('errors',0)); skip+=int(ts.get('skipped',0))
        for tc in ts.iter('testcase'):
            for el in list(tc):
                if el.tag not in ('error','failure'): continue
                causes=[l.strip() for l in (el.text or '').splitlines() if l.startswith('Caused by')]
                rows.append(dict(module=mod, cls=tc.get('classname') or os.path.basename(f)[5:-4], test=tc.get('name'),
                                 kind=el.tag, type=el.get('type',''), msg=re.sub(r'\s+',' ',el.get('message') or '')[:300],
                                 cause=causes[-1][:300] if causes else ''))
                break
    mods[mod]=dict(tests=tot,failures=fail,errors=err,skipped=skip)
json.dump(dict(modules=mods,rows=rows),open(os.path.join(os.path.dirname(__file__), '..', 'failures.json'),'w'),indent=1)
T=sum(m['tests'] for m in mods.values()); F=sum(m['failures']+m['errors'] for m in mods.values())
print('modules with reports',len(mods),'tests',T,'failing',F)
for k,m in mods.items():
    if m['failures']+m['errors']: print(f"{m['failures']+m['errors']:5} / {m['tests']:5}  {k}")
