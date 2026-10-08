import re,glob
from collections import defaultdict
S=__import__('os').path.join(__import__('os').path.dirname(__file__), 'results')
def pct(v,p):
    v=sorted(v); k=(len(v)-1)*p; f=int(k); c=min(f+1,len(v)-1); return v[f]+(v[c]-v[f])*(k-f)
for dev in ('a21s','p7a'):
  for size in ('720x1280','1080x2400'):
    data=defaultdict(list); snap=[]; pre=defaultdict(list); nof=0
    for f in glob.glob(f'{S}/{dev}-r*-template_matching_{size}.file.log'):
      for line in open(f):
        if 'noframe' in line: nof+=1
        m=re.search(r' tm (frame=.*)',line)
        if not m: continue
        d=dict(re.findall(r'(\w+)=(\S+)',m.group(1)))
        fw,fh=map(int,d['frame'].split('x')); tw,th=map(int,d['target'].split('x'))
        tpl=int(d['tpl'].split('x')[0])
        if tw==fw: var='gray' if d['gray']=='1' else 'color'
        elif tw==fw//2 and th==fh//2: var='scale0.5'; tpl*=2
        else: var='roi'
        data[(var,tpl)].append(float(d['mt'])); snap.append(float(d['snap'])); pre[var].append(float(d['pre']))
    print(f"== {dev} {size}  snap p50={pct(snap,.5):.1f}  pre(gray)={pct(pre['gray'],.5):.1f} pre(scale)={pct(pre['scale0.5'],.5):.1f} noframe={nof}")
    for var in ('color','gray','roi','scale0.5'):
      print(f"  {var:9}", "  ".join(f"t{t}:{pct(data[(var,t)],.5):6.0f}/{pct(data[(var,t)],.9):6.0f}(n{len(data[(var,t)])})" if data[(var,t)] else f"t{t}: -" for t in (32,64,128,256)))
