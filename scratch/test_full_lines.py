import math

METERS_PER_DEG_LAT = 111320.0
def meters_per_deg_lng(lat):
    return METERS_PER_DEG_LAT * math.cos(math.radians(lat))

def dist(p1, p2):
    mlng = meters_per_deg_lng((p1[0] + p2[0]) * 0.5)
    dlat = (p2[0] - p1[0]) * METERS_PER_DEG_LAT
    dlng = (p2[1] - p1[1]) * mlng
    return math.sqrt(dlat * dlat + dlng * dlng)

def dist_to_segment(p, a, b):
    mlng = meters_per_deg_lng(p[0])
    ax = (a[1] - p[1]) * mlng
    ay = (a[0] - p[0]) * METERS_PER_DEG_LAT
    bx = (b[1] - p[1]) * mlng
    by = (b[0] - p[0]) * METERS_PER_DEG_LAT
    dx = bx - ax
    dy = by - ay
    len2 = dx * dx + dy * dy
    t = 0.0
    if len2 > 1e-9:
        t = max(0.0, min(1.0, -(ax * dx + ay * dy) / len2))
    proj_lat = p[0] + (ay + t * dy) / METERS_PER_DEG_LAT
    proj_lng = p[1] + (ax + t * dx) / mlng
    return (proj_lat, proj_lng), dist(p, (proj_lat, proj_lng)), t

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
            pr, d, t = dist_to_segment(p, self.pts[i], self.pts[i+1])
            if d < best_d:
                best_d = d
                best_off = self.cum[i] + t * (self.cum[i+1] - self.cum[i])
        return best_off, best_d

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

# Find Etxebarri in bsr_kab
etx_pos = etx_ple[0]
min_idx = 0
min_d = float('inf')
for i, p in enumerate(bsr_kab):
    d = dist(p, etx_pos)
    if d < min_d:
        min_d = d
        min_idx = i

print(f'Etxebarri is at bsr_kab index {min_idx} (dist {min_d:.1f}m)')
# Basauri to Etxebarri segment
bsr_to_etx = bsr_kab[:min_idx]
print(f'bsr_to_etx has {len(bsr_to_etx)} points')

# Full Line 1: bsr_to_etx + etx_ple
full_l1 = bsr_to_etx + etx_ple
full_l2 = bsr_kab

path_l1 = Path(full_l1)
path_l2 = Path(full_l2)

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
        sid = p[4].strip()
        if tid not in horarios: horarios[tid] = []
        horarios[tid].append((seq, sid))

for tid in horarios: horarios[tid].sort(key=lambda x: x[0])

def is_l1(shape_id, hs):
    if any(k in shape_id for k in ['PLE', 'SOP', 'URD', 'IBB', 'BID', 'LAR']): return True
    if any(k in shape_id for k in ['KAB', 'ANS']): return False
    if hs and any(k in hs.lower() for k in ['plentzia', 'sopela', 'larrabasterra', 'bidezabal', 'ibarbengoa', 'urduliz']): return True
    if hs and any(k in hs.lower() for k in ['kabiezes', 'santurtzi']): return False
    if 'BSR' in shape_id: return False
    return True

bad_projections = 0
non_mono = 0
for tid, (serv, hs, sh) in trips.items():
    if serv != 'obranegvia2_invl_26.pex' or tid not in horarios: continue
    line_is_l1 = is_l1(sh, hs)
    path = path_l1 if line_is_l1 else path_l2
    st = horarios[tid]
    offsets = []
    for seq, sid in st:
        p = stops.get(sid)
        if not p: continue
        off, d = path.project((p[1], p[2]))
        if d > 120:
            print(f'Far stop: trip {tid} ({hs}), stop {p[0]} (sid={sid}), dist={d:.1f}m')
            bad_projections += 1
        offsets.append(off)
    inc = all(offsets[i] <= offsets[i+1] for i in range(len(offsets)-1))
    dec = all(offsets[i] >= offsets[i+1] for i in range(len(offsets)-1))
    if not (inc or dec):
        non_mono += 1

print(f'Total tested trips: {sum(1 for tid, (s, _, _) in trips.items() if s == "obranegvia2_invl_26.pex")}')
print(f'Bad projections (>120m): {bad_projections}')
print(f'Non monotonic trips: {non_mono}')

