import math

METERS_PER_DEG_LAT = 111320.0
def dist(p1, p2):
    mlng = METERS_PER_DEG_LAT * math.cos(math.radians((p1[0] + p2[0]) * 0.5))
    dlat = (p2[0] - p1[0]) * METERS_PER_DEG_LAT
    dlng = (p2[1] - p1[1]) * mlng
    return math.sqrt(dlat * dlat + dlng * dlng)

def dist_to_segment(p, a, b):
    mlng = METERS_PER_DEG_LAT * math.cos(math.radians(p[0]))
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
    return dist(p, (proj_lat, proj_lng))

def dist_to_poly(p, poly):
    return min(dist_to_segment(p, poly[i], poly[i+1]) for i in range(len(poly)-1))

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
ple_etx = [(lat, lon) for seq, lat, lon in shapes['PLE_ETX']]

# Check how far ple_etx points are from etx_ple polyline
dists_ple = [dist_to_poly(p, etx_ple) for p in ple_etx]
print('PLE_ETX distance to ETX_PLE polyline:')
print(f'max={max(dists_ple):.2f}m, avg={sum(dists_ple)/len(dists_ple):.2f}m')

bsr_kab = [(lat, lon) for seq, lat, lon in shapes['BSR_KAB']]
kab_bsr = [(lat, lon) for seq, lat, lon in shapes['KAB_BSR']]
dists_kab = [dist_to_poly(p, bsr_kab) for p in kab_bsr]
print('KAB_BSR distance to BSR_KAB polyline:')
print(f'max={max(dists_kab):.2f}m, avg={sum(dists_kab)/len(dists_kab):.2f}m')

