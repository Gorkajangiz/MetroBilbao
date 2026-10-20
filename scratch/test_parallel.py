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

l1_offset = apply_parallel_offset(etx_ple, bsr_kab, 8.0)
l2_offset = apply_parallel_offset(bsr_kab, etx_ple, -8.0)

# Check distance between the two offset lines in the shared trunk
moyua = (43.26259, -2.93308)
d_orig = min(dist(moyua, q) for q in bsr_kab)
d_off1 = min(dist(l1_offset[100], q) for q in l2_offset)
print(f'Original distance between L1 and L2 at Moyua: {d_orig:.1f}m')
print(f'Offset distance between L1 and L2 in Bilbao center: {d_off1:.1f}m')

