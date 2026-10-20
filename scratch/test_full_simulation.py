import math

METERS_PER_DEG_LAT = 111320.0
def meters_per_deg_lng(lat):
    return METERS_PER_DEG_LAT * math.cos(math.radians(lat))

def dist(p1, p2):
    mlng = meters_per_deg_lng((p1[0] + p2[0]) * 0.5)
    dlat = (p2[0] - p1[0]) * METERS_PER_DEG_LAT
    dlng = (p2[1] - p1[1]) * mlng
    return math.sqrt(dlat * dlat + dlng * dlng)

def smooth_offset_factor(d, threshold=100.0):
    if d >= threshold: return 0.0
    t = 1.0 - d / threshold
    return t * t * (3.0 - 2.0 * t)

def offset_perpendicular(p, a, b, meters):
    mlng = meters_per_deg_lng(p[0])
    tx = (b[1] - a[1]) * mlng
    ty = (b[0] - a[0]) * METERS_PER_DEG_LAT
    tlen = math.sqrt(tx * tx + ty * ty)
    if tlen < 1e-9: return p
    tx /= tlen
    ty /= tlen
    px = -ty
    py = tx
    return (p[0] + (py * meters) / METERS_PER_DEG_LAT,
            p[1] + (px * meters) / mlng)

def apply_parallel_offset(polyline, other, max_offset):
    n = len(polyline)
    if n < 2: return list(polyline)
    res = []
    for i in range(n):
        p = polyline[i]
        min_d = min(dist(p, q) for q in other)
        factor = smooth_offset_factor(min_d)
        if factor <= 0.001:
            res.append(p)
            continue
        if i == 0: prev_p, next_p = polyline[0], polyline[1]
        elif i == n - 1: prev_p, next_p = polyline[n - 2], polyline[n - 1]
        else: prev_p, next_p = polyline[i - 1], polyline[i + 1]
        res.append(offset_perpendicular(p, prev_p, next_p, factor * max_offset))
    return res

class Path:
    def __init__(self, pts):
        self.pts = pts
        self.cum = [0.0] * len(pts)
        for i in range(1, len(pts)):
            self.cum[i] = self.cum[i-1] + dist(pts[i-1], pts[i])
        self.total = self.cum[-1]

    def project(self, p):
        best_d = float('inf')
        best_off = 0.0
        for i in range(len(self.pts)-1):
            a, b = self.pts[i], self.pts[i+1]
            mlng = meters_per_deg_lng(p[0])
            ax = (a[1] - p[1]) * mlng
            ay = (a[0] - p[0]) * METERS_PER_DEG_LAT
            bx = (b[1] - p[1]) * mlng
            by = (b[0] - p[0]) * METERS_PER_DEG_LAT
            dx, dy = bx - ax, by - ay
            len2 = dx * dx + dy * dy
            t = 0.0
            if len2 > 1e-9:
                t = max(0.0, min(1.0, -(ax * dx + ay * dy) / len2))
            proj_lat = p[0] + (ay + t * dy) / METERS_PER_DEG_LAT
            proj_lng = p[1] + (ax + t * dx) / mlng
            d = dist(p, (proj_lat, proj_lng))
            if d < best_d:
                best_d = d
                best_off = self.cum[i] + t * (self.cum[i+1] - self.cum[i])
        return best_off, best_d

    def point_at(self, offset):
        if offset <= 0: return self.pts[0]
        if offset >= self.total: return self.pts[-1]
        lo, hi = 0, len(self.pts) - 1
        while lo < hi - 1:
            mid = (lo + hi) >> 1
            if self.cum[mid] <= offset: lo = mid
            else: hi = mid
        seg_len = self.cum[lo+1] - self.cum[lo]
        t = (offset - self.cum[lo]) / seg_len if seg_len > 0 else 0
        a, b = self.pts[lo], self.pts[lo+1]
        return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)

# Load shapes
shapes = {}
with open('app/src/main/assets/formas.txt', encoding='utf-8') as f:
    f.readline()
    for line in f:
        p = line.strip().split(',')
        if len(p) < 4: continue
        sid, lat, lon, seq = p[0].strip(), float(p[1]), float(p[2]), int(p[3])
        if sid not in shapes: shapes[sid] = []
        shapes[sid].append((seq, lat, lon))
for sid in shapes: shapes[sid].sort(key=lambda x: x[0])

etx_ple = [(lat, lon) for seq, lat, lon in shapes['ETX_PLE']]
bsr_kab = [(lat, lon) for seq, lat, lon in shapes['BSR_KAB']]

etx_pos = etx_ple[0]
min_idx = min(range(len(bsr_kab)), key=lambda i: dist(bsr_kab[i], etx_pos))
full_l1_raw = bsr_kab[:min_idx] + etx_ple
full_l2_raw = bsr_kab

l1_off = apply_parallel_offset(full_l1_raw, full_l2_raw, 7.0)
l2_off = apply_parallel_offset(full_l2_raw, full_l1_raw, -7.0)

path_l1 = Path(l1_off)
path_l2 = Path(l2_off)

# Load paradas
stops = {}
with open('app/src/main/assets/paradas.txt', encoding='utf-8') as f:
    f.readline()
    for line in f:
        p = line.strip().split(',')
        if len(p) >= 5:
            stops[p[0].strip()] = (p[2].strip(), float(p[3]), float(p[4]))

# Load viajes
trips = {}
with open('app/src/main/assets/viajes.txt', encoding='utf-8') as f:
    f.readline()
    for line in f:
        p = line.strip().split(',')
        if len(p) >= 6:
            trips[p[0].strip()] = (p[2].strip(), p[3].strip(), p[5].strip())

horarios = {}
with open('app/src/main/assets/horarios.txt', encoding='utf-8') as f:
    f.readline()
    for line in f:
        p = line.strip().split(',')
        if len(p) < 5: continue
        tid = p[0].strip()
        seq = int(p[1].strip())
        arr = p[2].strip()
        dep = p[3].strip()
        sid = p[4].strip()
        def to_sec(s):
            h, m, sec = map(int, s.split(':'))
            return h * 3600 + m * 60 + sec
        if tid not in horarios: horarios[tid] = []
        horarios[tid].append({'seq': seq, 'arr': to_sec(arr), 'dep': to_sec(dep), 'sid': sid})

for tid in horarios: horarios[tid].sort(key=lambda x: x['seq'])

def is_l1(shape_id, hs):
    if any(k in shape_id for k in ['PLE', 'SOP', 'URD', 'IBB', 'BID', 'LAR']): return True
    if any(k in shape_id for k in ['KAB', 'ANS']): return False
    if hs and any(k in hs.lower() for k in ['plentzia', 'sopela', 'larrabasterra', 'bidezabal', 'ibarbengoa', 'urduliz']): return True
    if hs and any(k in hs.lower() for k in ['kabiezes', 'santurtzi']): return False
    if 'BSR' in shape_id: return False
    return True

# Precompute trips
precomputed = {}
for tid, (serv, hs, sh) in trips.items():
    if serv != 'obranegvia2_invl_26.pex' or tid not in horarios: continue
    line_is_l1 = is_l1(sh, hs)
    path = path_l1 if line_is_l1 else path_l2
    st = horarios[tid]
    offsets = []
    for s in st:
        p = stops.get(s['sid'])
        if not p:
            offsets.append(offsets[-1] if offsets else 0.0)
            continue
        off, d = path.project((p[1], p[2]))
        offsets.append(off)
    precomputed[tid] = {
        'line': 'L1' if line_is_l1 else 'L2',
        'headsign': hs,
        'path': path,
        'stops': st,
        'offsets': offsets,
        'start': st[0]['arr'],
        'end': st[-1]['dep']
    }

print(f'Precomputed {len(precomputed)} trips on offset master paths!')

# Simulate at 14:26:00
now = 14 * 3600 + 26 * 60
active = []
for tid, data in precomputed.items():
    if data['start'] <= now <= data['end']:
        # Check train position
        st = data['stops']
        offs = data['offsets']
        path = data['path']
        pos = None
        status = ''
        for i in range(len(st)):
            if st[i]['arr'] <= now <= st[i]['dep']:
                pos = path.point_at(offs[i])
                status = f'At {stops[st[i]["sid"]][0]} (dwell)'
                break
            if i < len(st) - 1 and st[i]['dep'] < now < st[i+1]['arr']:
                dur = st[i+1]['arr'] - st[i]['dep']
                p = (now - st[i]['dep']) / dur
                p_sm = p * p * (3.0 - 2.0 * p)
                off = offs[i] + p_sm * (offs[i+1] - offs[i])
                pos = path.point_at(off)
                status = f'{stops[st[i]["sid"]][0]} -> {stops[st[i+1]["sid"]][0]} ({p*100:.0f}%)'
                break
        active.append((tid, data['line'], data['headsign'], status, pos))

print(f'Active trains at 14:26:00: {len(active)}')
for a in active[:8]:
    print(f'  {a[1]} to {a[2]:12s} | {a[3]}')

