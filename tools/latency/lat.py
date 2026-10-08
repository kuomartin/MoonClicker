import re, sys, statistics as st
from collections import defaultdict
def pct(v,p):
    v=sorted(v); k=(len(v)-1)*p; f=int(k); c=min(f+1,len(v)-1); return v[f]+(v[c]-v[f])*(k-f)
def kv(line):
    return dict(re.findall(r'(\w+)=([^\s]+)', line))
groups=defaultdict(lambda: defaultdict(list))
for line in open(sys.argv[1], errors='ignore'):
    m=re.search(r'LAT\s*:\s*(\w+)\s(.*)', line) or re.match(r'^\d+\.\d+ (\w+)\s(.*)', line)
    if not m: continue
    kind, rest = m.group(1), m.group(2)
    d=kv(rest)
    if kind=='tm':
        key=f"tm frame={d['frame']} tpl={d['tpl']} target={d['target']} gray={d['gray']}"
        for f in ('snap','pre','mt'): groups[key][f].append(float(d[f]))
    elif kind=='ocr':
        key=f"ocr mode={d['mode']} area={d['area']}"
        for f in ('det','rec','total'): groups[key][f].append(float(d[f]))
    elif kind in ('gles','h264','copy','wait','inject','cpu','begin'):
        print(kind, rest)
for key, fs in groups.items():
    n=len(next(iter(fs.values())))
    print(key, f"n={n}", " ".join(f"{f}:p50={pct(v,.5):.1f}/p90={pct(v,.9):.1f}" for f,v in fs.items()))
